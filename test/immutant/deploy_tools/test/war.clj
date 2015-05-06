(ns immutant.deploy-tools.test.war
  (:require [immutant.deploy-tools.war :refer :all]
            [clojure.test :refer :all]
            [leiningen.core.classpath :as cp]))

(deftest classpath-should-include-org-immutant-wildfly-for-the-given-immutant-version
  (let [base-project {:dependencies [['org.immutant/fntest "2.0.3"]]
                      :dependency-resolver (partial cp/resolve-dependencies :dependencies)
                      :dependency-hierarcher (partial cp/dependency-hierarchy :dependencies)
                      :repositories [["central" {:snapshots false, :url "https://repo1.maven.org/maven2/"}]
                                     ["clojars" {:url "https://clojars.org/repo/"}]]}]
    (doseq [dep ["core" "immutant" "messaging" "scheduling" "transactions" "web"]]
      (is (re-find #"org/immutant/wildfly/2.0.0-beta2"
            (classpath
              (update-in base-project [:dependencies] conj [(symbol "org.immutant" dep) "2.0.0-beta2"])))))
    (is (thrown-with-msg? Exception #"No org.immutant dependency found"
            (classpath base-project)))))
