(ns nihilite.readline.loop
  "Interactive jline3 REPL read-and-eval loop. The raw branch
   (telnet / socat) constructs a Terminal + LineReader via
   `nihilite.readline.terminal`, then calls `run-loop` here
   with a per-connection `repl-state` atom and a `write!` fn.

   Eval-cancel: while a form is being evaluated in a future, we
   poll the terminal's NonBlockingReader for C-c (0x03). On C-c
   the future is cancelled and an `:interrupted` marker is
   written back. Worst-case latency between eval completion and
   prompt redraw is one `cancel-poll-ms` window."
  (:require [clojure.string :as str]
            [nihilite.readline.eval :as eval]
            [nihilite.readline.history :as hist]
            [nihilite.readline.paren-balance :as pb]
            [nihilite.readline.terminal :as term])
  (:import [org.jline.terminal Terminal]
           [org.jline.reader LineReader
                             UserInterruptException
                             EndOfFileException]
           [org.jline.utils NonBlockingReader]))

(def ^:const raw-prompt "> ")
(def ^:const continuation-prompt "  ")
(def ^:const ^:long max-form-bytes 65536)
(def ^:const ^:long cancel-poll-ms 50)

(def ^:const banner
  (str "nihilite raw REPL — C-d exits on empty buffer, C-c cancels eval,\r\n"
       "C-l clears screen, TAB completes, history up/down.\r\n"))

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
  (term/sync-history-into-reader! reader)
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

                (<= (pb/paren-balance (.toString buf)) 0)
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
  (let [fut (future (eval/eval-form form-str repl-state))
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
  (let [reader (term/build-reader terminal repl-state)]
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
