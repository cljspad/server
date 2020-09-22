(ns cljsfiddle.server
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.defaults :refer [wrap-defaults]]
            [reitit.ring :as ring]
            [cognitect.aws.client.api :as aws]
            [integrant.core :as ig]
            [ring.util.response :as resp])
  (:import (java.util.concurrent CountDownLatch)
           (org.eclipse.jetty.server Server)
           (org.eclipse.jetty.server.handler.gzip GzipHandler))
  (:gen-class))

(defn read-s3-file [client bucket sandbox file]
  (let [resp (aws/invoke client {:op      :GetObject
                                 :request {:Bucket bucket
                                           :Key    (str sandbox "/" file)}})]
    (when-not (:cognitect.anomalies/category resp)
      (update resp :Body slurp))))

(defn ->sandbox [client bucket sandbox]
  (let [read-file (partial read-s3-file client bucket sandbox)
        read-src  (comp :Body read-file)]
    [sandbox {:read-src  (memoize read-src)
              :read-file (memoize read-file)}]))

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

(defn sym->classpath [sym]
  (-> sym
      name
      (str/replace "." "/")
      (str/replace "-" "_")))

;; TODO: these are very hacky - implement something cleaner...
(defn unpack-goog-require1 [s]
  (let [[x y & xs] (str/split (str s) #"\.")]
    (sym->classpath (str/join "." (into [x y y] (map str/lower-case xs))))))

(defn unpack-goog-require2 [s]
  (let [[x y & xs] (str/split (str s) #"\.")]
    (sym->classpath (str/join "." (into [x y] (map str/lower-case xs))))))

(defn unpack-goog-require3 [s]
  (let [[x y & xs] (str/split (str s) #"\.")]
    (sym->classpath (str/join "." (into [x (some-> y str/lower-case)] (map str/lower-case xs))))))

(defn try-read-cache
  [{:keys [read-src]} name]
  (or (read-src (format "cljsfiddle/%s.cljs.cache.json" (sym->classpath name)))
      (read-src (format "cljsfiddle/%s.cljc.cache.json" (sym->classpath name)))))

(defn read-clj
  [{:keys [read-src] :as sandbox} {:keys [macros name]} entry]
  (when-let [src (read-src entry)]
    {:source src
     :lang   :clj
     :cache  (when-not macros
               (try-read-cache sandbox name))}))

(defn read-js
  [{:keys [read-src]} entry]
  (when-let [src (read-src entry)]
    {:source src
     :lang   :js}))

;; TODO: remove all special cases...
(def special-cases
  #{'cljs.core.async.impl.ioc-helpers
    'cljs.core.async})

(defn load-source
  [sandboxes version {:keys [name macros] :as opts}]
  (when-let [sandbox (get sandboxes version)]
    (let [entry    (sym->classpath name)
          read-js  (partial read-js sandbox)
          read-clj (partial read-clj sandbox opts)]
      (cond
        (special-cases name)
        (read-js (str "cljsfiddle/" entry ".js"))

        macros
        (or (read-js (str "cljsfiddle/" entry ".js"))
            (read-clj (str "cljsfiddle/" entry ".cljc")))

        :else
        (or (read-clj (str "cljsfiddle/" entry ".cljs"))
            (read-clj (str "cljsfiddle/" entry ".cljc"))
            (read-js (str "cljsfiddle/" entry ".js"))
            (read-js (str "cljsfiddle/" (unpack-goog-require1 name) ".js"))
            (read-js (str "cljsfiddle/" (unpack-goog-require2 name) ".js"))
            (read-js (str "cljsfiddle/" (unpack-goog-require3 name) ".js"))
            {:source (format "require('%s');" name)
             :lang   :js})))))

(defmulti rpc (fn [_ctx req] (:request req)))

(defmethod rpc :default [_ _]
  {:status 404})

(defmethod rpc :env/load
  [{:keys [sandboxes]} {:keys [sandbox/version opts]}]
  (if-let [resp (load-source sandboxes version opts)]
    {:status  200
     :body    (pr-str resp)
     :headers {"Content-Type" "application/edn"}}
    {:status 404}))

(defn s3-handler
  [{:keys [sandboxes]} req]
  (when-let [version (get-in req [:path-params :version])]
    (when-let [read-file (get-in sandboxes [version :read-file])]
      (when-let [resp (read-file (subs (:uri req) (count (str "/sandbox/" version "/"))))]
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
                       (into {}))}))))

(defn s3-handler-latest
  [{:keys [latest-sandbox] :as ctx} req]
  (s3-handler ctx (-> req
                      (assoc-in [:path-params :version] latest-sandbox)
                      (update :uri #(str "/sandbox/" latest-sandbox
                                         (if (or (str/blank? %) (= "/" %))
                                           "/index.html"
                                           %))))))

(defn s3-handler-latest-index
  [ctx req]
  (s3-handler-latest ctx (assoc req :uri "/index.html")))

(defn routes
  [ctx]
  [["/api/:version/rpc" {:post {:handler #(rpc ctx (-> % :body slurp edn/read-string))}}]
   ["/gist/:version/:id" {:get {:handler #(s3-handler ctx (assoc % :uri "/index.html"))}}]
   ["/gist/:id" {:get {:handler (partial s3-handler-latest-index ctx)}}]
   ["/sandbox/:version" {:get {:handler #(resp/redirect (str (:uri %) "/index.html"))}}]
   ["/sandbox/:version/*" {:get {:handler (partial s3-handler ctx)}}]])

(defn handler
  [ctx]
  (ring/ring-handler
   (ring/router (routes ctx))
   (ring/routes (ring/redirect-trailing-slash-handler {:method :strip})
                (partial s3-handler-latest ctx)
                (ring/create-default-handler))))

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

(defmethod ig/init-key :ring/server
  [_ {:keys [handler port]}]
  ;; TODO: set security defaults once CSRF token injection added to index.html
  (jetty/run-jetty (wrap-defaults handler {:security nil})
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
                             :latest-sandbox (ig/ref :s3/sandbox-latest)}}
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