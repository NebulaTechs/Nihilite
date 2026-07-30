(ns nihilite.test.readline-loop-eof-test
  "Regression: when a raw-branch client closes the socket mid-readline,
   jline3 surfaces IOException from the underlying stream rather than
   EOF. Without the catch, the exception bubbles to
   nihilite.transport.raw/handle-raw, which logs it as a connection
   error and leaves the operator's log file cluttered with noise
   from every client that types C-c on a hung eval or just ^D-quits.

   read-balanced-form now treats IOException as :eof so run-loop
   exits cleanly and raw.clj's bye/close path runs."
  (:require [clojure.test :refer [deftest is testing]]
            [nihilite.readline.loop :as loop]))

(deftest read-balanced-form-is-public
  (testing "read-balanced-form is resolvable from tests."
    (is (some? (resolve 'nihilite.readline.loop/read-balanced-form)))))

(deftest eval-with-cancel-returns-disconnected-marker-on-ioexception
  (testing "eval-with-cancel catches IOException from the terminal
            reader and returns the disconnected marker instead of
            bubbling the exception to raw.clj's catch."
    (let [eval-with-cancel (resolve 'nihilite.readline.loop/eval-with-cancel)
          ;; Fake terminal whose reader raises IOException on read.
          fake-reader (proxy [org.jline.utils.NonBlockingReader] []
                        (read  ([] (throw (java.io.IOException. "peer closed")))
                               ([_ms] (throw (java.io.IOException. "peer closed")))))
          fake-terminal (proxy [org.jline.terminal.Terminal] []
                          (reader [] fake-reader))]
      (is (some? eval-with-cancel))
      (let [out (eval-with-cancel fake-terminal "(+ 1 1)" (atom {:ns (find-ns 'user)}))]
        (is (string? out) "returns a string, not an exception")
        (is (.contains ^String out "disconnected")
            "marker tells the operator the peer closed mid-eval")))))