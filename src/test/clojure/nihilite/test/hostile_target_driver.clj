(ns nihilite.test.hostile-target-driver
  "Characterisation driver: hook the methods Nihilite's own machinery runs on,
   one at a time, and record what actually happens.

   Every other driver picks targets that are known to be safe. That leaves the
   interesting question open: where is the edge? The project used to carry a
   hand-written blacklist (FileInputStream.read, ClassLoader.loadClass,
   ZipFile/Inflater, String.*, Object.equals/hashCode) plus a note that
   hooking those overflows the stack. Both were measured under an older
   dispatch path, and one of them (FileInputStream.read) turned out to be
   fine, so the list could not be trusted.

   So this driver does not encode a list of what must work. It walks a table
   of hostile targets, and for each one:

     - installs an :entry hook through the production install! path
     - hammers the method, including a re-entry from inside the bridge, which
       is the tightest version of the cycle the reentrancy guard exists for
     - uninstalls and moves on

   It asserts nothing about the targets. Several of them are documented as
   unsafe, and a driver that failed on them would fail by design; what it
   prints is the boundary the README quotes. It is not part of `check` for
   that reason, and a target that takes the JVM down ends the run -- so the
   normal way to use it is one target per JVM via HOSTILE_TARGET_INDEX, and the
   last HOSTILE_BEGIN line names whatever killed it.

   Measured on JDK 25, each target in its own JVM:

     FileOutputStream.write([BII)V      fired, nested 0
     FileInputStream.read([BII)I        directory classpath: woven 1, advice
                                        never entered (entry counter stays 0),
                                        or StackOverflowError; jar-only
                                        classpath: fires 1-2 times, no overflow
     Object.hashCode()I                 registers, woven 1, never fires
     ZipFile.getEntry(String)           registers, woven 1, never fires
     String.length()I                   StackOverflowError
     String.hashCode()I                 StackOverflowError
     Object.equals(Object)              StackOverflowError
     StringBuilder.append(String)       JVM assertion failure in libinstrument,
                                        then StackOverflowError
     ClassLoader.loadClass(String)      ClassCircularityError at install time
     Inflater.inflate([B)I              not measured: the hammer's gzip stream
                                        does not match a default Inflater

   The unsafe set is not a coincidence. A hook is unsafe exactly when the
   advice body needs the hooked method: the advice resolves a var by name
   (clojure.lang.VT/var), dispatches through hash maps, and logs, so String,
   Object identity and class loading are all on the advice's own path. The
   reentrancy guard cannot help there, because the cycle closes before the
   guard is reached."
  (:require [nihilite.builder.registry :as reg])
  (:import [net.bytebuddy.agent ByteBuddyAgent]
           [java.io File FileInputStream FileOutputStream]
           [java.util.zip Inflater ZipFile])
  (:gen-class
    :name nihilite.test.hostileTargetDriver
    :prefix "htd-"))

(def ^:private temp-file
  (doto (File/createTempFile "nihilite-hostile" ".bin")
    (.deleteOnExit)))

(defn- fail! [why code]
  (println "hostileTargetDriver FAIL:" why)
  (System/exit code))

(defn- spit-bytes! [^bytes bs]
  (with-open [out (FileOutputStream. temp-file)]
    (.write out bs))
  temp-file)

(defn- read-bytes ^bytes [^File f ^bytes ba]
  (with-open [in (FileInputStream. f)]
    (.read in ba))
  ba)

(defn- deflate ^String [^String s]
  (let [bos (java.io.ByteArrayOutputStream.)
        zos (java.util.zip.GZIPOutputStream. bos)]
    (.write zos (.getBytes s "UTF-8"))
    (.close zos)
    (.toString bos "ISO-8859-1")))

(def ^:private targets
  "target-internal / method / descriptor / arity / hammer.
   The hammer is a thunk that calls the hooked method a few times; it returns
   the last observed value only so the driver can print something.

   :check says whether HOSTILE_MODE=safe runs this target and what it then
   asserts. :fire means the bridge must actually run; :installs means only
   that installing it neither throws nor reports an empty woven count, with
   the firing left unconstrained on purpose -- Object.hashCode and
   ZipFile.getEntry register and never fire today, and asserting that would
   lock the bug in rather than describe the boundary. Targets with no :check
   are the ones that kill the JVM, plus FileInputStream.read, whose outcome
   depends on the classpath shape and so cannot be asserted either way."
  [{:label "FileInputStream.read([BII)I"
    :target-internal "java/io/FileInputStream"
    :method-name "read" :descriptor "([BII)I" :arity 3
    :hammer (fn [] (read-bytes temp-file (byte-array 8)))}

   {:label "FileOutputStream.write([BII)V"
    :target-internal "java/io/FileOutputStream"
    :method-name "write" :descriptor "([BII)V" :arity 3
    :check :fire
    :hammer (fn [] (spit-bytes! (.getBytes "nihilite" "UTF-8")))}

   {:label "String.length()I"
    :target-internal "java/lang/String"
    :method-name "length" :descriptor "()I" :arity 0
    :hammer (fn [] (count "nihilite"))}

   {:label "String.hashCode()I"
    :target-internal "java/lang/String"
    :method-name "hashCode" :descriptor "()I" :arity 0
    :hammer (fn [] (.hashCode "nihilite"))}

   {:label "StringBuilder.append(String)"
    :target-internal "java/lang/StringBuilder"
    :method-name "append" :descriptor "(Ljava/lang/String;)Ljava/lang/StringBuilder;" :arity 1
    :hammer (fn [] (.append (StringBuilder.) "nihilite"))}

   {:label "Object.equals(Object)"
    :target-internal "java/lang/Object"
    :method-name "equals" :descriptor "(Ljava/lang/Object;)Z" :arity 1
    :hammer (fn [] (.equals "a" "a"))}

   {:label "Object.hashCode()I"
    :target-internal "java/lang/Object"
    :method-name "hashCode" :descriptor "()I" :arity 0
    :check :installs
    :hammer (fn [] (.hashCode "a"))}

   {:label "ClassLoader.loadClass(String)"
    :target-internal "java/lang/ClassLoader"
    :method-name "loadClass" :descriptor "(Ljava/lang/String;)Ljava/lang/Class;" :arity 1
    :hammer (fn [] (Class/forName "java.util.BitSet"))}

   {:label "Inflater.inflate([B)I"
    :target-internal "java/util/zip/Inflater"
    :method-name "inflate" :descriptor "([B)I" :arity 1
    :hammer (fn [] (let [payload (.getBytes (deflate "nihilite payload") "UTF-8")
                          inf (Inflater.)
                          out (byte-array 128)]
                      (try
                        (.setInput inf payload)
                        (.inflate inf out)
                        (finally (.end inf)))))}

   {:label "ZipFile.getEntry(String)"
    :target-internal "java/util/zip/ZipFile"
    :method-name "getEntry" :descriptor "(Ljava/lang/String;)Ljava/util/zip/ZipEntry;" :arity 1
    :check :installs
    :hammer (fn [] (try (doto (ZipFile. temp-file)
                          (.close))
                        (catch Exception _ nil)))}])

(defn- probe-one [{:keys [label target-internal method-name descriptor arity hammer]}]
  (let [fires   (atom 0)
        nested  (atom 0)
        depth   (atom 0)
        bridge  (fn [_ctx]
                  (let [d (swap! depth inc)]
                    (try
                      (if (= d 1)
                        (do (swap! fires inc) (hammer))
                        (swap! nested inc))
                      (finally (swap! depth dec)))))
        id (str "hostile-" method-name)
        status (try
                 (reg/install! {:id              id
                                :target-internal target-internal
                                :method-name     method-name
                                :descriptor      descriptor
                                :position        :entry
                                :arity           arity
                                :action          :observe
                                :bridge          bridge
                                :note            (str "hostile-target driver: " label)})
                 (catch Throwable t
                   {:install-error (str (.getName (class t)) ": " (.getMessage t))}))
        outcome (if (:install-error status)
                  status
                  (let [st (reg/install-status! id)]
                    (merge
                      {:woven-count (:woven-count st)
                       :pending?    (:pending? st)
                       :loader      (some-> (:target-loader st) str)}
                      (try
                        (let [observed (hammer)]
                          {:fired    @fires
                           :nested   @nested
                           :observed (str observed)})
                        (catch StackOverflowError _
                          {:stack-overflow true})
                        (catch Throwable t
                          {:hammer-error (str (.getName (class t)) ": " (.getMessage t))})))))]
    (try (reg/uninstall! id) (catch Throwable _ nil))
    outcome))
(def ^:private check-targets (filterv :check targets))

(defn- env-int [k]
  (some-> (System/getenv k) str not-empty
          (as-> $ (try (Integer/parseInt $) (catch Throwable _ nil)))))

(defn- safe-mode? []
  (= "safe" (some-> (System/getenv "HOSTILE_MODE") str not-empty)))

(defn- select-targets
  "Which targets to probe: the whole table, the check-safe subset, or one
   of them.

   The index comes from HOSTILE_TARGET_INDEX, not a command-line
   argument. `clojure -T:build` does not forward positional arguments to the
   task, so the only thing that survives the trip is the environment —
   same reason HOSTILE_WARM_CALLS is an env var. HOSTILE_MODE=safe narrows
   the table to the targets tagged :check and turns the assertions on; that
   is the form `check` runs, one JVM for the whole subset. An argument is
   still honoured, for the `java -cp ... hostileTargetDriver 3` invocation
   that bypasses build.clj."
  [args]
  (let [idx (or (try (Integer/parseInt (first args)) (catch Throwable _ nil))
                (env-int "HOSTILE_TARGET_INDEX"))]
    (cond
      (nil? idx) (if (safe-mode?) check-targets targets)
      (neg? idx) (vec (reverse targets))
      :else [(nth targets idx :none)])))

(defn htd-main [& args]
  (spit-bytes! (.getBytes "nihilite" "UTF-8"))
  (let [inst (ByteBuddyAgent/install)
        Agent (Class/forName "nihilite.trainer.Agent")
        premain (.getDeclaredMethod Agent "premain"
                                    (into-array Class [String
                                                       java.lang.instrument.Instrumentation]))]
    (.setAccessible premain true)
    (.invoke premain nil (object-array [nil inst]))
    ((requiring-resolve 'nihilite.builder.registry.dispatch/install-redefine-dispatcher!))
    ;; Warm the JVM before arming any hook, so the probes run against methods
    ;; the JIT has already compiled and inlined. Nihilite's own deployment
    ;; (premain) is early and would never see a hot call site; a late install
    ;; (dynamic attach, or api/install! from a REPL) always would. Without this
    ;; the driver cannot tell the two apart.
    (when-let [n (some-> (System/getenv "HOSTILE_WARM_CALLS") Long/parseLong)]
      (let [f (spit-bytes! (.getBytes (apply str (repeat 64 "nihilite-warm ")) "UTF-8"))]
        (dotimes [_ n]
          (read-bytes f (byte-array 64)))
        (println "HOSTILE_WARMED" n "calls before arming any hook")
        (flush)))
    (let [results (mapv (fn [t]
                          ;; Printed before the probe: if the JVM dies inside
                          ;; this target, the last HOSTILE_BEGIN line names it.
                          (println "HOSTILE_BEGIN" (:label t))
                          (flush)
                          (let [r (probe-one t)]
                            (println "HOSTILE" (pr-str (assoc r :label (:label t))))
                            (flush)
                            r))
                        (select-targets args))]
      (println "HOSTILE_SUMMARY"
               (pr-str (mapv #(select-keys % [:label :fired :nested :woven-count :pending?
                                             :install-error :hammer-error :stack-overflow])
                            results)))
      (flush)
      ;; Characterisation, not a gate. Several of these targets are documented
      ;; as unsafe, and a driver that fails on them would fail forever by
      ;; design; it is kept out of `check` for that reason. What it asserts is
      ;; only that the probe itself ran: a target that kills the JVM never gets
      ;; here, and its HOSTILE_BEGIN line is the last one in the log.
      (when (= :none (:label (first results)))
        (fail! (str "no such target index; known targets: "
                    (pr-str (mapv :label targets)))
               2))
      (when (safe-mode?)
        ;; The gate `check` runs. It asserts only what is safe to assert:
        ;; that installing a hook on a bootstrap-loader class neither throws
        ;; nor reports an empty woven count, and that the one target the
        ;; project documents as working actually fires. Firing is left
        ;; unconstrained everywhere else on purpose -- two of these three
        ;; register and never fire today, and pinning that would freeze the
        ;; bug rather than describe the limit.
        (when-not (= (count check-targets) (count results))
          (fail! (str "safe mode ran " (count results) " target(s), expected "
                      (count check-targets) " -- a :check tag was added or removed")
                 3))
        (doseq [[t r] (mapv vector check-targets results)]
          (when (:install-error r)
            (fail! (str (:label t) " failed to install: " (:install-error r)) 4))
          (when-not (pos? (long (:woven-count r 0)))
            (fail! (str (:label t) " reported woven-count "
                        (pr-str (:woven-count r)) "; the class is loaded and the"
                        " hook is not armed") 5))
          (when (and (= :fire (:check t)) (not (pos? (long (:fired r 0)))))
            (fail! (str (:label t) " is documented as firing but :fired was "
                        (pr-str (:fired r))) 6)))
        (println "DRIVER_PASS hostile-target safe subset —"
                 (count check-targets) "target(s), all installed with a non-zero"
                 " woven count, and FileOutputStream.write fired"))
      (println "DRIVER_PASS hostile-target characterisation complete ("
               (count results) " target(s); see HOSTILE_SUMMARY)")
      (System/exit 0))))

(defn -main [& args]
  (htd-main args))
