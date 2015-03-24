(defproject org.immutant/deploy-tools "2.0.4"
  :description "Tools for deploying Immutant applications to a container."
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :url "https://github.com/immutant/deploy-tools"
  :dependencies [[leiningen-core "2.4.3"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.6.0"]]
                   :resource-paths ["resources" "test-resources"]}}
  :signing {:gpg-key "BFC757F9"}
  :lein-release {:deploy-via :clojars})
