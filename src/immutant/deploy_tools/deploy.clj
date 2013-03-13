(ns immutant.deploy-tools.deploy
  (:use immutant.deploy-tools.util)
  (:require [clojure.java.io               :as io]
            [immutant.deploy-tools.archive :as archive]))

(defn- rm-deployment-files [project root-dir options file-funs]
  (let [files (filter #(.exists %)
                      (mapcat #(map % [(deployment-file (descriptor-name project root-dir options))
                                       (deployment-file (archive-name project root-dir options))])
                                file-funs))]
      (when-not (empty? files)
        (doseq [file files]
          (io/delete-file file))
        true)))

(defn make-descriptor [root-dir additional-config]
  (prn-str (assoc (into {} (filter (fn [[_ v]] (not (nil? v))) additional-config))
             :root (.getAbsolutePath (io/file root-dir)))))

(defn deploy-archive [jboss-home project root-dir dest-dir options]
  (with-jboss-home jboss-home
    (rm-deployment-files project root-dir options [failed-marker])
    (let [archive-name (archive-name project root-dir options)
          archive-file (io/file dest-dir archive-name)
          deployed-file (deployment-file archive-name)]
      (archive/create project root-dir dest-dir options)
      (io/copy archive-file deployed-file)
      (spit (dodeploy-marker deployed-file) "")
      deployed-file)))

(defn deploy-dir [jboss-home project path options additional-config]
  (with-jboss-home jboss-home
    (rm-deployment-files project path options [failed-marker])
    (let [deployed-file (deployment-file (descriptor-name project path options))]
      (spit deployed-file (make-descriptor (:root project path) additional-config))
      (spit (dodeploy-marker deployed-file) "")
      deployed-file)))

(defn undeploy
  [jboss-home project root-dir options]
  (with-jboss-home jboss-home
    (rm-deployment-files project root-dir options
                         [identity
                          dodeploy-marker
                          deployed-marker
                          failed-marker])))
