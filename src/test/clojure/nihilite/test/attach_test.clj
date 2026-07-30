(ns nihilite.test.attach-test
  "Contract test for nihilite.attach: ns loads, self-pid is a
   numeric string, attach-to! throws on bad pid. The child-JVM
   round-trip is exercised by scripts/smoke-attach.sh."
  (:require [clojure.test :refer [deftest is]]
            [nihilite.attach :as attach]))

(deftest namespace-loads-cleanly
  (is (some? (resolve 'nihilite.attach/attach-to!))
      "attach-to! resolves")
  (is (some? (resolve 'nihilite.attach/self-pid))
      "self-pid resolves"))

(deftest self-pid-is-numeric-string
  (let [pid (attach/self-pid)]
    (is (string? pid) "self-pid returns a string")
    (is (pos? (count pid)) "self-pid is non-empty")
    (is (re-matches #"\d+" pid) "self-pid matches digits only")))

(deftest attach-to-throws-on-invalid-pid
  (is (thrown? Throwable (attach/attach-to! "not-a-real-pid-99999999"))
      "attach-to! throws for non-existent pid"))