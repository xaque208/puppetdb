(ns com.puppetlabs.puppetdb.test.facts
  (:require [com.puppetlabs.puppetdb.facts :refer :all]
            [com.puppetlabs.puppetdb.query.factsets :as fs]
            [clojure.string :as string]
            [com.puppetlabs.puppetdb.query.facts :refer :all]
            [clj-time.core :refer [now]]
            [clj-time.coerce :refer [to-timestamp]]
            [clojure.test :refer :all]))

(def current-time (to-timestamp (now)))

(deftest test-flatten-fact-value
  (testing "check basic types work"
    (is (= (flatten-fact-value "foo") "foo"))
    (is (= (flatten-fact-value 3) "3"))
    (is (= (flatten-fact-value true) "true"))
    (is (= (flatten-fact-value {:a :b}) "{\"a\":\"b\"}"))
    (is (= (flatten-fact-value [:a :b]) "[\"a\",\"b\"]"))))

(deftest test-flatten-fact-set
  (testing "ensure we get back a flattened set of values"
    (is (= (flatten-fact-set {"networking"
                              {"eth0"
                               {"ipaddresses" ["192.168.1.1"]}}})
           {"networking" "{\"eth0\":{\"ipaddresses\":[\"192.168.1.1\"]}}"}))))

(deftest test-factmap-to-paths
  (testing "should convert a conventional factmap to a set of paths"
    (is (= (sort-by :value_hash (factmap-to-paths {"networking"
                                                   {"eth0"
                                                    {"ipaddresses" ["1.1.1.1", "2.2.2.2"]}}
                                                   "os"
                                                   {"operatingsystem" "Linux"}
                                                   "avgload" 5.64
                                                   "empty_hash" {}
                                                   "empty_array" []}))
           [{:path "os#~operatingsystem"
             :depth 1
             :value_type_id 0
             :value_hash "a5bc91f0e5033e61ed90ff6621fb0bf1c8355f64"
             :value_string "Linux"
             :value_integer nil
             :value_float nil
             :value_boolean nil
             :name "os"}
            {:path "networking#~eth0#~ipaddresses#~1"
             :depth 3
             :value_type_id 0
             :value_hash "c1a1b4decce49801f7f41873282b1650aef5137d"
             :value_string "2.2.2.2"
             :value_integer nil
             :value_float nil
             :value_boolean nil
             :name "networking"}
            {:path "avgload"
             :depth 0
             :value_type_id 2
             :value_hash "ee5b587330bf5e2f31eade331c1ec2a1213b7457"
             :value_string nil
             :value_integer nil
             :value_float 5.64
             :value_boolean nil
             :name "avgload"}
            {:path "networking#~eth0#~ipaddresses#~0"
             :depth 3
             :value_type_id 0
             :value_hash "fcdd2924e5804c69ee520dcbd31b717b81ed66c5"
             :value_string "1.1.1.1"
             :value_integer nil
             :value_float nil
             :value_boolean nil
             :name "networking"}]))))

(deftest test-str->num
  (are [n s] (= n (str->num s))

       10 "10"
       123 "123"
       0 "0"
       nil "foo"
       nil "123foo"))

(deftest test-int-map->vector
  (are [v m] (= v (int-map->vector m))

       ["foo" "bar" "baz"] {1 "bar" 0 "foo" 2 "baz"}
       nil {"1" "bar" "0" "foo" "2" "baz"}
       nil {1 "bar" "0" "foo" "2" "baz"} ;;shouldn't be possible
       ))

(deftest test-unescape-string
  (are [unescaped s] (= unescaped (unescape-string s))

       "foo" "\"foo\""
       "foo" "foo"
       "123" "123"
       "123foo" "\"123foo\""))

(deftest test-unencode-path-segment
  (are [path-segment s] (= path-segment (unencode-path-segment s))

       "foo" "\"foo\""
       "\"foo\"" "\"\"foo\"\""
       "foo" "foo"

       "123" "\"123\""
       "123foo" "\"123foo\""
       1 "1"
       123 "123"))

(deftest test-factpath-to-string-and-reverse
  (let [data
        [["foo#~bar"      ["foo" "bar"]]
         ["foo"           ["foo"]]
         ["foo#~bar#~baz" ["foo" "bar" "baz"]]
         ["foo\\#\\~baz"  ["foo#~baz"]]
         ["foo#~0"        ["foo" 0]]
         ["foo#~\"123\""  ["foo" "123"]]]]
    (doseq [[r l] data]
      (is (= (string-to-factpath r) l))
      (is (= r (factpath-to-string l))))))

(deftest test-structured-data-seq
  (let [test-rows [{:certname "foo.com" :environment "DEV" :path "a#~b#~c" :value "abc" :type "string" :timestamp current-time}
                   {:certname "foo.com" :environment "DEV" :path "a#~b#~d" :value "1" :type "integer" :timestamp current-time}
                   {:certname "foo.com" :environment "DEV" :path "a#~b#~e" :value "true" :type "boolean" :timestamp current-time}
                   {:certname "foo.com" :environment "DEV" :path "a#~b#~f" :value "3.14" :type "float" :timestamp current-time}]]
    (is (= [{:certname "foo.com"
             :environment "DEV"
             :facts {"a" {"b" {"c" "abc"
                               "d" 1
                               "e" true
                               "f" 3.14}}}
             :timestamp current-time}]
           (structured-data-seq :v4 test-rows create-certname-pred
                                fs/collapse-factset fs/convert-types))))

  (testing "laziness of the collapsing fns"
    (let [ten-billion 10000000000]
      (is (= 10
             (count
              (take 10
                    (structured-data-seq :v4
                     (mapcat (fn [certname]
                               [{:certname certname :environment "DEV" :path "a#~b#~c"
                                 :value "abc" :type "string" :timestamp current-time}
                                {:certname certname :environment "DEV" :path "a#~b#~d"
                                 :value "1" :type "integer" :timestamp current-time}
                                {:certname certname :environment "DEV" :path "a#~b#~e"
                                 :value "3.14" :type "float" :timestamp current-time}
                                {:certname certname :environment "DEV" :path "a#~b#~f"
                                 :value "true" :type "boolean" :timestamp current-time}])
                             (map #(str "foo" % ".com") (range 0 ten-billion)))
                    create-certname-pred fs/collapse-factset fs/convert-types)))))))

  (testing "map with a nested vector"
    (let [test-rows [{:certname "foo.com" :environment "DEV"
                      :path "a#~b#~c" :value "abc" :type "string" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV"
                      :path "a#~b#~d#~0" :value "1" :type "integer" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV"
                      :path "a#~b#~d#~1" :value "3" :type "integer" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV"
                      :path "a#~b#~e" :value "true" :type "boolean" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV"
                      :path "a#~b#~f" :value "abf" :type "string" :timestamp current-time}]]

      (is (= [{:certname "foo.com"
               :environment "DEV"
               :facts {"a" {"b" {"c" "abc"
                                 "d" [1 3]
                                 "e" true
                                 "f" "abf"}}}
               :timestamp current-time}]
             (structured-data-seq :v4 test-rows create-certname-pred
                                   fs/collapse-factset fs/convert-types)))))
  (testing "map with a nested vector of maps"
    (let [test-rows [{:certname "foo.com" :environment "DEV" :path "a#~b#~c" :value "abc" :type "string" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~0#~e#~f#~0" :value "1" :type "integer" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~1#~e#~f#~0" :value "2" :type "integer" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~e" :value "abe" :type "string" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~f" :value "abf" :type "string" :timestamp current-time}]]
      (is (= [{:certname "foo.com"
               :environment "DEV"
               :facts {"a" {"b" {"c" "abc"
                                 "d" [{"e" {"f" [1]}}
                                      {"e" {"f" [2]}}]
                                 "e" "abe"
                                 "f" "abf"}}}
               :timestamp current-time}]
             (structured-data-seq :v4 test-rows create-certname-pred
                                  fs/collapse-factset fs/convert-types)))))

  (testing "json numeric formats"
    (let [test-rows [{:certname "foo.com" :environment "DEV" :path "a#~b#~c" :value "10E10" :type "integer" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~\"0\"#~e#~f#~0" :value "3.14E10" :type "float" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~\"1\"#~e#~f#~0" :value "1.4e-5" :type "float" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~e" :value "-10E-5" :type "float" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~f" :value "-0.25e-5" :type "float" :timestamp current-time}]]
      (is (= [{:certname "foo.com"
               :environment "DEV"
               :facts {"a" {"b" {"c" 100000000000
                                 "d" {"0" {"e" {"f" [3.14E10]}}
                                      "1" {"e" {"f" [1.4E-5]}}}
                                 "e"  -1.0e-4
                                 "f" -2.5E-6}}}
               :timestamp current-time}]
             (structured-data-seq :v4 test-rows create-certname-pred
                                  fs/collapse-factset fs/convert-types)))))

  (testing "map stringified integer keys"
    (let [test-rows [{:certname "foo.com" :environment "DEV" :path "a#~b#~c"
                      :value "abc" :type "string" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~\"0\"#~e#~f#~0"
                      :value "1" :type "integer" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV"
                      :path "a#~b#~d#~\"1\"#~e#~f#~0" :value "2" :type "integer"
                      :timestamp current-time}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~e"
                      :value "abe" :type "string" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~j"
                      :value nil :type "null" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~f"
                      :value "abf" :type "string" :timestamp current-time}]]

      (is (= [{:certname "foo.com"
               :environment "DEV"
               :facts {"a" {"b" {"c" "abc"
                                 "d" {"0" {"e" {"f" [1]}}
                                      "1" {"e" {"f" [2]}}}
                                 "e" "abe"
                                 "f" "abf"
                                 "j" nil}}}
               :timestamp current-time}]
             (structured-data-seq :v4 test-rows create-certname-pred
                                  fs/collapse-factset fs/convert-types))))))

(deftest test-facts-seq
  (let [test-rows [{:certname "foo.com" :environment "DEV" :path "a#~b#~c" :value "abc" :type "string" :name "a" :depth 2}
                   {:certname "foo.com" :environment "DEV" :path "a#~b#~d" :value "1" :type "integer" :name "a" :depth 2}
                   {:certname "foo.com" :environment "DEV" :path "a#~b#~e" :value "true" :type "boolean" :name "a" :depth 2}
                   {:certname "foo.com" :environment "DEV" :path "a#~b#~f" :value "3.14" :type "float" :name "a" :depth 2}]]
    (is (= [{:certname "foo.com"
             :environment "DEV"
             :value {"b" {"c" "abc"
                               "d" 1
                               "e" true
                               "f" 3.14}}
             :name "a"}]
 
             (structured-data-seq :v4 test-rows factname-certname-pred
                                  collapse-facts convert-types))))

  (testing "laziness of the collapsing fns"
    (let [ten-billion 10000000000]
      (is (= 10
             (count
              (take 10
                    (structured-data-seq :v4
                     (mapcat (fn [certname]
                               [{:certname certname :environment "DEV" :path "a#~b#~c" :value "abc" :type "string" :name "a" :depth 2}
                                {:certname certname :environment "DEV" :path "a#~b#~d" :value "1" :type "integer" :name "a" :depth 2}
                                {:certname certname :environment "DEV" :path "a#~b#~e" :value "3.14" :type "float" :name "a" :depth 2}
                                {:certname certname :environment "DEV" :path "a#~b#~f" :value "true" :type "boolean" :name "a" :depth 2}])
                             (map #(str "foo" % ".com") (range 0 ten-billion)))
                     factname-certname-pred collapse-facts convert-types)))))))

  (testing "map with a nested vector"
    (let [test-rows [{:certname "foo.com" :environment "DEV" :path "a#~b#~c" :value "abc" :type "string" :name "a" :depth 2}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~0" :value "1" :type "integer" :name "a" :depth 3}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~1" :value "3" :type "integer" :name "a" :depth 3}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~e" :value "true" :type "boolean" :name "a" :depth 2}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~f" :value "abf" :type "string" :name "a" :depth 2}]]

      (is (= [{:certname "foo.com"
               :environment "DEV"
               :value {"b" {"c" "abc"
                                 "d" [1 3]
                                 "e" true
                                 "f" "abf"}}
               :name "a"}]
             (structured-data-seq :v4 test-rows
                                  factname-certname-pred collapse-facts
                                  convert-types)))))
  (testing "map with a nested vector of maps"
    (let [test-rows [{:certname "foo.com" :environment "DEV" :path "a#~b#~c" :value "abc" :type "string" :name "a" :depth 2}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~0#~e#~f#~0" :value "1" :type "integer" :name "a" :depth 6}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~1#~e#~f#~0" :value "2" :type "integer" :name "a" :depth 6}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~e" :value "abe" :type "string" :name "a" :depth 2}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~f" :value "abf" :type "string" :name "a" :depth 2}]]
      (is (= [{:certname "foo.com"
               :environment "DEV"
               :value {"b" {"c" "abc"
                                 "d" [{"e" {"f" [1]}}
                                      {"e" {"f" [2]}}]
                                 "e" "abe"
                                 "f" "abf"}}
               :name "a"}]

             (structured-data-seq :v4 test-rows
                                  factname-certname-pred collapse-facts
                                  convert-types)))))

  (testing "json numeric formats"
    (let [test-rows [{:certname "foo.com" :environment "DEV" :path "a#~b#~c" :value "10E10" :type "integer" :name "a" :depth 2}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~\"0\"#~e#~f#~0" :value "3.14E10" :type "float" :name "a" :depth 6}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~\"1\"#~e#~f#~0" :value "1.4e-5" :type "float" :name "a" :depth 6}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~e" :value "-10E-5" :type "float" :name "a" :depth 2}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~f" :value "-0.25e-5" :type "float" :name "a" :depth 2}]]
      (is (= [{:certname "foo.com"
               :environment "DEV"
               :value {"b" {"c" 100000000000
                                 "d" {"0" {"e" {"f" [3.14E10]}}
                                      "1" {"e" {"f" [1.4E-5]}}}
                                 "e"  -1.0e-4
                                 "f" -2.5E-6}}
               :name "a"}]
             (structured-data-seq :v4 test-rows
                                  factname-certname-pred collapse-facts
                                  convert-types)))))

  (testing "map stringified integer keys"
    (let [test-rows [{:certname "foo.com" :environment "DEV" :path "a#~b#~c" :value "abc" :type "string" :name "a" :depth 2}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~\"0\"#~e#~f#~0" :value "1" :type "integer" :name "a" :depth 6}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~\"1\"#~e#~f#~0" :value "2" :type "integer" :name "a" :depth 6}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~e" :value "abe" :type "string" :name "a" :depth 2}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~j" :value nil :type "null" :name "a" :depth 2}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~f" :value "abf" :type "string" :name "a" :depth 2}]]

      (is (= [{:certname "foo.com"
               :environment "DEV"
               :value {"b" {"c" "abc"
                                 "d" {"0" {"e" {"f" [1]}}
                                      "1" {"e" {"f" [2]}}}
                                 "e" "abe"
                                 "f" "abf"
                                 "j" nil}}
              :name "a" }]

             (structured-data-seq :v4 test-rows
                                  factname-certname-pred collapse-facts
                                  convert-types))))))
