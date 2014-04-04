(defproject org.immutant/deploy-tools "0.14.2"
  :description "Handy dandy tools for deploying and archiving Immutant applications."
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :url "https://github.com/immutant/deploy-tools"
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.4.0"]]
                   :resource-paths ["resources" "test-resources"]}}
  :signing {:gpg-key "BFC757F9"}
  :lein-release {:deploy-via :clojars})
