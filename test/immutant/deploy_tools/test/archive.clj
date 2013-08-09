(ns immutant.deploy-tools.test.archive
  (:use immutant.deploy-tools.archive
        clojure.test)
  (:require [clojure.java.io :as io])
  (:import java.util.jar.JarFile))


(defn contains-path? [pathifier entries path]
  (some #{(pathifier path)}
        entries))

(let [app-root (io/file (io/resource "app-root"))
      tmp-dir (io/file "/tmp")]
  (let [contains-file-path? (partial contains-path?  #(io/file app-root %))]
    (deftest test-entry-points
      (testing "with no project"
        (let [entry-points (entry-points nil (.getAbsolutePath app-root) true)]
          (are [path] (contains-file-path? entry-points path)
               "lib"
               "src"
               "resources"
               "project.clj"
               "native"
               "target/native"
               "classes"
               "target/classes")
          
          (are [path] (not (contains-file-path? entry-points path))
               "srca"
               "srcb")))
            
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
               "some-classes"
               "target/native")
          (are [path] (not (contains-file-path? entry-points path))
               "src"
               "classes"
               "target/classes")))

      (testing "with a project - :omit-source true"
        (let [entry-points (entry-points
                            {:source-paths ["srca" "srcb"]
                             :compile-path "some-classes"
                             :omit-source true}
                            (.getAbsolutePath app-root)
                            true)]
          (are [path] (contains-file-path? entry-points path)
               "lib"
               "resources"
               "project.clj"
               "some-classes"
               "target/native")
          (are [path] (not (contains-file-path? entry-points path))
               "src"
               "srca"
               "srcb"
               "classes"
               "target/classes")))))

  (defn jar-entry-seq [file]
    (with-open [jarfile (JarFile. file)]
      (mapv (memfn getName)
            (enumeration-seq (.entries jarfile)))))
  
  (deftest test-create
    (testing "the dir name should be used to name the archive with no project"
      (is (= "app-root.ima" (.getName (create nil app-root tmp-dir nil)))))

    (testing "the name from the project should be used if given"
      (is (= "the-name.ima" (.getName (create {:name "the-name"} app-root tmp-dir nil)))))

    (testing "the resulting archive should have the proper contents"
      (let [entries (jar-entry-seq (create nil app-root tmp-dir
                                           {:include-dependencies true}))]
        (are [path] (contains-path? (constantly path) entries path)
             "lib/foo.jar"
             "src/app_root/core.clj"
             "classes/FakeClass.class"))))

  (deftest test-create-with-jar-options
    (testing "the resulting archive should have the proper contents"
      (let [entries (jar-entry-seq (create {:omit-source true
                                            :jar-exclusions [#"Biscuit"]}
                                           app-root
                                           tmp-dir
                                           {:include-dependencies true}))]
        (are [path] (contains-path? (constantly path) entries path)
             "lib/foo.jar"
             "classes/FakeClass.class")

        (are [path] (not (contains-path? (constantly path) entries path))
             "src/app_root/core.clj"
             "classes/Biscuit.class")))))
