(ns nihilite.readline.eval
  "Evaluation primitive shared by every transport branch (raw REPL,
   bencode, HTTP /v1/eval, WebSocket per-frame). Threads the
   classic *1 / *2 / *3 / *e REPL bindings so the user sees the
   same behavior they would in clojure.main.

   The return value is a CRLF- or LF-terminated display string:
     success → `=> <pr-str>\r\n`
     failure → friendly ERROR block via `nihilite.errors/format`."
  (:require [nihilite.errors :as errors]))

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

(defn- eval-form-line ^String [^String form-str repl-state ^String term]
  (let [{:keys [ns *1 *2 *3 *e]} @repl-state]
    (try
      (let [r (binding [*ns* ns
                        clojure.core/*1 *1
                        clojure.core/*2 *2
                        clojure.core/*3 *3
                        clojure.core/*e *e]
                (eval (read-string form-str)))]
        (swap! repl-state assoc :*1 r :*2 *1 :*3 *2)
        (str "=> " (pr-str r) term))
      (catch Throwable t
        (swap! repl-state assoc :*e t)
        (render-error (errors/format t))))))

(defn eval-form
  "Evaluates `form-str` in the namespace held by `repl-state`.
   Returns a CRLF-terminated display string."
  ^String [^String form-str repl-state]
  (eval-form-line form-str repl-state "\r\n"))

(defn eval-form-lf
  "Same evaluation contract and result shape as `eval-form`, but
   terminates the line with a bare LF instead of CRLF. Used by the
   raw branch in `nihilite.transport` where the client (nc / socat)
   does its own line discipline and CRLF would interfere."
  ^String [^String form-str repl-state]
  (eval-form-line form-str repl-state "\n"))
