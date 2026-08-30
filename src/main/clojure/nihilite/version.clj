(ns nihilite.version)

(def ^:private hardcoded "dev-j25-1.0")

(def version
  (or (System/getProperty "nihilite.runtime.version")
      (let [pkg (Package/getPackage "nihilite.server")]
        (when pkg
          (.getImplementationVersion pkg)))
      hardcoded))
