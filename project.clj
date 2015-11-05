(defproject org.immutant/deploy-tools "2.1.1-SNAPSHOT"
  :description "Tools for deploying Immutant applications to a container."
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :url "https://github.com/immutant/deploy-tools"
  :dependencies [[version-clj "0.1.2"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.6.0"]
                                  [leiningen-core "2.4.3"]]
                   :resource-paths ["resources" "test-resources"]}}
  :signing {:gpg-key "BFC757F9"}
  :deploy-repositories [["releases" :clojars]])
