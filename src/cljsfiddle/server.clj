(ns cljsfiddle.server
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.cors :refer [wrap-cors]])
  (:import (java.util.jar JarFile)))

(def sandbox
  {"1" (JarFile. (io/file "/Users/thomascrowley/Code/clojure/sandbox/target/sandbox-1.0.0-standalone.jar"))})

(defn sym->classpath [sym]
  (-> sym
      name
      (str/replace "." "/")
      (str/replace "-" "_")))

(defn try-read-cache [^JarFile sandbox name]
  (when-let [entry (or (.getEntry sandbox (format "cljsfiddle/%s.cljs.cache.json" (sym->classpath name)))
                       (.getEntry sandbox (format "cljsfiddle/%s.cljc.cache.json" (sym->classpath name))))]
    (with-open [stream (.getInputStream sandbox entry)]
      (slurp stream))))

;; TODO: these are very hacky - implement something cleaner...
(defn unpack-goog-require1 [s]
  (let [[x y & xs] (str/split (str s) #"\.")]
    (sym->classpath (str/join "." (into [x y y] (map str/lower-case xs))))))

(defn unpack-goog-require2 [s]
  (let [[x y & xs] (str/split (str s) #"\.")]
    (sym->classpath (str/join "." (into [x y] (map str/lower-case xs))))))

(defn load-source
  [version {:keys [name macros]}]
  (when-let [sandbox ^JarFile (get sandbox version)]
    (let [entries (into #{} (map str) (iterator-seq (.entries sandbox)))
          entry   (sym->classpath name)]
      (if-let [entry (if macros
                       (or (entries (str entry ".clj"))
                           (entries (str entry ".cljc")))
                       (or (entries (str entry ".cljs"))
                           (entries (str entry ".cljc"))))]
        (with-open [stream (.getInputStream sandbox (.getEntry sandbox entry))]
          {:source (slurp stream)
           :lang   :clj
           :cache  (when-not macros
                     (try-read-cache sandbox name))})
        (when-not macros
          (if-let [entry (or (entries (str entry ".js"))
                               (entries (str (unpack-goog-require1 name) ".js"))
                               (entries (str (unpack-goog-require2 name) ".js")))]
            (with-open [stream (.getInputStream sandbox (.getEntry sandbox entry))]
              {:source (slurp stream)
               :lang   :js})))))))

(def load-source-mz
  (memoize load-source))

(defmulti rpc :request)

(defmethod rpc :default [_]
  {:status 404})

(defmethod rpc :env/load [{:keys [sandbox/version opts]}]
  (if-let [resp (load-source-mz version opts)]
    {:status  200
     :body    (pr-str resp)
     :headers {"Content-Type" "application/edn"}}
    {:status 404}))

(defn handler
  [req]
  (if (= :post (:request-method req))
    (rpc (-> req :body slurp edn/read-string))
    {:status 405}))

(defn -main [& _]
  (jetty/run-jetty
   (wrap-cors handler
              :access-control-allow-origin [#".*"]
              :access-control-allow-methods [:get :post])
   {:port 3000 :join? false}))

(comment
 (load-source "1" {:name 'reagent.debug :macros true}))