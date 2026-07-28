(ns nihilite.test.observers-selector
  "Selector DSL tests. Glob/regex disambiguation per D1.4: a bare
   string is a glob (`*` -> `.*`, `?` -> `.`); a
   `java.util.regex.Pattern` is used as-is. `:tag` is exact
   match. Empty selector `{}` matches all."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.observers.selector :as sel]))

(defn- setup [f]
  (reg/clear!)
  (try (f) (finally (reg/clear!))))

(use-fixtures :each
  (fn [t] (setup t)))

(deftest exact-class-match
  (testing "bare string without wildcard = exact match"
    (let [s {:class "net/minecraft/server/MinecraftServer"}]
      (is (sel/matches? s {:class "net/minecraft/server/MinecraftServer"}))
      (is (not (sel/matches? s {:class "net/minecraft/server/MinecraftServer$Thread"}))))))

(deftest glob-star-wildcard
  (testing "* matches any number of chars"
    (is (sel/matches? {:class "net/*"} {:class "net/minecraft/server/MinecraftServer"}))
    (is (sel/matches? {:class "net/minecraft/*"} {:class "net/minecraft/server/MinecraftServer"}))
    (is (not (sel/matches? {:class "com/*"} {:class "net/minecraft/server/MinecraftServer"})))))

(deftest glob-question-wildcard
  (testing "? matches a single char"
    (is (sel/matches? {:method "get?"} {:method "getX"}))
    (is (not (sel/matches? {:method "get?"} {:method "getXY"})))))

(deftest explicit-pattern-override
  (testing "java.util.regex.Pattern is used as-is"
    (let [p (java.util.regex.Pattern/compile "^net/.*server$"
                                             java.util.regex.Pattern/CASE_INSENSITIVE)]
      (is (sel/matches? {:class p} {:class "net/minecraft/server/MinecraftServer"}))
      (is (not (sel/matches? {:class p} {:class "net/minecraft/client/Minecraft"}))))))

(deftest arity-exact-match
  (testing ":arity is exact integer match"
    (is (sel/matches? {:arity 2} {:arity 2}))
    (is (not (sel/matches? {:arity 2} {:arity 1})))
    (is (not (sel/matches? {:arity 2} {:arity "2"})))))

(deftest arity-nil-means-any
  (testing "absent :arity means no constraint"
    (is (sel/matches? {} {:arity 0}))
    (is (sel/matches? {} {:arity 5}))))

(deftest tag-exact-match
  (testing ":tag is exact match"
    (is (sel/matches? {:tag "tick-loop"} {:tag "tick-loop"}))
    (is (not (sel/matches? {:tag "tick"} {:tag "tick-loop"})))))

(deftest empty-selector-matches-all
  (testing "{} matches every spec"
    (is (sel/matches? {} {:class "anywhere" :method "anything" :arity 99}))
    (is (sel/matches? {} {}))))

(deftest select-targets-filters-by-tag
  (testing "select-targets filters specs by selector"
    (reg/install! {:id "a" :target-internal "x" :method-name "m"
                   :descriptor "()V" :position :entry :arity 0
                   :bridge (fn [_]) :tag "fast"})
    (reg/install! {:id "b" :target-internal "y" :method-name "m"
                   :descriptor "()V" :position :entry :arity 0
                   :bridge (fn [_]) :tag "slow"})
    (let [fast-ids (set (map :id (sel/select-targets {:tag "fast"})))
          all-ids  (set (map :id (sel/select-targets {})))]
      (is (= #{"a"} fast-ids))
      (is (= #{"a" "b"} all-ids)))))
