(ns nihilite.test.weaving-contract-driver
  "The contract suite cannot observe weaving. build.clj runs the contract pass
   as `clojure.main` with `:opts []` -- no -javaagent, no attach, no
   Instrumentation -- so every `install!` there returns true at
   `:woven-count 0` and all 390 assertions pass without a single class ever
   being transformed. Verified by instrumenting the ByteBuddy matcher: the
   contract suite records ZERO matcher invocations.

   That leaves `install!`/`uninstall!`'s lock-held-across-retransform span
   (registry.clj:531->549, :585->601) with no coverage at all -- and that span
   is the entire reason the lock exists. Any fix that reorders or narrows it
   would regress invisibly.

   This driver is that missing coverage. It is the only place where the
   registry's own claims can be compared against what the JVM actually did,
   because it is a place where Instrumentation is real.

   Three phases, each pinning one direction the registry can lie in:

     1. fresh install       -- :registered? true, :woven-count 1, :fired 1
     2. uninstall, reinstall-- same four must hold again
     3. clear!, reinstall   -- same four must hold again

   Phase 3 is the one that catches `reg/clear!` never retransforming
   (registry.clj:629-636): clear! wipes the indexes but never calls the
   uninstaller, so every re-woven class stays woven pointing at specs that no
   longer exist, and the reinstall reports :woven-count 1 while firing 0
   forever. The contract suite's own fixture is `reg/clear!`
   (fixtures.clj:5-8), so it manufactures that state before every test.

   Every phase compares the registry's `:fired` against an atom incremented by
   the bridge itself. Comparing only registry-to-registry would pass even if
   both counters were wrong the same way; the atom is what actually ran."
  (:require [nihilite.builder.api :as api]
            [nihilite.builder.registry :as reg]
            [nihilite.builder.registry.dispatch])
  (:import [net.bytebuddy.agent ByteBuddyAgent]
           [java.util BitSet])
  (:gen-class
    :name nihilite.test.weavingContractDriver
    :prefix "wcd-"
    :main true))

(def ^:private fired
  "Incremented by the bridge. The ground truth this driver holds the registry
   against: if the registry says :fired 1 and this says 0, the registry is
   counting a fire that never happened."
  (atom 0))

(def ^:private spec-id "weaving-contract-size")

(def ^:private other-id
  "The id phase 3 installs under. Deliberately different from spec-id: see the
   phase 3 comment for why reusing it would make the phase pass vacuously."
  "weaving-contract-size-2")

(def ^:private target-internal "java/util/BitSet")
(def ^:private method-name "size")
(def ^:private descriptor "()I")

(defn- fail!
  [why code]
  (println "weavingContractDriver FAIL:" why)
  (System/exit code))

(defn- probe
  "Calls the hooked method. BitSet is in the JDK and already loaded in any
   Clojure runtime, but forName makes the ordering explicit rather than
   incidental: install! retransforms already-loaded matching classes, so a
   target that is not loaded at install time weaves nothing."
  []
  (int (.size (BitSet.))))

(defn- reset-fired! []
  (reset! fired 0))

(defn- spec
  "The id is a parameter because the phase that matters most needs two
   different ids on one class: reg/clear! leaves the class woven against a
   spec that no longer exists, and reinstalling under the SAME id would find
   that leftover advice and pass. Only a different id exposes it."
  [id]
  {:id              id
   :target-internal target-internal
   :method-name     method-name
   :descriptor      descriptor
   :position        :entry
   :action          :observe
   :bridge          (fn [_] (swap! fired inc) nil)
   :note            "weaving contract: the registry must agree with the JVM"})

(defn- assert-agrees
  "The invariant, checked identically in all three phases.

   Every assertion here is a direction the registry can lie in:
     lookup nil          -> the spec vanished while :fired still counts
     :registered? false  -> a live spec the API refuses to describe
     :woven-count 0      -> bytes were never rewritten
     :fired <> @fired    -> the counter does not match what ran"
  [phase id code]
  (let [status (reg/install-status! id)
        counted (:fired status)]
    (when-not (api/lookup id)
      (fail! (str phase ": lookup returned nil for a spec this driver installed") code))
    (when-not (:registered? status)
      (fail! (str phase ": install-status! says :registered? false while lookup returns a spec")
             (+ code 1)))
    (when-not (= 1 (:woven-count status))
      (fail! (str phase ": :woven-count " (:woven-count status) " expected 1")
             (+ code 2)))
    (when-not (pos? counted)
      (fail! (str phase ": registry :fired " counted " after the target method ran once")
             (+ code 3)))
    (when-not (= counted @fired)
      (fail! (str phase ": registry :fired " counted " but the bridge ran " @fired
                " times -- the counters disagree")
             (+ code 4)))
    ;; Exactly one probe call, exactly one fire. "at least once" would pass even
    ;; when a class has been woven twice and every call fires the bridge twice,
    ;; which is what reg/clear! invites: it leaves the class woven against a
    ;; spec that no longer exists, so the next install! retransforms bytes that
    ;; already carry advice.
    (when-not (= 1 @fired)
      (fail! (str phase ": one call to the hooked method ran the bridge " @fired
                " times, expected exactly 1 -- the class is woven more than once")
             (+ code 5)))))

(defn- install-and-probe
  "install!, call the method once, then hold the registry to all four claims."
  [phase id code]
  (api/install! (spec id))
  (reset-fired!)
  (probe)
  (assert-agrees phase id code))

(defn wcd-main
  [& _args]
  (let [_ (ByteBuddyAgent/install)]
    ((requiring-resolve 'nihilite.builder.registry.dispatch/install-redefine-dispatcher!))
    ((requiring-resolve 'nihilite.trainer.installer/install)
     (ByteBuddyAgent/install))

    ;; Phase 1 -- a fresh install must weave, and the registry must say so.
    (Class/forName "java.util.BitSet")
    (install-and-probe "phase1/fresh-install" spec-id 40)

    ;; Phase 2 -- the uninstall/reinstall cycle must leave the class back to a
    ;; state install! can weave again. A failed retransform inside uninstall!
    ;; skips mark-uninstalled! (registry.clj:610), which is the only writer of
    ;; :registered? false (registry.clj:337-342), so the status would keep
    ;; claiming :registered? true for a spec lookup no longer returns.
    (api/uninstall! spec-id)
    (when (api/lookup spec-id)
      (fail! "phase2: lookup still returns the spec after uninstall!" 61))
    (when (:registered? (reg/install-status! spec-id))
      (fail! "phase2: install-status! still says :registered? true after uninstall!" 62))
    (install-and-probe "phase2/after-uninstall-reinstall" spec-id 63)

    ;; Phase 3 -- clear! is the one the contract fixture calls, and it is the
    ;; only one that does NOT retransform (registry.clj:629-636). The class is
    ;; therefore still woven, pointing at a spec that no longer exists.
    ;;
    ;; The reinstall uses a DIFFERENT id on purpose. Reusing the same id would
    ;; find that leftover advice, resolve the same method-key, and pass -- which
    ;; is why this phase cannot be folded into phase 2. A different id is the
    ;; only shape that can tell "weaved correctly" from "weaved against a
    ;; corpse".
    (reg/clear!)
    (when-not (empty? (reg/list-ids))
      (fail! "phase3: list-ids not empty after clear!" 81))
    (install-and-probe "phase3/after-clear-reinstall-other-id" other-id 82)

    (api/uninstall! other-id)
    (println "DRIVER_PASS registry agrees with the JVM across fresh install,"
             "uninstall/reinstall and clear!/reinstall-under-a-new-id")
    (System/exit 0)))