(defproject wombat "0.1.0-SNAPSHOT"
  :description "A Scheme-like LISP"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0-alpha3"]
                 [org.ow2.asm/asm-all "5.0.3"]
                 [org.clojure/core.match "0.2.1"]]
  :source-paths ["src/clj"]
  :java-source-paths ["src/jvm"]
  :javac-options ["-target" "1.7" "-source" "1.7"]
  :main wombat.core)
