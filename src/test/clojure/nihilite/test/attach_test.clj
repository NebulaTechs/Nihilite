(ns nihilite.test.attach-test
  "Contract test for nihilite.attach: ns loads and attach-to! throws on bad pid."
  (:require [clojure.test :refer [deftest is]]
            [nihilite.attach :as attach]))

(deftest namespace-loads-cleanly
  (is (some? (resolve 'nihilite.attach/attach-to!))
      "attach-to! resolves"))

(deftest attach-to-throws-on-invalid-pid
  (is (thrown? Throwable (attach/attach-to! "not-a-real-pid-99999999"))
      "attach-to! throws for non-existent pid"))