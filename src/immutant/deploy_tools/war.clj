(ns immutant.deploy-tools.war
  (:require [leiningen.core.classpath :as cp]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.io BufferedOutputStream FileOutputStream]
           java.util.Properties
           java.util.zip.ZipOutputStream
           [java.util.jar JarEntry JarOutputStream]))

(defn abort [& msg]
  ;; TODO: map?
  (throw (ex-info (apply str msg) {})))

(defn build-init [options options]
  (pr-str
    `(do
       (require 'immutant.wildfly)
       (immutant.wildfly/init-deployment (quote ~(:init-fn options))
         ~(if (-> options :nrepl :start?)
            {:nrepl (merge {:host "localhost" :port 0}
                      (:nrepl options))}
            {})))))

(defn extract-keys
  ([m]
     (extract-keys [] m))
  ([acc m]
     (if (map? m)
       (concat (keys m) (mapcat (fn [[_ v]] (extract-keys acc v)) m)))))

(defn extract-deps [options]
  (->> options
    (cp/dependency-hierarchy :dependencies)
    extract-keys))

(defn locate-version [options ns]
  (if-let [version (some (fn [[dep version]]
                           (if (= ns (namespace dep))
                             version))
                     (extract-deps options))]
    version
    (abort (format "No %s dependency found in the options' dependency tree." ns))))

(defn classpath [options]
  (let [classpath (:classpath options)]
    (str/join ":"
      (if (some #(re-find #"/wildfly.*?\.jar" %) classpath)
        classpath
        (reduce
          (fn [accum entry]
            (let [path (.getAbsolutePath entry)]
              (if (some #{path} accum)
                accum
                (conj (vec accum) path))))
          classpath
          (cp/resolve-dependencies :dependencies
               (assoc options :dependencies
                      [['org.immutant/wildfly (locate-version options "org.immutant")
                        :exclusions ['org.projectodd.wunderboss/wunderboss-wildfly
                                     'org.clojure/clojure]]])))))))

(defn build-descriptor [options]
  (cond-> {:language "clojure"
           :init (build-init options options)}
    (:dev? options)   (merge
                        {:root (:root options)
                         :classpath (classpath options)})
    (-> options
      :nrepl :start?) (merge
                        {"config.repl-options"
                         (pr-str (-> options :nrepl :options))})))

(defn map->properties [m]
  (reduce (fn [p [k v]]
            (doto p
              (.setProperty (name k) v)))
    (Properties.)
    m))

(defn build-war
  "Creates a war file with the given entry specs.

   specs is a vector of 2-element vectors of the form [\"path/in/war\"
   content-as-anything-that-can-be-io/copied]"
  [file specs]
  (with-open [out (-> file FileOutputStream. BufferedOutputStream. JarOutputStream.)]
    (doseq [[path content] specs]
      (.putNextEntry out (JarEntry. path))
      (io/copy content out))))

(defn resolve-path [project path]
  (if path
    (let [deployments-dir (io/file path "standalone/deployments")]
      (when-not (.exists path)
        (abort (format "Path '%s' does not exist." path)))
      (when-not (.isDirectory path)
        (abort (format "Path '%s' is not a directory." path)))
      (if (.exists deployments-dir)
        deployments-dir
        path))
    (io/file (:target-path project))))

(defn add-app-properties
  "Adds the generated wunderboss app.properties to the entry specs."
  [specs options]
  (assoc specs
    "META-INF/app.properties"
    (with-out-str
      (-> options
        build-descriptor
        map->properties
        (.store *out* "")))))

(defn add-file-spec [specs path file]
  (let [path (format "%s%s%s"
               path
               (if (empty? path) "" "/")
               (.getName file))]
    (assoc specs
      (if (.startsWith path "/")
        (.substring path 1)
        path)
      file)))

(defn find-required-wboss-dependencies [options]
  (let [deps (mapv first (extract-deps options))
        immutant-deps (filter #(= "org.immutant" (namespace %)) deps)
        match #(some #{'org.immutant/immutant %} immutant-deps)]
    (cond-> ['org.projectodd.wunderboss/wunderboss-wildfly]
      (match 'org.immutant/caching)   (conj 'org.projectodd.wunderboss/wunderboss-caching)
      (match 'org.immutant/messaging) (conj 'org.projectodd.wunderboss/wunderboss-messaging)
      (match 'org.immutant/web)       (conj 'org.projectodd.wunderboss/wunderboss-web))))

(defn wboss-jars-for-dev [options]
  (let [wboss-version (locate-version options "org.projectodd.wunderboss")]
    (->> (assoc options
           :dependencies (mapv (fn [dep]
                                 [dep wboss-version])
                           (find-required-wboss-dependencies options)))
      (cp/resolve-dependencies :dependencies))))

(defn all-wildfly-jars [options]
  (->> (assoc options
         :dependencies [['org.immutant/wildfly (locate-version options "org.immutant")
                         :exclusions
                         ['org.immutant/core
                          'org.clojure/clojure
                          'org.projectodd.wunderboss/wunderboss-clojure]]])
    (cp/resolve-dependencies :dependencies)))

(defn add-top-level-jars
  "Adds any additional (other than the uberjar) top-level jars to the war.

   These will be just enough jars to bootstrap the app in the container,
   and vary for devwars and uberwars."
  [specs options]
  (reduce #(add-file-spec %1 "WEB-INF/lib" %2)
    specs
    (if (:dev? options)
      (wboss-jars-for-dev options)
      (all-wildfly-jars options))))

(defn add-resource-dir [specs resource-dir]
  (let [dir (io/file resource-dir)]
    (if (.exists dir)
      (reduce
        (fn [m file]
          (if (.isFile file)
            (add-file-spec m
              (.substring (.getParent (.getAbsoluteFile file))
                (.length (.getAbsolutePath dir)))
              file)
            m))
        specs
        (file-seq dir))
      (abort (format "resource path '%s' does not exist."
               (.getPath dir))))))

(defn add-top-level-resources
  "Adds the tree of :resource-paths to the top-level of the war."
  [specs options]
  (reduce
    add-resource-dir
    specs
    (:war-resource-paths options)))

(defn find-base-xml [specs file-name]
  (if-let [jar-key (some #(re-find #"^.*wunderboss-wildfly.*\.jar$" %) (keys specs))]
    (let [cl (doto (clojure.lang.DynamicClassLoader.)
               (.addURL (.toURL (specs jar-key))))
          old-cl (-> (Thread/currentThread) .getContextClassLoader)]
      (try
        (-> (Thread/currentThread) (.setContextClassLoader cl))
        (if-let [resource (io/resource (str "base-xml/" file-name))]
          (slurp resource)
          (abort (format "No %s found in the wunderboss-wildfly jar." file-name)))
        (finally
          (-> (Thread/currentThread) (.setContextClassLoader old-cl)))))
    (abort "No wunderboss-wildfly jar found in the dependency tree.")))

(defn add-base-xml
  "Adds a WEB-INF/file-name to the entry specs unless it already exists.

   The file is pulled from the wunderboss-wildfly jar.
   If it does add the file to the specs, it also drops a copy in
   target/ in case the user needs to customize it."
  [specs options file-name]
  (let [spec-key (str "WEB-INF/" file-name)]
    (if (specs spec-key)
      specs
      (let [content (find-base-xml specs file-name)]
        (when (:target-path options)
          (spit (io/file (:target-path options) file-name) content))
        (assoc specs spec-key content)))))

(defn add-uberjar
  [specs options]
  (if (:dev? options)
    specs
    (add-file-spec specs "WEB-INF/lib"
      (io/file (:uberjar options)))))

(defn create-war
  "Generates a war file suitable for deploying to a WildFly container.

   `dest-path` should be the full path to the war file.

    Required options are:

    * :init-fn - the fully-qualified init function
    * :dependencies - a vector of depenencies for the app, in lein/aether form

    Truly optional options:

    * :dev? - generate a \"dev\" war
    * :target-path - the target path for the app, used to store web.xml
      and jboss-deployment-structure.xml for user customization.
    * :nrepl
      * :port
      * :host
      * :start?
      * :port-file (absolute)
      * :options

   Required options when :dev? is true:

   * :classpath - a sequence of classpath elements

   Required options when :dev? is false:

   * :uberjar - the absolute path to the app's uberjar

   :repositories, etc. (look at classpath fns)"
  [dest-path options]
  (let [file (io/file dest-path)]
    (build-war file
      (-> {}
        (add-uberjar options)
        (add-app-properties options)
        (add-top-level-jars options)
        (add-top-level-resources options)
        (add-base-xml options "web.xml")
        (add-base-xml options "jboss-deployment-structure.xml")))
    file))
