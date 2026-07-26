(ns nihilite.errors
  (:refer-clojure :exclude [format])
  (:require [clojure.stacktrace :as st]
            [clojure.string :as str]))

(def ^:private unwrap-classes
  #{java.lang.reflect.InvocationTargetException
    java.lang.reflect.UndeclaredThrowableException
    java.lang.ExceptionInInitializerError})

(defn- unwrap [^Throwable ex]
  (loop [current ex]
    (if (and current (contains? unwrap-classes (class current)) (.getCause current))
      (recur (.getCause current))
      current)))

(defn- clean-text [value]
  (-> (or value "")
      (str/replace #"^clojure\.lang\.Compiler\$CompilerException:\s*" "")
      (str/replace #"([A-Za-z_$][\w$]*)__\d+(\.[A-Za-z_$][\w$]*)" "$1$2")
      (str/replace #"([A-Za-z_$][\w$]*)__\d+" "$1")))

(defn- basename [file]
  (when file
    (last (str/split (str file) #"[\\/]+"))))

(defn- placeholder-file?
  "True when `file` is a JVM/Clojure source placeholder (code eval'd from
   a string, not a real .clj on disk) — these must NOT be rendered as a
   real source location."
  [file]
  (or (nil? file)
      (= file "")
      (= file "NO_SOURCE_FILE")
      (= file "NO_SOURCE_PATH")))

(defn- frame-ns [^StackTraceElement frame]
  (.getClassName frame))

(defn- hidden-frame? [^StackTraceElement frame]
  (let [name (frame-ns frame)]
    (boolean
          (or (str/starts-with? name "nihilite.")
              (str/starts-with? name "nrepl.")
          (str/starts-with? name "clojure.")
          (str/starts-with? name "sun.")
          (str/starts-with? name "java.")
          (str/starts-with? name "jdk.")))))

(defn- frame-location [^StackTraceElement frame]
  (let [file (.getFileName frame)
        line (.getLineNumber frame)]
    (if (and (not (placeholder-file? file)) (pos? line))
      (str (basename file) ":" line)
      "<no source location>")))

(defn- user-frame [^Throwable ex]
  (first (filter (fn [^StackTraceElement frame]
                  (and (not (hidden-frame? frame))
                       (not (str/starts-with? (frame-ns frame) "clojure.test$"))
                       (not (str/starts-with? (frame-ns frame) "clojure.core$"))
                       (not (str/starts-with? (frame-ns frame) "clojure.main$"))))
                (seq (.getStackTrace ex)))))

(defn- location [^Throwable ex]
  (let [data (ex-data ex)
        file (:file data)
        line (:line data)
        column (:column data)]
    (if (and (not (placeholder-file? file)) (integer? line) (pos? line))
      (if (and (integer? column) (pos? column))
        (str (basename file) ":" line ":" column)
        (str (basename file) ":" line))
      (if-let [frame (user-frame ex)]
        (frame-location frame)
        "<no source location>"))))

(defn- kind [^Throwable ex]
  (let [class-name (.getName (class ex))
        message (or (.getMessage ex) "")]
    (if (or (= class-name "clojure.lang.Compiler$CompilerException")
            (= class-name "clojure.lang.ReaderException")
            (and (instance? RuntimeException ex)
                 (str/starts-with? message "Syntax error")))
      "syntax-error"
      "runtime-error")))

(defn- compiler-internal-key?
  "True for Clojure compiler-internal ex-data keys (`:clojure.error/*`).
   These carry NO_SOURCE_PATH placeholders + compiler phase noise and
   must NOT surface in the friendly `:data` field."
  [k]
  (and (keyword? k)
       (= "clojure.error" (namespace k))))

(defn- safe-data [^Throwable ex]
  (let [raw (ex-data ex)]
    (when (map? raw)
      ;; keep onwy weaw usew ex-data — compiwew intewnaaws go night-night ~
      (let [data (into {} (remove (fn [[k _]] (compiler-internal-key? k)) raw))]
        (when (seq data)
          (try
            (if (every? (fn [[k v]] (and (or (keyword? k) (string? k) (symbol? k) (number? k) (nil? k))
                                         (or (nil? v) (string? v) (keyword? v) (symbol? v) (number? v)
                                             (boolean? v) (map? v) (vector? v) (set? v) (sequential? v)))) data)
              data
              (pr-str data))
            (catch Throwable _
              (pr-str data))))))))

(defn- cause-entry [^Throwable ex]
  {:message (clean-text (.getMessage ex))
   :location (location ex)})

(defn format ^java.util.Map [^Throwable ex]
  (let [top (unwrap ex)
        cause (some-> top .getCause unwrap)
        second-cause (when cause (cause-entry cause))
        too-deep? (boolean (some-> cause .getCause unwrap))
        second-cause (cond-> second-cause too-deep? (assoc :truncated? true))]
    {:nihilite/error true
     :kind (kind top)
     :message (clean-text (.getMessage top))
     :hint nil
     :location (location top)
     :causes (cond-> [(cause-entry top)] second-cause (conj second-cause))
     :data (safe-data top)}))
