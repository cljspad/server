(ns cljsfiddle.server
  (:require [clojure.string :as str]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.defaults :as defaults]
            [reitit.ring :as ring]
            [cognitect.aws.client.api :as aws]
            [integrant.core :as ig]
            [selmer.parser :as selmer]
            [ring.util.anti-forgery :as anti-forgery]
            [clj-http.client :as http]
            [clojure.core.memoize :as memo]
            [clojure.java.io :as io])
  (:import (java.util.concurrent CountDownLatch)
           (org.eclipse.jetty.server Server)
           (org.eclipse.jetty.server.handler.gzip GzipHandler))
  (:gen-class))

(defn read-s3-file [client bucket sandbox file]
  (let [resp (aws/invoke client {:op      :GetObject
                                 :request {:Bucket bucket
                                           :Key    (str sandbox "/" file)}})]
    (when-not (:cognitect.anomalies/category resp)
      resp)))

(defn ->sandbox [client bucket sandbox]
  (let [read-file (partial read-s3-file client bucket sandbox)]
    [sandbox {:read-file (memoize read-file)}]))

(defn sandboxes [client bucket]
  (->> {:op      :ListObjectsV2
        :request {:Bucket    bucket
                  :Delimiter "/"}}
       (aws/invoke client)
       :CommonPrefixes
       (map :Prefix)
       (map #(str/replace % "/" ""))
       (map (partial ->sandbox client bucket))
       (into {})))

(defn s3-resp->ring-resp [resp]
  {:status  200
   :body    (:Body resp)
   :headers (->> {"Content-Type"   (when-let [content-type (:ContentType resp)]
                                     (str content-type))
                  "Content-Length" (when-let [content-length (:ConentLength resp)]
                                     (when (pos? content-length)
                                       (str content-length)))
                  "Last-Modified"  (when-let [last-modified (:LastModified resp)]
                                     (str last-modified))
                  "ETag"           (when-let [etag (:Etag resp)]
                                     (str etag))}
                 (filter (fn [[_ v]] (some? v)))
                 (into {}))})

(defn s3-handler
  [{:keys [sandboxes]} req]
  (when-let [version (get-in req [:path-params :version])]
    (when-let [read-file (get-in sandboxes [version :read-file])]
      (when-let [resp (read-file (subs (:uri req) (count (str "/sandbox/" version "/"))))]
        (s3-resp->ring-resp resp)))))

(defn index-html
  [{:keys [latest-sandbox sandboxes]} req]
  (let [version (get-in req [:path-params :version] latest-sandbox)]
    (when-let [read-file (get-in sandboxes [version :read-file])]
      (let [gist-id    (get-in req [:path-params :gist-id])
            extra-opts (:cljsfiddle/opts req)
            index      (read-file "index.html")
            opts       {:sandbox-version version
                        :opts            (cond-> {:latest latest-sandbox}
                                           gist-id (assoc :gist_id gist-id)
                                           extra-opts (merge extra-opts))
                        :anti-forgery    (anti-forgery/anti-forgery-field)}
            body       (selmer/render (slurp (:Body index)) opts)]
        {:status  200
         :body    body
         :headers {"Content-Type" "text/html"}}))))

(def embed-html
  (delay
   (slurp (io/resource "embed.html"))))

(defn embed-index-html
  [ctx req]
  (let [selected-tab (get-in req [:query-params "selected_tab"] "editor")
        defer-load?  (case (get-in req [:query-params "defer_load"] "true")
                       "true" true
                       "false" false
                       true)
        opts         {:embed true :selected_tab selected-tab}
        href         (if (empty? (:query-params req))
                       (str (:uri req) "?defer_load=false")
                       (str (:uri req) "&defer_load=false"))]
    (if defer-load?
      {:status  200
       :body    (selmer/render @embed-html {:href href})
       :headers {"Content-Type" "text/html"}}

      (index-html ctx (assoc req :cljsfiddle/opts opts)))))

(defn fetch-gist
  [{:keys [client-id client-secret]} gist-id]
  (http/get (str "https://api.github.com/gists/" gist-id)
            (cond-> {:throw-exceptions false
                     :as               :json
                     :timeout          10}
              (and client-id client-secret)
              (assoc :basic-auth [client-id client-secret]))))

(defn fetch-gist-raw-url [url]
  (let [resp (http/get url {:throw-exceptions false :timeout 10})]
    (when (= 200 (:status resp))
      (:body resp))))

;; Simple memoization so we don't thrash our 5000 req/hour ratelimit
(def fetch-gist-mz
  (memo/ttl fetch-gist :ttl/threshold 30000))

(defn load-gist
  [{:keys [github]} req]
  (when-let [gist-id (get-in req [:path-params :gist-id])]
    (let [resp (fetch-gist-mz github gist-id)]
      (if (= 200 (:status resp))
        (when-let [file (or (some (fn [[k v]]
                                    (when (str/ends-with? (name k) ".cljs")
                                      v))
                                  (-> resp :body :files))
                            (-> resp :body :files first second))]
          (when-let [source (if (:truncated file)
                              (fetch-gist-raw-url (:raw_url file))
                              (:content file))]
            {:status  200
             :body    source
             :headers {"Content-Type" "text/plain"}}))

        {:status (:status resp)}))))

(defn routes
  [ctx]
  [["/" {:get {:handler (partial index-html ctx)}}]
   ["/api/v1/gist/:gist-id" {:get {:handler (partial load-gist ctx)}}]
   ["/gist/:gist-id" {:get {:handler (partial index-html ctx)}}]
   ["/gist/:version/:gist-id" {:get {:handler (partial index-html ctx)}}]
   ["/embed/:gist-id" {:get {:handler (partial embed-index-html ctx)}}]
   ["/embed/:version/:gist-id" {:get {:handler (partial embed-index-html ctx)}}]
   ["/sandbox/:version" {:get {:handler (partial index-html ctx)}}]
   ["/sandbox/:version/*" {:get {:handler (partial s3-handler ctx)}}]])

(def not-found
  (constantly {:status 404 :body "" :headers {}}))

(defn wrap-s3-latest
  [{:keys [sandboxes latest-sandbox]} req]
  (when-let [read-file (get-in sandboxes [latest-sandbox :read-file])]
    (when-let [resp (read-file (:uri req))]
      (s3-resp->ring-resp resp))))

(defn handler
  [ctx]
  (ring/ring-handler
   (ring/router (routes ctx))
   (ring/routes (ring/redirect-trailing-slash-handler {:method :strip})
                (partial wrap-s3-latest ctx)
                (ring/create-default-handler {:not-acceptable not-found}))))

(defmethod ig/init-key :s3/client
  [_ {:keys [region]}]
  (aws/client {:api :s3 :region region}))

(defmethod ig/init-key :s3/sandboxes
  [_ {:keys [client bucket]}]
  (sandboxes client bucket))

(defmethod ig/init-key :s3/sandbox-latest
  [_ {:keys [sandboxes]}]
  (->> sandboxes keys sort last))

(defmethod ig/init-key :ring/handler
  [_ {:keys [ctx]}]
  (handler ctx))

(defn jetty-configurator
  [^Server server]
  (let [content-types ["text/css"
                       "text/plain"
                       "text/javascript"
                       "application/javascript"
                       "application/edn"
                       "image/svg+xml"]
        gzip-handler  (doto (GzipHandler.)
                        (.setIncludedMimeTypes (into-array String content-types))
                        (.setMinGzipSize 1024)
                        (.setHandler (.getHandler server)))]
    (.setHandler server gzip-handler)))

(def site-opts
  (assoc-in defaults/site-defaults [:security :frame-options] nil))

(defmethod ig/init-key :ring/server
  [_ {:keys [handler port]}]
  (jetty/run-jetty
   (defaults/wrap-defaults handler site-opts)
   {:port         port
    :join?        false
    :configurator jetty-configurator}))

(defmethod ig/halt-key! :ring/server
  [_ ^Server server]
  (.stop server))

(defn config []
  {:s3/client         {:region (System/getenv "S3_REGION")}
   :s3/sandboxes      {:client (ig/ref :s3/client)
                       :bucket (System/getenv "S3_BUCKET")}
   :s3/sandbox-latest {:sandboxes (ig/ref :s3/sandboxes)}
   :ring/handler      {:ctx {:sandboxes      (ig/ref :s3/sandboxes)
                             :latest-sandbox (ig/ref :s3/sandbox-latest)
                             :github         {:client-id     (System/getenv "GITHUB_CLIENT_ID")
                                              :client-secret (System/getenv "GITHUB_CLIENT_SECRET")}}}
   :ring/server       {:handler (ig/ref :ring/handler)
                       :port    (Long/parseLong (System/getenv "PORT"))}})

(defn -main [& _]
  (try
    (let [system (ig/init (config))
          latch  (CountDownLatch. 1)]
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (.countDown latch))))
      (.await latch)
      (ig/halt! system)
      (System/exit 0))
    (catch Throwable e
      (.printStackTrace e)
      (System/exit 1))))