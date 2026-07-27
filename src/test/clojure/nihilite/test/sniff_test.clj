(ns nihilite.test.sniff-test
  "Unit tests for protocol sniffing & WebSocket helper logic in nihilite.transport."
  (:require [clojure.test :refer [deftest is testing]]
            [nihilite.transport :as t]))

(deftest test-protocol-sniffers
  (testing "bencode-prefix? matching"
    (let [bencode-fn @#'nihilite.transport.sniff/bencode-prefix?
          to-bytes (fn [^String s] (.getBytes s "UTF-8"))]
      (is (true? (bencode-fn (to-bytes "d4:code") 7)))
      (is (true? (bencode-fn (to-bytes "d123:foo") 8)))
      (is (false? (bencode-fn (to-bytes "de") 2)))
      (is (false? (bencode-fn (to-bytes "GET / HTTP/1.1") 14)))
      (is (false? (bencode-fn (to-bytes "d") 1)))))

  (testing "http-method-prefix? matching"
    (let [http-fn @#'nihilite.transport.sniff/http-method-prefix?
          to-bytes (fn [^String s] (.getBytes s "UTF-8"))]
      (is (true? (http-fn (to-bytes "GET /healthz HTTP/1.1") 16)))
      (is (true? (http-fn (to-bytes "POST /v1/eval HTTP/1.1") 17)))
      (is (true? (http-fn (to-bytes "OPTIONS * HTTP/1.1") 18)))
      (is (true? (http-fn (to-bytes "PRI * HTTP/2.0\r\n") 16)))
      (is (false? (http-fn (to-bytes "d4:code") 7)))
      (is (false? (http-fn (to-bytes "HELLO") 5)))))

  (testing "WebSocket Sec-WebSocket-Accept key calculation"
    (let [accept-fn @#'nihilite.transport.ws/ws-accept-key]
      ;; RFC 6455 Section 1.3 test vector
      (is (= "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
             (accept-fn "dGhlIHNhbXBsZSBub25jZQ=="))))))
