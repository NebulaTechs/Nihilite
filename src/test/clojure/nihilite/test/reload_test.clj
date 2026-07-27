(ns nihilite.test.reload-test
  "Unit tests for nihilite.reload: module parsing, topo sort, cycle detection."
  (:require [clojure.test :refer [deftest is testing]]
            [nihilite.reload :as reload]))

(deftest test-module-parsing-and-topo-sort
  (testing "Header parsing logic via private functions"
    (let [parse-fn @#'nihilite.reload/parse-module-header
          temp-file (java.io.File/createTempFile "test_mod" ".clj")]
      (try
        (spit temp-file ";; nihilite-module: foo_mod\n;; nihilite-requires: bar_mod baz_mod\n(ns foo-mod)")
        (let [res (parse-fn temp-file)]
          (is (= "foo_mod" (:module res)))
          (is (= ["bar_mod" "baz_mod"] (:requires res))))
        (finally
          (.delete temp-file)))))

  (testing "Topological sort ordering"
    (let [topo-fn @#'nihilite.reload/topo-sort
          modules [[(symbol "c") {:module "c" :requires ["b"]} nil]
                   [(symbol "b") {:module "b" :requires ["a"]} nil]
                   [(symbol "a") {:module "a" :requires []} nil]]
          sorted (topo-fn modules)]
      (is (= [(symbol "a") (symbol "b") (symbol "c")] sorted))))

  (testing "Cycle detection throws ExceptionInfo"
    (let [topo-fn @#'nihilite.reload/topo-sort
          modules [[(symbol "a") {:module "a" :requires ["b"]} nil]
                   [(symbol "b") {:module "b" :requires ["a"]} nil]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"module cycle detected"
            (topo-fn modules))))))
