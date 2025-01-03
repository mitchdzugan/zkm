(require '[clojure.string :as str])

(defmacro Sub [& _] {:todo :impl})
(defmacro Cmd [& _] {:todo :impl})
(defmacro Col [& _] {:todo :impl})
(defmacro Title [& _] {:todo :impl})

(defn eval-file [filename]
  (let [file-content (try (slurp filename) (catch Exception _ "(-> nil)"))
        code (if-not (str/starts-with? file-content "#!") file-content
               (->> file-content str/split-lines (drop 1) (str/join \newline)))]
    (println (eval (read-string (str "(do " code ")"))))))

(doseq [in-file *command-line-args*]
  (eval-file in-file))
