(ns user
  (:require [cljsfiddle.server]
            [clojure.string :as str]
            [integrant.core :as ig]
            [clojure.java.io :as io]
            [ring.util.mime-type :as mime])
  (:import (java.io File)))

(defonce system
  (atom nil))

(defmethod ig/init-key :dev/sandboxes
  [_ {:keys [path]}]
  (let [read-file (fn [file]
                    (let [file ^File (apply io/file path "resources" "public" (str/split file #"/"))]
                      (when (and (.exists file) (not (.isDirectory file)))
                        {:Body          (slurp file)
                         :ContentType   (mime/ext-mime-type (str file))
                         :ContentLength (str (.length file))})))
        read-src  (comp :Body read-file)]
    {"dev" {:read-file read-file
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