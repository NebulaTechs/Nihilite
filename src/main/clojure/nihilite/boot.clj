(ns nihilite.boot
  "Server bootstrap. start! binds ONE loopback server.
   start! {:port 7888 :bind \"127.0.0.1\"} -> {:server stop-fn};
   stop! accepts {:server stop-fn} or bare IFn, idempotent;
   eval-init! reads nihilite.init as a Clojure form string and evals it."
  (:require [clojure.tools.logging :as log]
            [nihilite.version :as v]
            [nihilite.transport :as transport])
  (:gen-class))

(defonce ^:private ready-fn (atom nil))

(def ^:private init-property-name "nihilite.init")

(def ^:private init-default "(do (require 'clojure.repl) (in-ns 'user))")

(defn set-ready!
  [f]
  (reset! ready-fn f)
  f)

(defn await-runtime-ready!
  []
  (when-let [f @ready-fn]
    (f)))

(defn- loopback? [^String host]
  (or (= host "127.0.0.1")
      (= host "::1")
      (= host "localhost")
      (nil? host)
      (= host "")))

(defn start!
  [& {:keys [port bind]
      :or {bind "127.0.0.1"
           port 7888}}]
  (log/info "nihilite version" v/version)
  (when-not (loopback? bind)
    (log/warn "bound on non-loopback host" bind
              "— REPL is unauthenticated, do NOT expose to a shared network"))
  (log/info "starting canonical server on" bind ":" port
            "(nREPL bencode, single socket)")
  (let [stop-fn (transport/start! {:port port :bind bind})]
    {:server stop-fn}))

(defn stop!
  [server-or-handle]
  (let [stop-fn (cond
                  (map? server-or-handle)      (:server server-or-handle)
                  (ifn? server-or-handle)      server-or-handle
                  :else                       nil)]
    (when stop-fn
      (log/info "stopping canonical server")
      (try
        (stop-fn)
        (log/info "canonical server stopped")
        (catch Throwable t
          (log/error t "stop failed"))))))

(defn eval-init!
  []
  (let [form (or (System/getProperty init-property-name) init-default)]
    (try
      (log/info "eval init:" form)
      (eval (read-string form))
      (log/info "init eval done")
      form
      (catch Throwable t
        (log/error t "init eval failed")
        nil))))

(defn -main [& _]
  (log/info "nihilite.boot -main invoked; this ns is a library, not an entry point")
  (log/info "use nihilite.server.ServerMain as the Main-Class"))