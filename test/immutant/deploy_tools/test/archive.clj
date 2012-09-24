(ns immutant.deploy-tools.test.archive
  (:use [immutant.deploy-tools.archive])
  (:use [clojure.test])
  (:require [clojure.java.io :as io])
  (:import [java.util.jar JarFile]))


(defn contains-path? [pathifier entries path]
  (some #{(pathifier path)}
        entries))

(let [app-root (io/file (io/resource "app-root"))
      tmp-dir (io/file "/tmp")]
  (let [contains-file-path? (partial contains-path? #(.getAbsolutePath (io/file app-root %)))]
    (deftest test-entry-points
      (testing "with no project"
        (let [entry-points (entry-points nil (.getAbsolutePath app-root) true)]
          (are [path] (contains-file-path? entry-points path)
               "lib"
               "src"
               "resources"
               "project.clj"
               "immutant.clj"
               "classes"
               "target/classes")))

      (testing "with a project"
        (let [entry-points (entry-points
                            {:source-paths ["srca" "srcb"]
                             :compile-path "some-classes"}
                            (.getAbsolutePath app-root)
                            true)]
          (are [path] (contains-file-path? entry-points path)
               "lib"
               "srca"
               "srcb"
               "resources"
               "project.clj"
               "immutant.clj"
               "some-classes"
               "target/native")
          (are [path] (not (contains-file-path? entry-points path))
               "src"
               "classes"
               "target/classes")))))

  (deftest test-create
    (testing "the dir name should be used to name the archive with no project"
      (is (= "app-root.ima" (.getName (create nil app-root tmp-dir nil)))))

    (testing "the name from the project should be used if given"
      (is (= "the-name.ima" (.getName (create {:name "the-name"} app-root tmp-dir nil)))))

    (testing "the resulting archive should have the proper contents"
      (let [entries (map (memfn getName)
                         (enumeration-seq (.entries (JarFile. (create nil app-root tmp-dir
                                                                      {:include-dependencies true})))))]
        (are [path] (contains-path? (constantly path) entries path)
             "immutant.clj"
             "lib/foo.jar"
             "src/app_root/core.clj"
             "classes/FakeClass.class")))
    (let [called (atom nil)
          copy-deps-fn (fn [_] (reset! called true))]
      (testing "the copy-deps-fn should be called when include-deps? is true"
        (reset! called false)
        (create nil app-root tmp-dir {:include-dependencies true, :copy-deps-fn copy-deps-fn})
        (is (= true @called)))
      (testing "the copy-deps-fn should not be called when include-deps? is false"
        (reset! called false)
        (create nil app-root tmp-dir {:include-dependencies false, :copy-deps-fn copy-deps-fn})
        (is (= false @called))))))
