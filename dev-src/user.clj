(ns user
  (:require [cljsfiddle.server]
            [integrant.core :as ig]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

(defonce system
  (atom nil))

(defmethod ig/init-key :dev/sandboxes
  [_ {:keys [path]}]
  (let [read-file (fn [file]
                    (let [file (io/file path "resources" "public" file)]
                      (when (.exists file)
                        {:Body (slurp file)})))
        read-src  (comp :Body read-file)]
    {"dev" {:manifest  (edn/read-string (slurp (io/file path "resources" "public" "cljsfiddle.manifest.edn")))
            :read-file read-file
            :read-src  read-src}}))

(defn config [cljsfiddle-path]
  {:dev/sandboxes {:path cljsfiddle-path}
   :ring/handler  {:ctx {:sandboxes      (ig/ref :dev/sandboxes)
                         :latest-sandbox "dev"}}
   :ring/server   {:handler (ig/ref :ring/handler)
                   :port    3000}})

(defn start! [cljsfiddle-path]
  (reset! system (ig/init (config cljsfiddle-path))))

(defn stop! []
  (swap! system ig/halt!))