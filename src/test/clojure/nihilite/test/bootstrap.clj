(ns nihilite.test.bootstrap
  "Bootstrap contract test (Wave 6 Task 1).

   Smoke for the test runner itself. Asserts `clojure.test`-shape
   primitives and the `nihilite.test.runner/TEST_NAMESPACES` registry.

   This file is intentionally minimal: its primary job is to prove
   the harness wiring without requiring the production runtime
   namespaces to load. Future contract tests live alongside this one."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]))

(deftest test-vars-shape
  (is (map? (ns-interns *ns*))
      "ns-interns returns a map")
  (is (map? (ns-publics *ns*))
      "ns-publics returns a map"))

(deftest runner-namespace-list-is-non-empty-and-well-shaped
  (let [nss nihilite.test.runner/TEST_NAMESPACES]
    (is (sequential? nss) "TEST_NAMESPACES is a seq")
    (is (every? string? nss) "every TEST_NAMESPACES entry is a string")
    (is (every? #(or (str/starts-with? % "nihilite.test.")
                     (= % "nihilite.errors-test")) nss)
        "every TEST_NAMESPACES entry lives under nihilite.test.*")))

(deftest arithmetic-baseline
  (is (= 7 (+ 3 4)) "3 + 4 = 7")
  (is (= 12 (* 3 4)) "3 × 4 = 12"))
