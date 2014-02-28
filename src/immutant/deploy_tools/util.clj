(ns immutant.deploy-tools.util
  (:require [clojure.java.io :as io]))

(defn get-application-root [args]
  (io/file (or (first (filter #(not (.startsWith % "--")) args))
               (System/getProperty "user.dir"))))

(def ^{:dynamic true} *jboss-home*)

(defmacro with-jboss-home [jboss-home & forms]
  `(if ~jboss-home
    (if (.isDirectory ~jboss-home)
      (binding [*jboss-home* ~jboss-home]
        (do ~@forms))
      (abort (.getAbsolutePath ~jboss-home) "does not exist."))
    (abort "Could not locate jboss home. Set $JBOSS_HOME or $IMMUTANT_HOME.")))

(defn err [& message]
  (binding [*out* *err*]
    (apply println message)))

;; borrowed from leiningen.core
(defn abort
  "Print msg to standard err and exit with a value of 1."
  [& msg]
  (binding [*out* *err*]
    (apply println msg)
    (shutdown-agents)
    (System/exit 1)))

(defn app-name [project root-dir]
  (:name project (and root-dir (.getName root-dir))))

(defn descriptor-name [project root-dir options]
  (str (or (:name options) (app-name project root-dir)) ".clj") )

(defn archive-name [project root-dir options]
  (let [n (or (:name options) (app-name project root-dir))]
    (if (:version options)
      (str n "-" (:version project) ".ima")
      (str n ".ima"))))

(defn deployment-dir
  ([]
     (deployment-dir *jboss-home*))
  ([jboss-home]
   (io/file jboss-home "standalone" "deployments")))

(defn deployment-file [deployment-name]
  (io/file (deployment-dir) deployment-name))

(defn marker [suffix deployment-file]
  (io/file (str (.getAbsolutePath deployment-file) suffix)))

(def dodeploy-marker
  (partial marker ".dodeploy"))

(def deployed-marker
  (partial marker ".deployed"))

(def failed-marker
  (partial marker ".failed"))

(defn application-is-deployed? [project root-dir opts]
  (or (.exists (deployment-file (descriptor-name project root-dir opts)))
      (.exists (deployment-file (archive-name project root-dir opts)))))

(defn- find-zip-entry-bytes [zs path]
  (when-let [e (.getNextEntry zs)]
    (if (= path (.getName e))
      (let [size (.getSize e)
            bytes (byte-array size)]
        (.read zs bytes 0 size)
        bytes)
      (recur zs path))))

(defn- find-properties [zs path]
  (when-let [bytes (find-zip-entry-bytes zs path)]
    (doto (java.util.Properties.)
      (.load (java.io.StringReader. (String. bytes))))))

(defn current-immutant-build-properties [jboss-home]
  (let [jar-file (io/file jboss-home
                          "modules/org/immutant/core/main/immutant-core-module.jar")]
    (when (.exists jar-file)
      (with-open [zs (java.util.zip.ZipInputStream.
                      (io/input-stream jar-file))]
        (find-properties zs "org/immutant/immutant.properties")))))
