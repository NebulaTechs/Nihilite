(ns nihilite.test.init-service-driver
  "Probe for one question: can a user's init script, running inside a JVM
   that Nihilite was attached to, deploy a network service that a client
   outside the JVM can reach?

   This is the load-bearing assumption behind removing the embedded nREPL
   server. If init can start a plain socket service and addURL a jar onto
   Compiler/LOADER, then a user who wants an interactive control plane
   brings their own, and `java -jar nihilite.jar` does not have to open a
   port on its own. If it cannot, the idea is dead and the server stays.

   The probe answers four things, in order, and prints one line each:

     PROBE loader-class       what Compiler/LOADER actually is
     PROBE is-url-classloader  whether init can addURL onto it
     PROBE host-ns            whether init can require a namespace that is
                               only on the host application's classpath
     PROBE service-port       the port the init-started service bound
     PROBE service-echo       bytes round-tripped from this process

   Everything runs in a real JVM through `java -jar`, because that is the only
   path where the init form is evaluated at all. A first run of this probe
   under `-javaagent:` produced no init output whatsoever: agent-premain arms
   the transformer and starts the worker, and nothing evaluates
   `-Dnihilite.init`. boot/-main does that, and only Main-Class reaches it.
   So `-Dnihilite.init` is currently dead on the -javaagent path, which is
   the primary deployment mode.

   The init script is written to a temp file and loaded via
   `(load-file \"...\")` so the -Dnihilite.init property stays short and no
   nested escaping is needed.

   Not part of `check`: it spawns a JVM and binds a port, and its purpose
   is to answer a design question rather than to gate a change."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.net Socket InetSocketAddress]))

(def ^:private probe-ns-name "probe.hostapp.ns")

(defn- host-app-source
  "Source for a namespace that exists ONLY on the added path. If init can
   require this after addURL, init can reach code Nihilite knows nothing
   about, which is what lets a user ship their own server without putting
   anything on Nihilite's classpath."
  []
  (str "(ns " probe-ns-name "\n"
       "  (:require [nihilite.api :as api]))\n\n"
       "(def marker :host-app-marker)\n\n"
       "(defn install-a-hook! []\n"
       "  (api/install!\n"
       "   {:id \"probe-hook\"\n"
       "    :target-internal \"java/io/FileOutputStream\"\n"
       "    :method-name \"write\"\n"
       "    :descriptor \"([BII)V\"\n"
       "    :position :entry\n"
       "    :arity 3\n"
       "    :action :observe\n"
       "    :note \"init service probe\"\n"
       "    :bridge (fn [_] nil)}))\n"))

(defn- probe-init-source
  "Reads the probe's init script off disk rather than building it as a string.

   It lives at src/test/clojure/probe/init_service.clj so it is ordinary
   readable Clojure under the linter, instead of an escaped string literal
   with four levels of quoting that nothing can check. The host directory
   reaches it as a system property rather than being interpolated in."
  []
  (let [f (io/file "src/test/clojure/probe/init_service.clj")]
    (when-not (.exists f)
      (throw (ex-info "probe init script not found" {:path (str f)})))
    (slurp f)))

(defn- write-file! [^File dir ^String name ^String content]
  (let [f (File. dir name)]
    (spit f content)
    f))

(defn- probe-echo
  "Connects to the probe's echo service, sends one byte and returns what
   comes back. Returns nil if the connection or read fails."
  [port]
  (try
    (with-open [sock (Socket.)]
      (.connect sock (InetSocketAddress. "127.0.0.1" port) 5000)
      (let [out (.getOutputStream sock)
            in  (.getInputStream sock)]
        (.write out (int (unchecked-byte 0x41)))
        (.flush out)
        ;; read one byte back; the echo service increments by one
        (let [b (.read in)]
          (when (>= b 0) b))))
    (catch Exception _ nil)))

(defn -main [& args]
  (let [nihilite-jar (or (first args) "target/nihilite.jar")
        jar-file      (File. ^String nihilite-jar)
        _             (when-not (.exists jar-file)
                         (binding [*out* *err*]
                           (println "PROBE_FAIL jar not found:" nihilite-jar))
                         (System/exit 2))
        host-dir      (doto (File/createTempFile "nihilite-probe-host" "")
                        (.delete)
                        (.mkdirs))
        host-ns-dir   (doto (apply io/file host-dir
                                    (butlast (str/split probe-ns-name #"\.")))
                        (.mkdirs))
        _             (write-file! host-ns-dir "ns.clj" (host-app-source))
        init-file     (write-file! host-dir "init.clj" (probe-init-source))
        init-form     (str "(load-file \"" (.getPath init-file) "\")")
        ;; -jar, not -javaagent: boot/-main is what evaluates the init form, and
        ;; it only runs on the Main-Class path. This is itself a finding -- see
        ;; the ns docstring.
        cmd           ["java"
                       (str "-Dnihilite.init=" init-form)
                       (str "-Dnihilite.probe.hostdir=" (.getPath host-dir))
                       "-jar" (.getPath jar-file)]
        pb            (ProcessBuilder. ^java.util.List cmd)
        _             (.redirectErrorStream pb true)
        proc          (.start pb)
        rdr           (io/reader (.getInputStream proc))
        port          (atom nil)
        collected     (atom [])
        host-ns-ok?   (atom false)
        deadline      (+ (System/nanoTime)
                         (.toNanos java.util.concurrent.TimeUnit/SECONDS 45))]
    (println "PROBE cmd" (pr-str cmd))
    (flush)
    (doto (Thread.
           ^Runnable
           (fn []
             (try
               (loop [line (.readLine rdr)]
                 (when line
                   (swap! collected conj line)
                   (println line)
                   (flush)
                   (when-let [[_ p] (re-find #"PROBE service-port (\d+)" line)]
                     (reset! port (Integer/parseInt p)))
                  (when (.startsWith line "PROBE host-ns ")
                    (reset! host-ns-ok? (.startsWith line "PROBE host-ns ok")))
                   (recur (.readLine rdr))))
               (catch Exception _ nil)))
           "init-service-probe-reader")
      (.setDaemon true)
      (.start))
    (while (and (nil? @port) (< (System/nanoTime) deadline) (.isAlive proc))
      (Thread/sleep 200))
    (let [echo (when @port
                 ;; let the accept loop reach accept()
                 (Thread/sleep 300)
                 (probe-echo @port))]
      (.destroyForcibly proc)
      (try (.waitFor proc 5 java.util.concurrent.TimeUnit/SECONDS)
           (catch InterruptedException _ (.interrupt (Thread/currentThread))))
      (println "PROBE service-echo"
               (if echo (format "0x%02x" echo) "nil"))
      (flush)
      (if echo
        (do
          (println "PROBE_SUMMARY echo round trip ok -- init deployed a network"
                   "service that a client in this process reached over loopback")
          (when-not host-ns-ok?
            (println "PROBE_SUMMARY WARNING addURL did not make the added"
                     "directory visible to require; a user cannot ship their own"
                     "code this way"))
          (System/exit 0))
        (do
          (println "PROBE_FAIL init did not deploy a reachable network service;"
                   "port=" @port)
          (when-let [[_ tail] (re-find #"(?s)(PROBE.*)" (str/join "\n" @collected))]
            (println tail))
          (System/exit 1))))))
