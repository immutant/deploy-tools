(ns immutant.deploy-tools.test.war
  (:require [immutant.deploy-tools.war :refer :all]
            [clojure.test :refer :all]))

(deftest classpath-should-include-org-immutant-wildfly-for-the-given-immutant-version
  (let [base-deps [['org.immutant/fntest "2.0.3"]]]
    (doseq [dep ["core" "immutant" "messaging" "scheduling" "transactions" "web"]]
      (is (re-find #"org/immutant/wildfly/2.0.0-beta2"
            (classpath
              {:dependencies (conj base-deps [(symbol "org.immutant" dep) "2.0.0-beta2"])}))))
    (is (thrown-with-msg? Exception #"No org.immutant dependency found"
            (classpath {:dependencies base-deps})))))
