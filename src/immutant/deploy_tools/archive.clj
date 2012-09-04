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

(defn ^{:internal true} copy-to-jar [root-path jar file]
  (let [root (str (unix-path root-path) \/)]
    (doseq [child (file-seq (io/file file))]
      (let [path (reduce trim-leading-str (unix-path (str child))
                         [root "/"])]
        (when (and (.exists child)
                   (not (.isDirectory child)))
          (.putNextEntry jar (doto (JarEntry. path)
                               (.setTime (.lastModified child))))
          (io/copy child jar))))))

(defn ^{:internal true} write-jar [root-path out-file filespecs]
  (with-open [jar (-> out-file
                      (FileOutputStream.)
                      (BufferedOutputStream.)
                      (JarOutputStream.))]
    (doseq [filespec filespecs]
      (copy-to-jar root-path jar filespec))))

(defn ^{:private true} potential-entry-points
  [include-deps?]
  (remove nil?
   [[[:resources-path    ;; lein1
      :resource-paths]   ;; lein2
     "resources"] 
    [[:source-path       ;; lein1
      :source-paths]     ;; lein2
     "src"]       
    [[:native-path]      ;; lein2
     "target/native"]
    (when include-deps?
      [[:library-path]     ;; lein1
       "lib"])
    [[:compile-path]     ;; lein1 & 2
     ["classes"          ;; lein1 default
      "target/classes"]] ;; lein2 default
    ]))

(defn ^{:internal true} entry-points
  "Specifies the top level files to be archived, along with the dirs to be recursively archived."
  [project root-path include-deps?]
  (map #(if (.startsWith % root-path)
          %
          (str root-path "/" %))
       (flatten
        (conj
         (map (fn [[keys default]]
                (let [paths (remove nil? (map #(% project) keys))]
                  (if (seq paths)
                    paths
                    default)))
              (potential-entry-points include-deps?))
         "project.clj"
         "immutant.clj"))))

(defn create [project root-dir dest-dir include-deps? copy-deps-fn]
  (let [jar-file (io/file dest-dir (archive-name project root-dir))
        root-path (.getAbsolutePath root-dir)]
    (and include-deps? copy-deps-fn (copy-deps-fn project))
    (write-jar root-path jar-file (entry-points project root-path include-deps?))
    jar-file))

