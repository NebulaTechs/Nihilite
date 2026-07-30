(ns nihilite.transport.bencode
  "Native nREPL bencode branch. Bridges the sniffed connection into
   `nrepl.server/handle`. Unreads the sniffed bytes onto a
   PushbackInputStream so nrepl sees the leading `d`."
  (:require [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [nrepl.bencode :as bencode]
            [nrepl.server :as nrserver]
            [nrepl.transport :as nrtransport])
  (:import [java.net Socket]
           [java.io BufferedInputStream OutputStream PushbackInputStream]))

(defn- decode-message
  "Walk a parsed nrepl message: keys keywordized; byte-array values
   UTF-8 decoded EXCEPT for any key listed under `-unencoded`."
  [msg]
  (let [unencoded (get msg "-unencoded")
        drop-keys (cond-> ["-unencoded"]
                    (seq unencoded) (into unencoded))
        without   (apply dissoc msg drop-keys)
        decoded   (reduce-kv
                    (fn [m k v]
                      (assoc m k (if (bytes? v)
                                   (String. ^bytes v "UTF-8")
                                   v)))
                    (empty without)
                    without)]
    (walk/keywordize-keys
      (merge decoded
             (when (seq unencoded)
               (select-keys msg unencoded))))))

(defn handle-bencode
  "Wrap buf-in in PushbackInputStream, unread the sniffed prefix,
   then hand the stream to nrepl.server/handle."
  [^Socket sock ^BufferedInputStream buf-in ^bytes sniffed-prefix]
  (.setSoTimeout sock 0)
  (let [pb-in (PushbackInputStream. buf-in (alength sniffed-prefix))
        ^OutputStream out (.getOutputStream sock)]
    (try
      (.unread pb-in sniffed-prefix 0 (alength sniffed-prefix))
      (let [transport (nrtransport/fn-transport
                        (fn [] (decode-message (bencode/read-nrepl-message pb-in)))
                        (fn [resp]
                          (locking out
                            (bencode/write-bencode out resp)
                            (.flush out)))
                        (fn [] (try (.close sock) (catch Throwable _))))]
        (nrserver/handle (nrserver/default-handler) transport))
      (catch Throwable t
        (log/error t "bencode connection error"))
      (finally
        (try (.close sock) (catch Throwable _))))))
