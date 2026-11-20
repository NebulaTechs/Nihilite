(ns nihilite.builder.registry.runtime
  "Dependency-free runtime state shared by the builder and the trainer.

   The mutable registry is builder code, but the trainer has to publish the
   Instrumentation, entry point, dispatcher and uninstaller it owns. Keeping
   those four holders here lets both sides meet at a leaf instead of making
   either side load the other's machinery. This namespace deliberately owns no
   registry mutation, dispatch, index or hook logic.")

(defonce ^{:doc "The live java.lang.instrument.Instrumentation, or nil before an
  agent publishes one."}
  instrumentation-ref
  (atom nil))

(defonce ^{:doc "The keyword naming the agent entry point, or nil before an
  agent publishes one."}
  entry-point-ref
  (atom nil))

(defonce ^{:doc "The :redefine dispatch IFN, or nil before one is installed."}
  redefine-dispatcher-ref
  (atom nil))

(defonce ^{:doc "The Trainer-side uninstall entry point, or nil before the
  installer publishes one."}
  uninstaller-ref
  (atom nil))

(defn register-instrumentation!
  "Publishes the live Instrumentation, ignoring nil."
  [^java.lang.instrument.Instrumentation inst]
  (when inst
    (reset! instrumentation-ref inst))
  inst)

(defn set-entry-point!
  "Publishes the agent entry point label."
  [entry-point]
  (reset! entry-point-ref entry-point))

(defn set-redefine-dispatcher!
  "Publishes the :redefine dispatch IFN and returns it."
  [dispatch-ifn]
  (reset! redefine-dispatcher-ref dispatch-ifn)
  dispatch-ifn)

(defn register-uninstaller!
  "Publishes the Trainer-side uninstall entry point and returns it."
  [uninstaller]
  (reset! uninstaller-ref uninstaller)
  uninstaller)
