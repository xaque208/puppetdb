(ns com.puppetlabs.puppetdb.query.fact-nodes
  (:require [com.puppetlabs.jdbc :as jdbc]
            [com.puppetlabs.puppetdb.facts :as f]
            [com.puppetlabs.puppetdb.query :as query]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.puppetdb.query-eng :as qe]
            [com.puppetlabs.puppetdb.schema :as pls]
            [clojure.edn :as clj-edn]
            [schema.core :as s]))

(def row-schema
  {:certname s/Str
   :environment (s/maybe s/Str)
   :path s/Str
   :name s/Str
   :value (s/maybe s/Str)
   :type s/Str})

(def converted-row-schema
  {:certname s/Str
   :environment (s/maybe s/Str)
   :path f/fact-path
   :name s/Str
   :value f/fact-value})

(pls/defn-validated munge-result-row :- converted-row-schema
  "Coerce the value of a row to the proper type, and convert the path back to
   an array structure."
  [row :- row-schema]
  (-> row
      (update-in [:value] #(f/unstringify-value (:type row) %))
      (update-in [:path] f/string-to-factpath)
      (dissoc :type)))

(defn munge-result-rows
  "Munge resulting rows for fact-nodes endpoint."
  [rows]
  (map munge-result-row rows))

(defn query->sql
  "Compile a query into an SQL expression."
  [version query paging-options]
  {:pre [((some-fn nil? sequential?) query) ]
   :post [(map? %)
          (string? (first (:results-query %)))
          (every? (complement coll?) (rest (:results-query %)))]}
  (qe/compile-user-query->sql qe/fact-nodes-query query paging-options))
