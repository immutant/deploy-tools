(ns immutant.deploy-tools.war
  (:require [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.io BufferedOutputStream FileOutputStream]
           java.util.Properties
           java.util.zip.ZipOutputStream
           [java.util.jar JarEntry JarOutputStream]))

(defn abort [& msg]
  (throw (RuntimeException. (apply str msg))))

(defn warn [external-f & msg]
  (if external-f
    (apply external-f msg)
    (binding [*out* *err*]
      (apply println "Warning:" msg))))

(defn build-init [{:keys [init-fn nrepl]}]
  (pr-str
    `(do
       (require 'immutant.wildfly)
       (immutant.wildfly/init-deployment (quote ~init-fn)
         ~(if (:start? nrepl)
            {:nrepl (merge {:host "localhost" :port 0}
                      (dissoc nrepl :options))}
            {})))))

(defn extract-keys
  ([m]
     (extract-keys [] m))
  ([acc m]
     (if (map? m)
       (concat (keys m) (mapcat (fn [[_ v]] (extract-keys acc v)) m)))))

(def extract-deps
  (memoize
    (fn [{:keys [dependency-hierarcher] :as options}]
      (->> options
        dependency-hierarcher
        extract-keys))))

(defn classpath [{:keys [classpath dependency-resolver repositories immutant-version]
                  :as options}]
  (if (some #(re-find #"/org/immutant/wildfly/.*?/wildfly.*?\.jar" %) classpath)
    classpath
    (reduce
      (fn [accum entry]
        (let [path (.getAbsolutePath entry)]
          (if (some #{path} accum)
            accum
            (conj (vec accum) path))))
      classpath
      (dependency-resolver
        {:dependencies [['org.immutant/wildfly immutant-version
                         :exclusions ['org.projectodd.wunderboss/wunderboss-wildfly
                                      'org.clojure/clojure]]]
         :repositories repositories}))))

(defn segregate-classpath [{:keys [dev?] :as options}]
  (-> (if dev?
        (->> (classpath options)
          (group-by #(if (.endsWith % ".jar")
                       :classpath-jars
                       :classpath))
          (merge options))
        options)
    (update-in [:classpath] (partial str/join ":"))
    (update-in [:classpath-jars] (partial map io/file))))

(defn build-descriptor [{:keys [root classpath] :as options}]
  (cond-> {:language "clojure"
           :init (build-init options)}
    (:dev? options)   (merge
                        {:root root
                         :classpath classpath})
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

(defn find-required-wboss-dependencies [{:keys [wunderboss-version] :as options}]
  (let [deps (mapv first (extract-deps options))
        immutant-deps (filter #(= "org.immutant" (namespace %)) deps)
        match #(some #{'org.immutant/immutant %} immutant-deps)]
    (map (fn [dep] [dep wunderboss-version])
      (cond-> ['org.projectodd.wunderboss/wunderboss-wildfly-core
               'org.projectodd.wunderboss/wunderboss-wildfly-singletons]
        (match 'org.immutant/caching)      (conj 'org.projectodd.wunderboss/wunderboss-caching
                                             'org.projectodd.wunderboss/wunderboss-wildfly-caching)
        (match 'org.immutant/messaging)    (conj 'org.projectodd.wunderboss/wunderboss-messaging
                                             'org.projectodd.wunderboss/wunderboss-wildfly-messaging)
        (match 'org.immutant/transactions) (conj 'org.projectodd.wunderboss/wunderboss-transactions
                                             'org.projectodd.wunderboss/wunderboss-wildfly-transactions)
        (match 'org.immutant/web)          (conj 'org.projectodd.wunderboss/wunderboss-web)))))

(defn all-wildfly-jars [{:keys [dependency-resolver dev? immutant-version] :as options}]
  (dependency-resolver
    (assoc options
      :dependencies (mapv 
                      #(conj %
                        :exclusions
                        ['org.immutant/core
                         'org.clojure/clojure
                         'org.projectodd.wunderboss/wunderboss-clojure])
                      (concat [['org.immutant/wildfly immutant-version]]
                        (find-required-wboss-dependencies options))))))

(defn add-top-level-jars
  "Adds any additional (other than the uberjar) top-level jars to the war.

   These will be just enough jars to bootstrap the app in the container,
   and vary for devwars and uberwars."
  [specs {:keys [dev? classpath-jars] :as options}]
  (let [wf-jars (all-wildfly-jars options)]
    (reduce #(add-file-spec %1 "WEB-INF/lib" %2)
      specs
      (if dev?
        (concat wf-jars classpath-jars)
        wf-jars))))

(defn ensure-forward-slashes [path]
  (str/join "/" (str/split path #"[\\/]")))

(defn add-resource-dir [specs resource-dir]
  (let [dir (io/file resource-dir)]
    (if (.exists dir)
      (reduce
        (fn [m file]
          (if (.isFile file)
            (add-file-spec m
              (ensure-forward-slashes
                (.substring (.getParent (.getAbsoluteFile file))
                  (.length (.getAbsolutePath dir))))
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

(defn generate-jboss-web-xml [context-root virtual-host]
  (format "<jboss-web>\n%s%s</jboss-web>\n"
    (if context-root
      (format "<context-root>%s</context-root>\n" context-root)
      "")
    (if virtual-host
      (format "<virtual-host>%s</virtual-host>\n" virtual-host)
      "")))

(defn add-jboss-web-xml [specs {:keys [context-path virtual-host target-path warn-fn]}]
  (if (or (specs "WEB-INF/jboss-web.xml") (not (or context-path virtual-host)))
    (do
      (when (or context-path virtual-host)
        (warn warn-fn ":context-path or :virtual-host specified, but a WEB-INF/jboss-web.xml"
          "exists in [:immutant :war :resource-paths]. Ignoring options."))
      specs)
    (let [content (generate-jboss-web-xml context-path virtual-host)]
      (when target-path
        (spit (io/file target-path "jboss-web.xml") content))
      (assoc specs "WEB-INF/jboss-web.xml" content))))

(defn with-jar-on-classpath [jar-file f]
  (with-open [cl (doto (clojure.lang.DynamicClassLoader.)
                   (.addURL (.toURL jar-file)))]
    (let [thread (Thread/currentThread)
          old-cl (.getContextClassLoader thread)]
      (try
        (.setContextClassLoader thread cl)
        (f)
        (finally
          (.setContextClassLoader thread old-cl))))))

(defn locate-version [sym deps]
  (if-let [version (some (fn [[dep version]]
                           (when (= sym dep) version))
                     deps)]
    version
    (abort (format "No %s dependency found in the dependency tree." sym))))

(defn insert-versions [options]
  (let [deps (extract-deps options)]
    (assoc options
      :immutant-version (locate-version 'org.immutant/core deps)
      :wunderboss-version (locate-version
                            'org.projectodd.wunderboss/wunderboss-core deps))))

(defn find-base-xml [specs file-name]
  (if-let [jar-key (some #(re-find #"^.*wunderboss-wildfly-core-\d.*\.jar$" %) (keys specs))]
    (with-jar-on-classpath (specs jar-key)
      #(if-let [resource (io/resource (str "base-xml/" file-name))]
         (slurp resource)
         (abort (format "No %s found in the wunderboss-wildfly-core jar." file-name))))
    (abort "No wunderboss-wildfly-core jar found in the dependency tree.")))

(defn add-base-xml
  "Adds a WEB-INF/file-name to the entry specs unless it already exists.

   The file is pulled from the wunderboss-wildfly-core jar.  It also drops a
  copy of the original in target/ in case the user needs to customize it."
  [specs options file-name]
  (let [spec-key (str "WEB-INF/" file-name)
        content (find-base-xml specs file-name)]
    (when (:target-path options)
      (spit (io/file (:target-path options) file-name) content))
    (if (specs spec-key)
      specs
      (assoc specs spec-key content))))

(defn add-uberjar
  [specs options]
  (if (:dev? options)
    specs
    (add-file-spec specs "WEB-INF/lib"
      (io/file (:uberjar options)))))

(defn resolve-target-path [path]
  (when path
    (let [dir (io/file path)
          deployments-dir (io/file dir "standalone/deployments")]
      (when-not (.exists dir)
        (abort (format "Path '%s' does not exist." path)))
      (when-not (.isDirectory dir)
        (abort (format "Path '%s' is not a directory." path)))
      (if (.exists deployments-dir)
        deployments-dir
        path))))

(defn create-war
  "Generates a war file suitable for deploying to a WildFly container.

   `dest-path` should be the full path to the war file.

    Required options are:

    * :init-fn - the fully-qualified init function
    * :dependencies - a vector of depenencies for the app, in lein/aether form
    * :dependency-resolver - (fn [deps repos] ...) - a fn that resolves
      dependencies. deps is a standard pomegranate dependency vector, repos
      is a standard pomegranate

    Truly optional options:

    * :dev? - generate a \"dev\" war
    * :target-path - the target path for the app, used to store web.xml
      and jboss-deployment-structure.xml for user customization.
    * :context-path
    * :virtual-host - a seq of host names
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
  (try
    (let [file (io/file dest-path)
          options' (-> options
                     insert-versions
                     segregate-classpath)]
      (build-war file
        (-> {}
          (add-uberjar options')
          (add-app-properties options')
          (add-top-level-jars options')
          (add-top-level-resources options')
          (add-base-xml options' "web.xml")
          (add-base-xml options' "jboss-deployment-structure.xml")
          (add-jboss-web-xml options')))
      file)
    (catch Exception e
      (.printStackTrace e))))
