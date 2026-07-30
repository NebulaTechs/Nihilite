(ns nihilite.test.transport-bencode-done-test
  "Regression: nrepl.bencode hard-requires PushbackInputStream (calls
   .unread() while parsing integer keys). Sniffer returns the bytes
   it consumed; bencode branch unreads them onto PushbackInputStream
   before handing to nrepl.server/handle. Without this unread, the
   leading `d` was already consumed and every lein/cider client hung."
  (:require [clojure.test :refer [deftest is testing]]
            [nihilite.transport.sniff :as sniff]
            [nihilite.transport.bencode :as bencode]
            [nrepl.bencode :as nrepl-bencode])
  (:import [java.io BufferedInputStream ByteArrayInputStream
                     ByteArrayOutputStream OutputStream PushbackInputStream]
           [java.net Socket]))

(defn- fake-socket
  "Stub Socket returning the supplied streams; never touches the network."
  [^BufferedInputStream in ^OutputStream out]
  (proxy [Socket] []
    (getInputStream  [] in)
    (getOutputStream [] out)
    (setSoTimeout   [ms])
    (close           [])))

(deftest sniff-returns-bencode-with-prefix-bytes
  (testing "sniff on a bencode clone stream returns [:bencode <bytes>]"
    (let [msg (.getBytes "d2:op5:clonee" "UTF-8")
          buf-in (BufferedInputStream. (ByteArrayInputStream. msg) 4096)
          result (sniff/sniff (fake-socket buf-in (ByteArrayOutputStream.)) buf-in)]
      (is (vector? result) "result is a vector")
      (is (= :bencode (first result)) "first element is :bencode")
      (is (= "d2:op5:clonee" (String. ^bytes (second result) "UTF-8"))
          "second element is the sniffed prefix bytes (whole 14-byte clone)"))))

(deftest bencode-handle-reads-first-byte-after-unread
  (testing "sniffer consumed 13 bytes; handle-bencode unreads them onto
            a PushbackInputStream so nrepl reads the leading `d` and
            the message completes (returns) instead of hanging."
    (let [msg (.getBytes "d2:op5:clonee" "UTF-8")
          consumed (byte-array 13)
          raw (ByteArrayInputStream. msg)
          buf-in (BufferedInputStream. raw 4096)
          _ (.read buf-in consumed 0 13)
          out (ByteArrayOutputStream.)
          sock (fake-socket buf-in out)
          f (future (bencode/handle-bencode sock buf-in consumed))
          deadline (+ (System/currentTimeMillis) 3000)]
      (loop []
        (when (and (not (future-done? f))
                   (< (System/currentTimeMillis) deadline))
          (Thread/sleep 50)
          (recur)))
      (is (future-done? f)
          "handle-bencode returns within 3s — proves nrepl saw `d`")
      (future-cancel f))))

(deftest bencode-decoder-walks-byte-arrays-and-keywordizes
  (testing "decode-message: byte-array values UTF-8 decoded; keys keywordized."
    (let [decode (resolve 'nihilite.transport.bencode/decode-message)
          ;; bencode: d2:op4:eval4:code7:(+ 1 2)e
          msg (nrepl-bencode/read-nrepl-message
                (PushbackInputStream.
                  (BufferedInputStream.
                    (ByteArrayInputStream.
                      (.getBytes
                        "d2:op4:eval4:code7:(+ 1 2)e"
                        "UTF-8"))
                    4096)
                  4096))]
      (is (some? decode))
      (let [decoded (decode msg)]
        (is (= "eval" (:op decoded)) "byte-array value UTF-8 decoded")
        (is (= "(+ 1 2)" (:code decoded))
            "byte-array value UTF-8 decoded, including spaces")))))