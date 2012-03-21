(ns com.puppetlabs.cmdb.cli.deactivate
  (:require [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [clj-http.client :as client]
            [clj-http.util :as util])
  (:use [com.puppetlabs.utils :only (cli! ini-to-map utf8-string->sha1)]))

(defn deactivate
  "Submits a 'deactivate node' request for `node` to the Grayskull instance
  specified by `host` and `port`. Returns a true value if submission succeeded,
  and a false value otherwise."
  [node host port]
  (let [msg    (-> {:command "deactivate node"
                    :version 1
                    :payload (json/generate-string node)}
                 (json/generate-string))
        body   (format "checksum=%s&payload=%s"
                       (utf8-string->sha1 msg)
                       (util/url-encode msg))
        url    (format "http://%s:%s/commands" host port)
        result (client/post url {:body               body
                                 :throw-exceptions   false
                                 :content-type       :x-www-form-urlencoded
                                 :character-encoding "UTF-8"
                                 :accept             :json})]
    (if (= 200 (:status result))
      true
      (log/error result))))

(defn -main
  [& args]
  (let [[options nodes] (cli! args
                              ["-c" "--config" "Path to config.ini"])
        config      (ini-to-map (:config options))
        host        (get-in config [:jetty :host] "localhost")
        port        (get-in config [:jetty :port] 8080)
        failures    (->> nodes
                      (map (fn [node]
                             (log/info (str "Submitting deactivation command for " node))
                             (deactivate node host port)))
                      (filter (complement identity))
                      (count))]
    (System/exit failures)))
