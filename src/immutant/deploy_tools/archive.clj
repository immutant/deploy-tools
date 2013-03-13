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

(defprotocol CopyToJar
  (copy-to-jar [item jar & opts]))

(extend-type java.io.File
  CopyToJar
  (copy-to-jar [file jar & {:keys [root-path]}]
  (let [root (str (unix-path root-path) \/)]
    (doseq [child (file-seq file)]
      (let [path (reduce trim-leading-str (unix-path (str child))
                         [root "/"])]
        (when (and (.exists child)
                   (not (.isDirectory child)))
          (.putNextEntry jar (doto (JarEntry. path)
                               (.setTime (.lastModified child))))
          (io/copy child jar)))))))

(defrecord VirtualFile [name content])

(extend-type VirtualFile
  CopyToJar
  (copy-to-jar [vfile jar & _]
    (.putNextEntry jar (doto (JarEntry. (:name vfile))
                         (.setTime (System/currentTimeMillis))))
    (io/copy (:content vfile) jar)))

(defn ^{:internal true} write-jar [root-path out-file filespecs]
  (with-open [jar (-> out-file
                      (FileOutputStream.)
                      (BufferedOutputStream.)
                      (JarOutputStream.))]
    (doseq [filespec filespecs]
      (copy-to-jar filespec jar :root-path root-path))))

(defn ^{:private true} potential-entry-points
  [include-deps?]
  (remove
   nil?
   [[:resource-paths
     "resources"] 
    [:source-paths
     "src"]       
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
  (->> (potential-entry-points include-deps?)
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
      (VirtualFile. ".immutant.clj" (pr-str opts)))))

(defn create [project root-dir dest-dir options]
  (let [jar-file (io/file dest-dir (archive-name project root-dir options))
        root-path (.getAbsolutePath root-dir)
        include-deps? (:include-dependencies options)
        copy-deps-fn (:copy-deps-fn options)
        filespecs (entry-points project root-path include-deps?)
        id (internal-descriptor (dissoc options
                                        :include-dependencies
                                        :copy-deps-fn
                                        :name))]
    (and include-deps? copy-deps-fn (copy-deps-fn project))
    (write-jar root-path jar-file (if id (conj filespecs id) filespecs))
    jar-file))

