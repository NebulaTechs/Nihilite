(ns examples.dynamic-attach-eval
  "Attach Nihilite to a running JVM and evaluate one deterministic expression.

   Invoke this file as a script with the target process id and the Nihilite
   agent jar:

     clojure examples/dynamic_attach_eval.clj <pid> <agent-jar>

   The target must remain alive until this script finishes. The script uses
   only the repository's file-backed eval protocol and exits nonzero on any
   attach, protocol, parsing, session, or assertion failure."
  (:require [clojure.edn :as edn])
  (:import [java.io File]
           [java.util Base64]
           [com.sun.tools.attach VirtualMachine]))

(def ^:private success-marker "nihilite:example-pass dynamic-attach-eval")

(def ^:private expected-value
  {:eval-started? true
   :value "42"
   :closed? true
   :session-closed? true})

(def ^:private lifecycle-code
  (str
   (pr-str '(require 'nihilite.builder.eval))
   "\n"
   (pr-str
    '(do
       (let [sid (nihilite.builder.eval/open-session)]
         (try
           (let [eval-id (nihilite.builder.eval/eval-in sid "(+ 20 22)")]
             (loop [attempt 0]
               (let [snap (nihilite.builder.eval/snapshot sid)]
                 (cond
                   (:running? snap)
                   (if (< attempt 100)
                     (do
                       (Thread/sleep 10)
                       (recur (inc attempt)))
                     (throw
                      (ex-info "inner eval session did not become idle"
                               {:session sid :snapshot snap})))

                   (:error snap)
                   (throw
                    (ex-info "inner eval session failed"
                             {:session sid :snapshot snap}))

                   :else
                   (do
                     (when-not (string? eval-id)
                       (throw
                        (ex-info "eval-in did not return an eval id"
                                 {:session sid :eval-id eval-id})))
                     (when-not (= "42" (:value snap))
                       (throw
                        (ex-info "inner eval value was not 42"
                                 {:session sid :snapshot snap})))
                     (let [closed? (nihilite.builder.eval/close-session sid)]
                       (when-not (true? closed?)
                         (throw
                          (ex-info "close-session did not close the inner session"
                                   {:session sid :closed? closed?})))
                       (when (nihilite.builder.eval/session? sid)
                         (throw
                          (ex-info "inner session remained registered after close"
                                   {:session sid})))
                       {:eval-started? true
                        :value (:value snap)
                        :closed? closed?
                        :session-closed? (not (nihilite.builder.eval/session? sid))})))
                 )))
           (finally
             (nihilite.builder.eval/close-session sid))))))))

(defn- fail!
  "Throw a categorized failure for the top-level error handler."
  [stage message data]
  (throw (ex-info (str "dynamic attach eval failed during " stage ": " message)
                  (assoc data :nihilite/example-stage stage))))

(defn- parse-args
  "Parse and validate the target pid and agent jar arguments."
  [args]
  (when-not (= 2 (count args))
    (fail! :arguments
           "usage: dynamic_attach_eval.clj <pid> <agent-jar>"
           {:args args}))
  (let [pid-text (first args)
        jar-text (second args)
        pid (parse-long pid-text)]
    (when-not (and pid (pos? pid))
      (fail! :arguments "the target pid must be a positive integer"
             {:pid pid-text}))
    (let [jar-file (File. ^String jar-text)]
      (when-not (.isFile jar-file)
        (fail! :arguments "the agent jar does not exist or is not a file"
               {:jar jar-text}))
      {:pid pid :jar jar-text :jar-file jar-file})))

(defn- base64-code
  "Encode evaluated Clojure code as the UTF-8 Base64 payload used by the wire protocol."
  ^String [^String code]
  (.encodeToString (Base64/getEncoder) (.getBytes code "UTF-8")))

(defn- read-reply
  "Read and validate one EDN reply map written by the target."
  [^File reply-file]
  (when-not (.isFile reply-file)
    (fail! :protocol "the target wrote no reply file"
           {:reply-file (.getPath reply-file)}))
  (let [reply
        (try
          (edn/read-string (slurp reply-file))
          (catch Throwable failure
            (fail! :parse "the target reply is not valid EDN"
                   {:reply-file (.getPath reply-file)
                    :cause (.getMessage failure)})))]
    (when-not (map? reply)
      (fail! :parse "the target reply is not an EDN map"
             {:reply reply}))
    reply))

(defn- validate-reply!
  "Require the current successful file-transport reply shape."
  [reply]
  (let [expected-keys #{:token :session :ns :value :error :out :err :done}]
    (when-not (= expected-keys (set (keys reply)))
      (fail! :protocol "the target reply has an unexpected shape"
             {:reply reply})))
  (when-not (true? (:done reply))
    (fail! :protocol "the target evaluation did not finish"
           {:reply reply}))
  (when-not (nil? (:error reply))
    (fail! :session "the target evaluation reported an error"
           {:reply reply}))
  (when-not (nil? (:token reply))
    (fail! :protocol "the file transport returned an unexpected token"
           {:reply reply}))
  (when-not (string? (:session reply))
    (fail! :protocol "the target reply has no session id"
           {:reply reply}))
  (when-not (string? (:ns reply))
    (fail! :protocol "the target reply has no namespace"
           {:reply reply}))
  (when-not (string? (:value reply))
    (fail! :protocol "the target reply has no rendered value"
           {:reply reply}))
  (when-not (string? (:out reply))
    (fail! :protocol "the target reply has no output stream"
           {:reply reply}))
  (when-not (string? (:err reply))
    (fail! :protocol "the target reply has no error stream"
           {:reply reply}))
  reply)

(defn- validate-value!
  "Parse the rendered value and require the expected deterministic result."
  [reply]
  (let [value
        (try
          (edn/read-string (:value reply))
          (catch Throwable failure
            (fail! :parse "the rendered target value is not valid EDN"
                   {:value (:value reply)
                    :cause (.getMessage failure)})))]
    (when-not (map? value)
      (fail! :parse "the rendered target value is not an EDN map"
             {:value value}))
    (when-not (= expected-value value)
      (fail! :assertion "the target session lifecycle returned an unexpected value"
             {:expected expected-value
              :actual value}))
    value))

(defn- attach-and-eval!
  "Attach to the target, send one eval:file request, and return its reply."
  [^long pid ^String jar ^File reply-file]
  (let [request (str "eval:file:" (.getPath reply-file)
                     "|" (base64-code lifecycle-code))
        vm (VirtualMachine/attach (str pid))]
    (try
      (.loadAgent ^VirtualMachine vm ^String jar ^String request)
      (finally
        (.detach ^VirtualMachine vm)))
    (read-reply reply-file)))

(defn -main [& args]
  (try
    (let [{:keys [pid jar]} (parse-args args)
          reply-file (File/createTempFile "nihilite-dynamic-attach-eval" ".edn")]
      (try
        (let [reply (attach-and-eval! pid jar reply-file)]
          (validate-reply! reply)
          (validate-value! reply))
        (finally
          (.delete reply-file))))
    (println success-marker)
    (flush)
    (catch Throwable failure
      (binding [*out* *err*]
        (println "nihilite:example-fail dynamic-attach-eval:"
                 (.getMessage failure))
        (flush))
      (System/exit 1))))

(apply -main *command-line-args*)
