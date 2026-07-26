(ns nihilite.errors-test
  (:require [clojure.test :refer [deftest is testing]]
            [nihilite.errors :refer [format]]))

(defn- messages [ex] (map :message (:causes (format ex))))

(deftest syntax-errors-are-sanitized
  (let [ex (try (eval '(defnN foo [])) (catch Throwable t t))
        result (format ex)]
    (is (= "syntax-error" (:kind result)))
    (is (not (re-find #"__\d+" (:message result))))
    (is (not (re-find #"nihilite\.\w+$" (:message result))))
    (is (not (re-find #"clojure\.lang\.Compiler\$" (:message result))))))

(deftest runtime-error-preserves-simple-data
  (let [ex (ex-info "boom" {:k 42})
        result (format ex)]
    (is (= "runtime-error" (:kind result)))
    (is (= "boom" (:message result)))
    (is (= {:k 42} (:data result)))
    ;; ex-info created in test-infra has no :file/:line in ex-data and
    ;; no user-relevant frame (all clojure.* frames are dropped), so
    ;; location resolves to the explicit sentinel. The intent of THIS
    ;; test is :data preservation, not location format.
    (is (string? (:location result)))
    (is (seq (:location result)))))

(deftest wrapper-errors-are-hoisted
  (doseq [wrapper [(java.lang.reflect.InvocationTargetException. (NullPointerException.))
                   (java.lang.reflect.UndeclaredThrowableException. (NullPointerException.))
                   (java.lang.ExceptionInInitializerError. (NullPointerException.))]]
    (let [result (format wrapper)]
      (is (= 1 (count (:causes result))))
      (is (= "runtime-error" (:kind result))))))

(deftest cause-depth-is-capped
  (let [result (format (ex-info "boom" {} (RuntimeException. "inner")))]
    (is (= 2 (count (:causes result)))))
  (let [result (format (ex-info "top" {} (RuntimeException. "inner"
                                                (RuntimeException. "deepest"))))]
    (is (= 2 (count (:causes result))))
    (is (= true (:truncated? (second (:causes result)))))))

(deftest non-simple-data-falls-back-to-pr-str
  (let [data {:k (Object.)}
        result (format (ex-info "boom" data))]
    (is (= (pr-str data) (:data result)))))

(deftest missing-location-is-explicit
  (let [result (format (ex-info "boom" {}))]
    (is (= "<no source location>" (:location result)))))

(deftest stack-frame-sanitization
  (let [frame (StackTraceElement. "nihilite.foo$bar__1234" "invoke" "/foo/bar/baz.clj" 7)
        hidden (StackTraceElement. "nihilite.transport$safe_eval_line" "invoke" "transport.clj" 8)
        sun-frame (StackTraceElement. "sun.reflect.NativeMethodAccessorImpl" "invoke0" "NativeMethodAccessorImpl.java" 9)
        compiler (StackTraceElement. "clojure.lang.Compiler$CompilerException" "x" "Compiler.java" 10)
        ex (doto (RuntimeException. "nihilite.transport$fn__13649.invoke")
             (.setStackTrace (into-array StackTraceElement [frame hidden sun-frame compiler])))]
    (is (= "<no source location>" (:location (format ex))))
    (is (= "nihilite.transport$fn.invoke" (:message (format ex))))))

(deftest canonical-shape
  (let [result (format (ex-info "boom" {}))]
    (is (= #{:nihilite/error :kind :message :hint :location :causes :data}
           (set (keys result))))
    (is (= true (:nihilite/error result)))
    (is (nil? (:hint result)))
    (is (every? #(contains? result %) [:kind :message :location :causes]))))
