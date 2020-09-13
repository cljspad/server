(defproject cljsfiddle/server "1.0.0"
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [ring "1.8.1"]
                 [ring-cors "0.1.13"]
                 [metosin/reitit-core "0.5.5"]
                 [metosin/reitit-ring "0.5.5"]]
  :main cljsfiddle.server
  :source-paths ["src"]
  :resource-paths ["resources"])