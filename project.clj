(defproject cljspad/server "1.0.0"
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [ring "1.8.1"]
                 [ring/ring-defaults "0.3.2"]
                 [metosin/reitit-core "0.5.5"]
                 [metosin/reitit-ring "0.5.5"]
                 [com.cognitect.aws/api "0.8.474"]
                 [com.cognitect.aws/endpoints "1.1.11.842"]
                 [com.cognitect.aws/s3 "809.2.734.0"]
                 [integrant "0.8.0"]
                 [selmer "1.12.28"]
                 [cheshire "5.10.0"]
                 [clj-http "3.10.3"]]
  :main cljspad.server
  :source-paths ["src"]
  :resource-paths ["resources"]
  :profiles {:uberjar {:aot :all}
             :dev     {:source-paths ["src" "dev-src"]}})