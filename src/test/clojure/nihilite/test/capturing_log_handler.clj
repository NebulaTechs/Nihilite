(ns nihilite.test.capturing-log-handler
  "java.util.logging.Handler implementation that captures all log records
   published at Level >= WARNING. Replaces the previous Java test class
   of the same name.

   Returns a plain Clojure map with `:handler` (the proxy implementing
   `java.util.logging.Handler`) and `:captured` (synchronized list of
   LogRecord instances)."
  (:import [java.util.logging Handler Level LogRecord]
           [java.util ArrayList Collections]))

(defn- capturing-handler
  "Returns a proxy implementing java.util.logging.Handler; the captured
   list is mutated in-place on each `publish` call."
  [captured]
  (proxy [Handler] []
    (publish [record]
      (when (>= (.intValue (.getLevel ^LogRecord record)) (.intValue Level/WARNING))
        (.add ^java.util.List captured record)))
    (flush [])
    (close [])))

(defn make
  "Returns a map with `:handler` (the proxy) and `:captured` (the
   synchronized list of LogRecord instances)."
  []
  (let [captured (Collections/synchronizedList (ArrayList.))]
    {:handler (capturing-handler captured)
     :captured captured}))

(defn captured
  "Returns the captured records list from a handler map created by `make`."
  [m]
  (when (map? m) (:captured m)))
