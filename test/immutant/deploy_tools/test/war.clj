(ns immutant.deploy-tools.test.war
  (:require [immutant.deploy-tools.war :refer :all]
            [clojure.test :refer :all]
            [leiningen.core.classpath :as cp]))

(defn classpath-chain [project]
  (-> project insert-versions classpath))

(deftest classpath-should-include-org-immutant-wildfly-for-the-given-immutant-version
  (let [immutant-version "2.1.3"
        base-project {:dependencies [['org.immutant/fntest "2.0.3"]]
                      :dependency-resolver (partial cp/resolve-dependencies :dependencies)
                      :dependency-hierarcher (partial cp/dependency-hierarchy :dependencies)
                      :repositories [["central" {:snapshots false, :url "https://repo1.maven.org/maven2/"}]
                                     ["clojars" {:url "https://clojars.org/repo/"}]
                                     ["Immutant incremental builds"
                                      {:url "http://downloads.immutant.org/incremental/"}]]}]
    (doseq [dep ["core" "immutant" "messaging" "scheduling" "transactions" "web"]]
      (is (some (partial re-find (re-pattern (str "org/immutant/wildfly/" immutant-version)))
            (-> base-project
              (update-in [:dependencies] conj [(symbol "org.immutant" dep) immutant-version])
              classpath-chain))))
    (is (thrown-with-msg? Exception #"No org.immutant/core dependency found"
            (classpath-chain base-project)))))
