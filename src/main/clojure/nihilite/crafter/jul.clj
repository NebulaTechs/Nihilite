(ns nihilite.crafter.jul
  "java.util.logging helpers, shared by the namespaces that log through JUL
   directly rather than through clojure.tools.logging.

   Four namespaces did this their own way. installer and agent each grew their
   own trio of log-info/log-warn/log-error over their own Logger, worker grew a
   single log-info, boot took a Logger inline. The signatures had drifted:
   installer's log-error took a Throwable and agent's could not, and only
   installer's log-info concatenated a message from several arguments. Three
   prefixes for one product: \"HookInstaller \", \"[Nihilite Agent] \",
   \"[Nihilite] \".

   What this module is NOT for: replacing the JUL calls with
   clojure.tools.logging. agent and worker take their Logger from
   tools.logging's factory on purpose, so the backend follows the user's
   clojure.tools.logging.factory property, and worker needs that without
   clojure.java.api. Both say so where they do it.

   What it does settle: the helper signatures, and the rule that a logger name
   is part of the observable surface. uninstall_warn_test attaches a handler to
   \"nihilite.trainer.installer\" and asserts on what installer logs, so that
   name is a contract that outlives any namespace rename. A logger named after
   its namespace is not a contract -- it breaks the moment the namespace moves,
   which is what the crafter/trainer/builder split did to half of them."
  (:import [java.util.logging Logger Level]))

(defn logger
  "The logger for `nm`, which should be a stable dotted name rather than one
   derived from a namespace."
  ^Logger [nm]
  (Logger/getLogger ^String nm))

(defn info
  "Logs at INFO. Several arguments are concatenated, so a call can interleave
   computed values without the format-string noise at every site."
  [^Logger l & msgs]
  (.info l (apply str msgs))
  nil)

(defn warn
  "Logs at WARNING."
  [^Logger l & msgs]
  (.warning l (apply str msgs))
  nil)

(defn fine
  "Logs at FINE. boot narrates a skipped init here, which is below WARNING and
   so invisible on a logger left at its default level."
  [^Logger l & msgs]
  (.log l Level/FINE (apply str msgs))
  nil)

(defn error
  "Logs at SEVERE. A leading Throwable is logged as the record's thrown rather
   than concatenated into its message, so a call site can pass the exception
   it caught without giving up the message.

   Both shapes are in use and both are worth keeping: agent had no Throwable to
   hand at its one error site, installer had one at both of its."
  [^Logger l & msgs]
  (if (instance? Throwable (first msgs))
    (.log l Level/SEVERE (apply str (rest msgs)) ^Throwable (first msgs))
    (.severe l (apply str msgs)))
  nil)
