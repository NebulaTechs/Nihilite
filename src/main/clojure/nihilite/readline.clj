(ns nihilite.readline
  "jline3-backed raw-branch REPL loop. Every raw client (telnet char-mode,
   `socat STDIO,raw,echo=0`, cooked `nc`) shares the same UX: char-by-char
   echo, 1000-entry shared history (Up/Down, C-r), TAB completion, cursor
   motion (C-a/e/k/u/w, arrows), paren-balanced multi-line continuation,
   C-c eval-cancel, C-d exit-on-empty, friendly non-leaky error rendering.

   Terminal type is `xterm` (NOT `dumb`): jline3 needs raw-mode terminal
   features for history/completion, and the pre-existing `:char` path
   already proved `xterm` works over a socket via `ExternalTerminal`.

   No dependency on `transport.clj`; depends only on `nihilite.readline.history`,
   `nihilite.readline.completion`, `nihilite.errors`, and jline3."
  (:require [clojure.string :as str]
            [nihilite.readline.history :as hist]
            [nihilite.readline.completion :as completion]
            [nihilite.errors :as errors])
  (:import [java.io InputStream OutputStream]
           [java.nio.charset StandardCharsets]
           [org.jline.terminal Terminal Terminal$SignalHandler]
           [org.jline.terminal.impl ExternalTerminal]
           [org.jline.reader LineReader LineReaderBuilder
                             EndOfFileException UserInterruptException]
           [org.jline.reader.impl DefaultParser]
           [org.jline.reader.impl.history DefaultHistory]
           [org.jline.utils NonBlockingReader]))

(def ^:const raw-prompt "> ")
(def ^:const continuation-prompt "  ")
(def ^:const ^:long max-form-bytes 65536)
(def ^:const ^:long cancel-poll-ms 50)

(def ^:const banner
  (str "nihilite raw REPL — C-d exits on empty buffer, C-c cancels eval,\r\n"
       "C-l clears screen, TAB completes, history up/down.\r\n"))

(defn paren-balance
  "Net bracket balance of `s` (positive = more opens). Counts (), [], {}.
   Naive — does not skip string/char/comment content."
  [^String s]
  (loop [i 0 bal 0]
    (if (>= i (.length s))
      bal
      (let [c (.charAt s i)]
        (recur (inc i)
               (cond
                 (or (= c \() (= c \[) (= c \{)) (inc bal)
                 (or (= c \)) (= c \]) (= c \})) (dec bal)
                 :else bal))))))

(defn render-error
  "Render the canonical `nihilite.errors/format` map to a friendly,
   non-leaky multi-line string (CRLF-terminated per line). Shape:

     ERROR [<kind>] <message>
       at <location>
       cause: <cause-message> at <cause-location>
       data: <edn>

   Only non-nil fields are emitted. The `:causes` vector's first entry
   is the top error itself (already shown on the ERROR line), so only
   causes[1..] are rendered as `cause:` lines."
  ^String [error-map]
  (let [{:keys [kind message location causes data]} error-map
        sb (StringBuilder.)]
    (.append sb (str "ERROR [" kind "] " message "\r\n"))
    (when (and location (not= location "<no source location>"))
      (.append sb (str "  at " location "\r\n")))
    (doseq [c (rest causes)]
      (.append sb (str "  cause: " (:message c)
                       (when-let [l (:location c)]
                         (when (not= l "<no source location>")
                           (str " at " l)))
                       (when (:truncated? c) " …")
                       "\r\n")))
    (when (some? data)
      (.append sb (str "  data: " (if (string? data) data (pr-str data)) "\r\n")))
    (.toString sb)))

(defn eval-form
  "Eval `form-str` in the ns held by `repl-state`, thread `*1/*2/*3/*e`,
   and return a CRLF-terminated display string:
     success → `=> <pr-str>\r\n`
     failure → friendly ERROR block via `render-error` (errors/format)

   `repl-state` is a per-connection atom {:ns Namespace :*1 :*2 :*3 :*e}.
   Reflection warnings stay on `*err*` (not folded into the result)."
  ^String [^String form-str repl-state]
  (let [{:keys [ns *1 *2 *3 *e]} @repl-state]
    (try
      (let [r (binding [*ns* ns
                        clojure.core/*1 *1
                        clojure.core/*2 *2
                        clojure.core/*3 *3
                        clojure.core/*e *e]
                (eval (read-string form-str)))]
        (swap! repl-state assoc :*1 r :*2 *1 :*3 *2)
        (str "=> " (pr-str r) "\r\n"))
      (catch Throwable t
        (swap! repl-state assoc :*e t)
        (render-error (errors/format t))))))

(defn build-terminal
  "Build a jline3 `ExternalTerminal` (type `xterm`) over the socket
   streams. `ExternalTerminal` spawns a daemon pump thread copying
   `in` → jline's slave pipe; TerminalBuilder's FFM provider would try
   /dev/tty and never read our socket. SIG_IGN so jline never touches
   the JVM SIGINT policy (boot.clj owns that)."
  ^Terminal [^InputStream in ^OutputStream out ^String name]
  (let [term (ExternalTerminal.
               nil
               name
               "xterm"
               in
               out
               StandardCharsets/UTF_8
               Terminal$SignalHandler/SIG_IGN
               false)]
    (.resume term)
    term))

(defn build-reader
  "Build a `LineReader` bound to `terminal`, with a Clojure-aware TAB
   completer sourced against the ns held in `repl-state`, and a
   `DefaultHistory` synced from the shared server-wide deque before
   each read."
  ^LineReader [^Terminal terminal repl-state]
  (let [current-ns (:ns @repl-state)
        completer  (completion/completer-for current-ns)
        history    (DefaultHistory.)
        reader (-> (LineReaderBuilder/builder)
                   (.terminal terminal)
                   (.completer completer)
                   (.parser (DefaultParser.))
                   (.history history)
                   (.build))]
    reader))

(defn sync-history-into-reader!
  "Purge the reader's jline history and reload it from the shared
   server-wide deque so Up/Down and C-r see cross-socket entries.
   O(n), n ≤ 1000."
  [^LineReader reader]
  (let [h (.getHistory reader)]
    (.purge h)
    (doseq [entry (hist/history-entries)]
      (.add h ^String entry))))

(defn read-balanced-form
  "Read one balanced Clojure form via jline `readLine`. Accumulates
   continuation lines until paren-balance ≤ 0. Returns:
     {:form s}      balanced form ready to eval
     {:eof true}    C-d on an empty buffer
     {:intr true}   C-c during input (abort current form)
     {:blank true}  lone blank first line (skip / reprompt)
     {:overflow true} form exceeded max-form-bytes

   jline gives us — for free, in-line — char echo, Backspace, arrows,
   C-a/e/k/u/w, TAB completion, Up/Down + C-r history."
  [^LineReader reader]
  (sync-history-into-reader! reader)
  (loop [buf (StringBuilder.)
         prompt raw-prompt]
    (let [line (try
                 (.readLine reader prompt)
                 (catch UserInterruptException _ ::intr)
                 (catch EndOfFileException _ ::eof))]
      (cond
        (= line ::eof)  {:eof true}
        (= line ::intr) {:intr true}
        :else
        (let [first? (zero? (.length buf))]
          (if (and first? (str/blank? line))
            {:blank true}
            (do
              (.append buf line)
              (.append buf "\n")
              (cond
                (> (.length buf) max-form-bytes)
                {:overflow true}

                (<= (paren-balance (.toString buf)) 0)
                {:form (.toString buf)}

                :else
                (recur buf continuation-prompt)))))))))

(defn eval-with-cancel
  "Run `(eval-form form-str repl-state)` in a future. While it runs,
   poll the terminal's NonBlockingReader for C-c (0x03). On C-c:
   `future-cancel` (interrupts Thread/sleep / blocking IO; does NOT
   stop a pure CPU busy loop) and return an `:interrupted` marker.
   Worst-case latency between eval completion and prompt redraw is
   one `cancel-poll-ms` window."
  ^String [^Terminal terminal ^String form-str repl-state]
  (let [fut (future (eval-form form-str repl-state))
        ^NonBlockingReader nbr (.reader terminal)]
    (loop []
      (if (future-done? fut)
        @fut
        (let [c (try (.read nbr cancel-poll-ms) (catch Throwable _ -1))]
          (cond
            (= c 3) ; Ctrl-C
            (do (future-cancel fut)
                "\r\n;; interrupted\r\n")
            :else (recur)))))))

(defn run-loop
  "Interactive jline3 REPL over `terminal`. `repl-state` is a
   per-connection atom {:ns Namespace :*1 :*2 :*3 :*e}. `write!` is a
   1-arg fn that writes a CRLF-normalized string to the client.

   Returns when the client hits C-d on an empty buffer or submits the
   literal `(exit)` form — byebye + close, never System/exit. Each
   submitted balanced form is pushed to the shared history, then
   eval'd through the C-c-cancellable watcher.

   Loop actions:
     :form     → push history, eval (cancellable), write result
     :blank    → reprompt (skip)
     :intr     → C-c during input: abort form, reprompt
     :overflow → ERROR + clear buffer + reprompt (64 KiB cap)
     :eof/exit → return (caller writes bye + closes socket)"
  [^Terminal terminal repl-state write!]
  (let [reader (build-reader terminal repl-state)]
    (loop []
      (let [r (read-balanced-form reader)]
        (cond
          (:eof r)   :eof
          (:intr r)  (recur)
          (:blank r) (recur)

          (:overflow r)
          (do (write! (str "ERROR [transport-error] form exceeds "
                           max-form-bytes " bytes; buffer cleared\r\n"))
              (recur))

          :else
          (let [form (:form r)]
            (if (= "(exit)" (str/trim form))
              :exit
              (do
                (hist/history-add! (str/trim form))
                (write! (eval-with-cancel terminal form repl-state))
                (recur)))))))))
