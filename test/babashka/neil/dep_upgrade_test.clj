(ns babashka.neil.dep-upgrade-test
  (:require
   [babashka.neil.test-util :as test-util]
   [clojure.test :as t :refer [deftest is testing]]
   [clojure.edn :as edn]
   [clojure.set :as set]))

(def test-file-path (str (test-util/test-file "deps.edn")))

(defn ->edn []
  (-> test-file-path slurp edn/read-string))

(defn get-dep-version [dep-name]
  (-> (->edn) :deps (get dep-name)))

(defn get-alias-versions
  "Returns a set of the dep versions for aliases matching the passed `dep-name`."
  [dep-name]
  (-> (->edn) :aliases
      (->> (map (comp #(get % dep-name) :extra-deps second))
           (into #{}))))

(deftest dep-upgrade-test
  (testing "a fresh project is up-to-date"
    (spit test-file-path "{}")
    (test-util/neil "dep add :lib clj-kondo/clj-kondo" :deps-file test-file-path)
    (let [clj-kondo-version-original (get-dep-version 'clj-kondo/clj-kondo)]
      (test-util/neil "dep upgrade" :deps-file test-file-path)
      (is (= clj-kondo-version-original (get-dep-version 'clj-kondo/clj-kondo)))))

  (testing "an old dependency can be upgraded, and --dry-run is respected"
    (spit test-file-path "{}")
    (test-util/neil "dep add :lib clj-kondo/clj-kondo :version 2022.01.01" :deps-file test-file-path)
    (let [clj-kondo-version-original (get-dep-version 'clj-kondo/clj-kondo)]

      ;; should be the same version after --dry-run
      (test-util/neil "dep upgrade" :deps-file test-file-path :dry-run true)
      (is (= clj-kondo-version-original (get-dep-version 'clj-kondo/clj-kondo)))

      ;; after a non-dry-run, the version should be changed
      (test-util/neil "dep upgrade" :deps-file test-file-path)
      (is (not (= clj-kondo-version-original (get-dep-version 'clj-kondo/clj-kondo)))))))

(deftest dep-upgrade-test-one-lib
  (testing "specifying :lib only updates one dep"
    (spit test-file-path "{}")
    (test-util/neil "dep add :lib clj-kondo/clj-kondo :version 2022.01.01" :deps-file test-file-path)
    (test-util/neil "dep add :lib babashka/fs :version 0.0.1" :deps-file test-file-path)
    (let [clj-kondo-original (get-dep-version 'clj-kondo/clj-kondo)
          fs-original        (get-dep-version 'babashka/fs)]
      (test-util/neil "dep upgrade :lib clj-kondo/clj-kondo" :deps-file test-file-path)
      (let [clj-kondo-upgraded (get-dep-version 'clj-kondo/clj-kondo)
            fs-upgraded        (get-dep-version 'babashka/fs)]
        (is (not (= clj-kondo-original clj-kondo-upgraded)))
        (is (= fs-original fs-upgraded))))))

(deftest dep-upgrade-test-using-git-tags
  (testing "deps can be added with --latest-tag"
    (spit test-file-path "{}")
    (test-util/neil "dep add :lib clj-kondo/clj-kondo :latest-tag" :deps-file test-file-path)
    (let [original (get-dep-version 'clj-kondo/clj-kondo)]
      (is (:git/tag original))
      (is (:git/sha original))
      (is (:git/url original))))

  (testing "deps can be added with --tag"
    (spit test-file-path "{}")
    (test-util/neil "dep add :lib clj-kondo/clj-kondo :tag \"v2022.03.08\"" :deps-file test-file-path)
    (let [original (get-dep-version 'clj-kondo/clj-kondo)]
      (is (= "v2022.03.08" (:git/tag original)))
      (is (:git/sha original))
      (is (:git/url original))))

  (testing "deps with :git/tag coords upgrade to latest tags"
    (spit test-file-path "{}")
    (test-util/neil "dep add :lib clj-kondo/clj-kondo :tag \"v2022.03.08\"" :deps-file test-file-path)
    (let [original (get-dep-version 'clj-kondo/clj-kondo)]
      (is (= "v2022.03.08" (:git/tag original)))
      (test-util/neil "dep upgrade" :deps-file test-file-path)
      (let [upgraded (get-dep-version 'clj-kondo/clj-kondo)]
        (is (= (:git/url original) (:git/url upgraded)))
        (is (:git/tag upgraded))
        (is (not= (:git/tag original) (:git/tag upgraded)))
        (is (:git/sha upgraded)))))

  (testing "deps with :tag coords are also supported"
    (spit test-file-path "{:deps {clj-kondo/clj-kondo {:tag \"v2022.03.08\" :sha \"247e538\"}}}")
    (let [original (get-dep-version 'clj-kondo/clj-kondo)]
      (is (= "v2022.03.08" (:tag original)))
      (test-util/neil "dep upgrade" :deps-file test-file-path)
      (let [upgraded (get-dep-version 'clj-kondo/clj-kondo)]
        (is (:git/tag upgraded))
        (is (not= (:tag original) (:git/tag upgraded)))
        (is (:git/sha upgraded))))))

(deftest dep-upgrade-test-maintain-dep-source
  (testing "upgrading a :git/sha dep should maintain :git/sha"
    (spit test-file-path "{}")
    (test-util/neil "dep add :lib clj-kondo/clj-kondo :latest-sha true" :deps-file test-file-path)
    (let [clj-kondo-original (get-dep-version 'clj-kondo/clj-kondo)]
      ;; this upgrade should return the same latest sha, NOT a :mvn/version
      (test-util/neil "dep upgrade" :deps-file test-file-path)
      (let [{:keys [git/url git/sha]} (get-dep-version 'clj-kondo/clj-kondo)]
        (is url)
        (is sha)
        (is (= clj-kondo-original (get-dep-version 'clj-kondo/clj-kondo))))))

  (testing "upgrading an older :git/sha dep should set the latest :git/sha"
    (spit test-file-path "{}")
    (test-util/neil "dep add :lib clj-kondo/clj-kondo :sha 6ffc3934cb83d2c4fff16d84198c73b40cd8a078"
                    :deps-file test-file-path)
    (let [clj-kondo-original (get-dep-version 'clj-kondo/clj-kondo)]
      ;; here we upgrade and then assert that the sha is different, but still :git/sha based
      (test-util/neil "dep upgrade" :deps-file test-file-path)
      (let [{:keys [git/url git/sha]} (get-dep-version 'clj-kondo/clj-kondo)]
        (is url)
        (is sha)
        ;; should be a different sha
        (is (not (= sha (:git/sha clj-kondo-original)))))))

  (testing "upgrading a coordinate with just :sha (not :git/sha) should still work"
    (spit test-file-path "{:deps {clj-kondo/clj-kondo
                            {:git/url \"https://github.com/clj-kondo/clj-kondo\"
                             :sha \"6ffc3934cb83d2c4fff16d84198c73b40cd8a078\"}}}")
    (let [original (get-dep-version 'clj-kondo/clj-kondo)]
      ;; here we upgrade and then assert that the sha is different,
      ;; and on :git/sha rather than :sha
      (is (:sha original))
      (test-util/neil "dep upgrade" :deps-file test-file-path)
      (let [upgraded (get-dep-version 'clj-kondo/clj-kondo)]
        (is (:git/sha upgraded))
        (is (not (:sha upgraded)))
        ;; should be a different sha
        (is (not (= (:git/sha upgraded) (:sha original)))))))

  (testing "upgrading a single lib should also maintain :git/url and sha"
    (spit test-file-path "{}")
    (test-util/neil "dep add :lib clj-kondo/clj-kondo :sha 6ffc3934cb83d2c4fff16d84198c73b40cd8a078"
                    :deps-file test-file-path)
    (test-util/neil "dep add :lib babashka/fs :sha 791009052fe8916b4e10e55732622a69250c7598"
                    :deps-file test-file-path)

    (let [clj-kondo-original (get-dep-version 'clj-kondo/clj-kondo)
          fs-original        (get-dep-version 'babashka/fs)]
      (test-util/neil "dep upgrade :lib babashka/fs" :deps-file test-file-path)
      (let [clj-kondo-upgraded (get-dep-version 'clj-kondo/clj-kondo)
            fs-upgraded        (get-dep-version 'babashka/fs)]
        (is (:git/sha clj-kondo-upgraded))
        (is (:git/url clj-kondo-upgraded))
        (is (:git/sha fs-upgraded))
        (is (:git/url fs-upgraded))
        ;; should be unchanged
        (is (= clj-kondo-original clj-kondo-upgraded))
        ;; should be a different sha
        (is (not (= fs-original fs-upgraded)))))))

(deftest dep-upgrade-test-updates-aliases-independently
  (testing "upgrading an alias's :extra-deps works as expected"
    (spit test-file-path "{}")
    ;; here we add the same dep to two aliases
    (test-util/neil "dep add :lib clj-kondo/clj-kondo --alias lint --sha 6ffc3934cb83d2c4fff16d84198c73b40cd8a078" :deps-file test-file-path)
    (test-util/neil "dep add :lib clj-kondo/clj-kondo --alias other-lint --version 2020.01.01" :deps-file test-file-path)
    (let [initial-versions (get-alias-versions 'clj-kondo/clj-kondo)]
      (test-util/neil "dep upgrade" :deps-file test-file-path)
      (let [upgraded-versions (get-alias-versions 'clj-kondo/clj-kondo)
            lint-clj-v        (-> (->edn) :aliases :lint :extra-deps (get 'clj-kondo/clj-kondo))
            other-lint-clj-v  (-> (->edn) :aliases :other-lint :extra-deps (get 'clj-kondo/clj-kondo))]
        ;; both should be upgraded - there should be no overlap in these sets
        (is (nil? (seq (set/intersection initial-versions upgraded-versions))))
        ;; lint alias still has :git/sha key
        (is (:git/sha lint-clj-v))
        ;; other-lint alias still has :mvn/version key
        (is (:mvn/version other-lint-clj-v)))))

  (testing "specifying an alias only upgrades for that alias"
    (spit test-file-path "{}")
    ;; here we add the same dep to two aliases
    (test-util/neil (str "dep add :lib clj-kondo/clj-kondo"
                         " --alias lint --sha 6ffc3934cb83d2c4fff16d84198c73b40cd8a078")
                    :deps-file test-file-path)
    (test-util/neil "dep add :lib babashka/fs :version 0.0.1" :deps-file test-file-path)
    (let [initial-clj-kondo-v (first (get-alias-versions 'clj-kondo/clj-kondo))
          initial-fs-v        (get-dep-version 'babashka/fs)]
      (test-util/neil "dep upgrade --alias lint" :deps-file test-file-path)
      (let [upgraded-clj-kondo-v (first (get-alias-versions 'clj-kondo/clj-kondo))
            upgraded-fs-v        (get-dep-version 'babashka/fs)]
        (is (= initial-fs-v upgraded-fs-v))
        (is (not (= initial-clj-kondo-v upgraded-clj-kondo-v)))))))
