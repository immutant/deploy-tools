(ns immutant.deploy-tools.archive
  (:use immutant.deploy-tools.util)
  (:require [clojure.java.io :as io])
  (:import (java.io         BufferedOutputStream FileOutputStream)
           (java.util.jar   JarEntry JarOutputStream)
           (java.util.regex Pattern)))

;; Much of this is adapted from leiningen.jar so we don't
;; have to depend on lein internals, and because we generate
;; jars a bit differently

(defn ^{:internal true} trim-leading-str [s to-trim]
  (.replaceAll s (str "^" (Pattern/quote to-trim)) ""))

(defn ^{:internal true} unix-path [path]
  (.replace path "\\" "/"))

(defn exclude-file? [exclusions f]
  (some #(re-find % (.getCanonicalPath f)) exclusions))

(defprotocol CopyToJar
  (copy-to-jar [item jar opts]))

(extend-type java.io.File
  CopyToJar
  (copy-to-jar [file jar {:keys [root-path exclusions]}]
  (let [root (str (unix-path root-path) \/)]
    (doseq [child (file-seq file)]
      (let [path (reduce trim-leading-str (unix-path (str child))
                         [root "/"])]
        (when (and (.exists child)
                   (not (.isDirectory child))
                   (not (exclude-file? exclusions child)))
          (.putNextEntry jar (doto (JarEntry. path)
                               (.setTime (.lastModified child))))
          (io/copy child jar)))))))

(defrecord VirtualFile [name content])

(extend-type VirtualFile
  CopyToJar
  (copy-to-jar [vfile jar _]
    (.putNextEntry jar (doto (JarEntry. (:name vfile))
                         (.setTime (System/currentTimeMillis))))
    (io/copy (:content vfile) jar)))

(defn ^{:internal true} write-jar [root-path out-file filespecs exclusions]
  (with-open [jar (-> out-file
                      (FileOutputStream.)
                      (BufferedOutputStream.)
                      (JarOutputStream.))]
    (doseq [filespec filespecs]
      (copy-to-jar filespec jar {:root-path root-path :exclusions exclusions}))))

(defn ^{:private true} potential-entry-points
  [include-deps? include-src?]
  (remove
   nil?
   [[:resource-paths
     "resources"] 
    (when include-src?
      [:source-paths
       "src"])       
    [:native-path
     ["native"
      "target/native"]]
    (when include-deps?
      [:no-key
       "lib"])
    [:compile-path
     ["classes"     
      "target/classes"]]]))

(defn ^{:internal true} entry-points
  "Specifies the top level files to be archived, along with the dirs to be recursively archived."
  [project root-path include-deps?]
  (->> (potential-entry-points include-deps? (not (:omit-source project)))
       (map (fn [[k default]]
              (let [paths (k project)]
                (if (seq paths)
                  paths
                  default))))
       (cons "project.clj")
       flatten
       set
       (map #(if (.startsWith % root-path)
               %
               (str root-path "/" %)))
       (map io/file)))

(defn ^:internal internal-descriptor [opts]
  (let [opts (into {} (remove (comp nil? val) opts))]
    (when-not (empty? opts)
      (->VirtualFile ".immutant.clj" (pr-str opts)))))

(defn create [project root-dir dest-dir {:keys [extra-filespecs include-dependencies] :as options}]
  (let [jar-file (io/file (doto dest-dir .mkdirs)
                          (archive-name project root-dir options))
        root-path (.getAbsolutePath root-dir)
        filespecs (concat (entry-points project root-path include-dependencies)
                          extra-filespecs)
        id (internal-descriptor (select-keys options
                                  [:resolve-dependencies
                                   :resolve-plugin-dependencies
                                   :context-path
                                   :virtual-host
                                   :lein-profiles]))]
    (write-jar root-path jar-file (if id (conj filespecs id) filespecs) (:jar-exclusions project))
    jar-file))

