(ns immutant.deploy-tools.test.deploy
  (:use [immutant.deploy-tools.deploy])
  (:use [clojure.test])
  (:require [clojure.java.io :as io]
            [immutant.deploy-tools.util :as util])
  (:import [java.util.jar JarFile]))

(def ^{:dynamic true} *mock-jboss-home*)
(def ^{:dynamic true} *deployments-dir*)

(use-fixtures :each
              (fn [f]
                (binding [*mock-jboss-home* (io/file "/tmp/test-deployments")]
                  (binding [*deployments-dir* (doto (io/file *mock-jboss-home* "standalone/deployments")
                                                (.mkdirs))]
                    (doseq [file (file-seq (io/file (io/resource "app-root")))]
                      (if (.endsWith (.getName file) ".ima")
                        (io/delete-file file true)))
                    (f)
                    (doseq [file (file-seq *deployments-dir*)]
                      (io/delete-file file true))))))

(let [app-root (io/file (io/resource "app-root"))]

  (deftest test-make-descriptor
    (is (= {:root (.getAbsolutePath app-root) :lein-profiles ['a' 'b']}
           (read-string (make-descriptor app-root {:lein-profiles ['a' 'b']})))))

    (deftest test-make-descriptor-with-nil-config-values
    (is (= {:root (.getAbsolutePath app-root)}
           (read-string (make-descriptor app-root {:lein-profiles nil})))))

  (deftest test-make-descriptor-with-no-profiles
    (is (= {:root (.getAbsolutePath app-root)}
           (read-string (make-descriptor app-root nil)))))

  (deftest test-deploy-dir-with-a-project
    (let [descriptor (deploy-dir *mock-jboss-home* {:name "ham-biscuit"} app-root nil nil)
          expected-descriptor (io/file *deployments-dir* "ham-biscuit.clj")]
      (is (= "ham-biscuit.clj" (.getName descriptor)))
      (is (= expected-descriptor descriptor))
      (is (.exists expected-descriptor))
      (is (.exists (io/file *deployments-dir* "ham-biscuit.clj.dodeploy")))))

  (deftest test-deploy-dir-without-a-project
    (let [descriptor (deploy-dir *mock-jboss-home* nil app-root nil nil)
          expected-descriptor (io/file *deployments-dir* "app-root.clj")]
      (is (= "app-root.clj" (.getName descriptor)))
      (is (= expected-descriptor descriptor))
      (is (.exists expected-descriptor))
      (is (.exists (io/file *deployments-dir* "app-root.clj.dodeploy")))))

  (deftest test-deploy-dir-should-remove-failed-markers
    (let [failed-clj (util/failed-marker (io/file *deployments-dir* "app-root.clj"))
          failed-ima (util/failed-marker (io/file *deployments-dir* "app-root.ima"))]
      (spit failed-clj "")
      (spit failed-ima "")
      (deploy-dir *mock-jboss-home* nil app-root nil nil)
      (is (not (.exists failed-clj)))
      (is (not (.exists failed-ima)))))

  (deftest test-deploy-dir-with-a-name
    (let [descriptor (deploy-dir *mock-jboss-home* nil app-root {:name "ham"} nil)
          expected-descriptor (io/file *deployments-dir* "ham.clj")]
      (is (= "ham.clj" (.getName descriptor)))
      (is (= expected-descriptor descriptor))
      (is (.exists expected-descriptor))
      (is (.exists (io/file *deployments-dir* "ham.clj.dodeploy")))))

  (deftest test-deploy-dir-with-a-name-should-remove-failed-markers
    (let [failed-clj (util/failed-marker (io/file *deployments-dir* "biscuit.clj"))
          failed-ima (util/failed-marker (io/file *deployments-dir* "biscuit.ima"))]
      (spit failed-clj "")
      (spit failed-ima "")
      (deploy-dir *mock-jboss-home* nil app-root {:name "biscuit"} nil)
      (is (not (.exists failed-clj)))
      (is (not (.exists failed-ima)))))

  (deftest test-deploy-dir-with-additional-config
    (let [descriptor (deploy-dir *mock-jboss-home* {:name "ham-biscuit"} app-root nil {:foo "bar"})
          descriptor-contents (read-string (slurp descriptor))]
      (is (= "bar" (:foo descriptor-contents)))))

  (deftest test-deploy-archive-with-a-project
    (let [descriptor (deploy-archive *mock-jboss-home* {:name "ham-biscuit"} app-root app-root nil)
          expected-descriptor (io/file *deployments-dir* "ham-biscuit.ima")]
      (is (= "ham-biscuit.ima" (.getName descriptor)))
      (is (= expected-descriptor descriptor))
      (is (.exists expected-descriptor))
      (is (.exists (io/file *deployments-dir* "ham-biscuit.ima.dodeploy")))))

  (deftest test-deploy-archive-without-a-project
    (let [descriptor (deploy-archive *mock-jboss-home* nil app-root app-root nil)
          expected-descriptor (io/file *deployments-dir* "app-root.ima")]
      (is (= "app-root.ima" (.getName descriptor)))
      (is (= expected-descriptor descriptor))
      (is (.exists expected-descriptor))
      (is (.exists (io/file *deployments-dir* "app-root.ima.dodeploy")))))

  (deftest test-deploy-archive-with-a-name
    (let [descriptor (deploy-archive *mock-jboss-home* {:name "ham-biscuit"} app-root app-root {:name "gravy"})
          expected-descriptor (io/file *deployments-dir* "gravy.ima")]
      (is (= "gravy.ima" (.getName descriptor)))
      (is (= expected-descriptor descriptor))
      (is (.exists expected-descriptor))
      (is (.exists (io/file *deployments-dir* "gravy.ima.dodeploy")))))

 (deftest test-deploy-archive-should-remove-failed-markers
    (let [failed-clj (util/failed-marker (io/file *deployments-dir* "app-root.clj"))
          failed-ima (util/failed-marker (io/file *deployments-dir* "app-root.ima"))]
      (spit failed-clj "")
      (spit failed-ima "")
      (deploy-archive *mock-jboss-home* nil app-root app-root nil)
      (is (not (.exists failed-clj)))
      (is (not (.exists failed-ima)))))


  (deftest test-undeploy
    (let [deployed-file (deploy-archive *mock-jboss-home* {:name "gravy"} app-root app-root nil)
          dodeploy-marker (util/dodeploy-marker deployed-file)]
      (is (.exists deployed-file))
      (is (.exists dodeploy-marker))
      (is (= true (undeploy *mock-jboss-home* {:name "gravy"} app-root nil)))
      (is (not (.exists deployed-file)))
      (is (not (.exists dodeploy-marker)))))

  (deftest undeploy-returns-nil-if-nothing-was-done
    (is (= nil (undeploy *mock-jboss-home* {:name "gravy"} app-root nil)))))
