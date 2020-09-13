(ns cljsfiddle.server
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.cors :refer [wrap-cors]])
  (:import (java.net URL)
           (java.util.jar JarFile)))

(defn read-manifest [^JarFile sandbox]
  (when-let [entry (.getEntry sandbox "cljsfiddle.manifest.edn")]
    (with-open [stream (.getInputStream sandbox entry)]
      (edn/read-string (slurp stream)))))

(defn sandboxes []
  (->> (io/resource "sandbox")
       (io/file)
       (file-seq)
       (map str)
       (filter #(str/ends-with? % ".jar"))
       (map #(last (str/split % #"/")))
       (map #(str "sandbox/" %))
       (map io/resource)
       (map (fn [^URL resource]
              (let [jar-file (JarFile. (io/file resource))]
                ;; TODO: spec validation over manifest edn
                {:resource resource
                 :jar-file jar-file
                 :manifest (read-manifest jar-file)})))
       (group-by #(-> % :manifest :sandbox/version))
       (map (fn [[version [sandbox]]]
              [version sandbox]))
       (into {})))

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

(defn entries [^JarFile sandbox]
  (into #{} (map str) (iterator-seq (.entries sandbox))))

(defn load-source
  [sandboxes version {:keys [name macros]}]
  (when-let [sandbox ^JarFile (get-in sandboxes [version :jar-file])]
    (let [entries (entries sandbox)
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


(defmulti rpc (fn [_ctx req] (:request req)))

(defmethod rpc :default [_ _]
  {:status 404})

(defmethod rpc :env/load [{:keys [sandboxes]} {:keys [sandbox/version opts]}]
  ;; TODO: memoize response
  (if-let [resp (load-source sandboxes version opts)]
    {:status  200
     :body    (pr-str resp)
     :headers {"Content-Type" "application/edn"}}
    {:status 404}))

(defn respond-with-manifest
  [{:keys [sandboxes]}]
  {:status  200
   :headers {"Content-Type" "application/edn"}
   :body    (->> sandboxes
                 (map (fn [[id {:keys [manifest]}]]
                        [id manifest]))
                 (into {})
                 (pr-str))})

(defn handler
  [ctx req]
  (case (:request-method req)
    :post (rpc ctx (-> req :body slurp edn/read-string))
    :get  (respond-with-manifest ctx)
    {:status 405}))

(defn -main [& _]
  (let [ctx {:sandboxes (sandboxes)}]
    (jetty/run-jetty
     (wrap-cors (partial handler ctx)
                :access-control-allow-origin [#".*"]
                :access-control-allow-methods [:get :post])
     {:port 3000 :join? false})))

(comment
 (load-source "1" {:name 'reagent.debug :macros true}))