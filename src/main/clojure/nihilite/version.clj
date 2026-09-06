(ns nihilite.version
  (:import [java.text SimpleDateFormat]
           [java.util Date]))

(defonce ^:private timestamp
  (let [fmt (SimpleDateFormat. "yyyyMMdd-HHmmss")]
    (.format fmt (Date.))))

(def version
  (or (System/getProperty "nihilite.runtime.version")
      (let [pkg (Package/getPackage "nihilite.server")]
        (when pkg
          (.getImplementationVersion pkg)))
      (str "dev-" timestamp)))