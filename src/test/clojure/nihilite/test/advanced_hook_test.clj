(ns nihilite.test.advanced-hook-test
  "Documents the :advanced-hook? spec flag. At present this is a metadata
   marker — registry accepts the spec unconditionally and the AgentBuilder's
   ignore matcher silently filters out ignored targets at weave time.
   The actual bypass (a separate AgentBuilder without the ignore) is future
   work; until then, :advanced-hook? on a spec does not change runtime
   behaviour, only signals user intent."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.test.fixtures :as fx]))

(defn- spec-with-target [target]
  {:id (str "ah-" target)
   :target-internal target
   :method-name "hashCode"
   :descriptor "()I"
   :position :entry
   :action :observe
   :bridge (fn [_ctx] nil)})

(use-fixtures :each fx/reg-cleanup)

(deftest registry-accepts-ignored-target-without-flag
  (let [spec (spec-with-target "java/util/HashMap")]
    (is (true? (reg/install! spec)))
    (is (some? (reg/lookup "ah-java/util/HashMap")))))

(deftest registry-accepts-ignored-target-with-flag
  (let [spec (assoc (spec-with-target "clojure/lang/Var")
                    :advanced-hook? true)]
    (is (true? (reg/install! spec)))
    (is (some? (reg/lookup "ah-clojure/lang/Var")))))

(deftest non-ignored-target-is-accepted
  (let [spec (spec-with-target "com/example/Foo")]
    (is (true? (reg/install! spec)))
    (is (some? (reg/lookup "ah-com/example/Foo")))))
