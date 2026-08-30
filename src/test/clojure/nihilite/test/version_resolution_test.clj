(ns nihilite.test.version-resolution-test
  (:require [clojure.test :refer [deftest is]]
            [nihilite.version :as v]))

(deftest version-is-non-empty-string
  (is (string? v/version)
      "nihilite.version/version must be a string (from property, manifest, or fallback)")
  (is (pos? (count v/version))
      "nihilite.version/version must be non-empty"))
