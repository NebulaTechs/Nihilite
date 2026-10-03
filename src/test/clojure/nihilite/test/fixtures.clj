(ns nihilite.test.fixtures
  "Registry cleanup fixture shared by test namespaces."
  (:require [nihilite.registry :as reg]))

(defn reg-cleanup
  [f]
  (reg/clear!)
  (try (f) (finally (reg/clear!))))

(defn with-redefine-dispatcher
  "Fixture for tests that install a :redefine spec without a real agent.

   install! waits for the redefine dispatcher before weaving a :redefine hook,
   because the advice's only route to its bridge goes through it. These tests
   assert on registry data rather than on a woven method, so they install the
   dispatcher directly and the wait passes immediately."
  [f]
  ((requiring-resolve 'nihilite.registry.dispatch/install-redefine-dispatcher!))
  (f))

(defn reg-cleanup-with-dispatcher
  "reg-cleanup plus the dispatcher. Use this instead of stacking two
   use-fixtures calls: the second call replaces the fixture list rather than
   adding to it, so the cleanup would be lost."
  [f]
  (with-redefine-dispatcher #(reg-cleanup f)))
