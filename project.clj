(defproject cljsfiddle/server "1.0.0"
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [ring "1.8.1"]
                 [ring/ring-defaults "0.3.2"]
                 [metosin/reitit-core "0.5.5"]
                 [metosin/reitit-ring "0.5.5"]
                 [com.cognitect.aws/api "0.8.474"]
                 [com.cognitect.aws/endpoints "1.1.11.842"]
                 [com.cognitect.aws/s3 "809.2.734.0"]
                 [integrant "0.8.0"]]
  :main cljsfiddle.server
  :source-paths ["src"]
  :resource-paths ["resources"]
  :profiles {:dev {:source-paths ["src" "dev-src"]}})