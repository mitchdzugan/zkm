(ns zkm.core
 (:require [clojure.string :as str]
           [sci.core :as sci])
 (:gen-class))

(def st (atom {:next-menu-id 2
               :curr-id 1}))

(defn curr-menu [& args] (let [{:keys [menus curr-id]} (or (first args) @st)]
                     (get menus curr-id)))
(defn swap!-curr [f]
  (swap! st #(assoc-in %1 [:menus (:curr-id %1)] (f (curr-menu %1)))))

(defn title! [title] (swap!-curr #(assoc %1 :title title)))

(defn col! [] (swap!-curr #(update %1 :cols conj (count (:entries %1)))))

(defn add-entry! [& entry] (swap!-curr #(update %1 :entries conj entry)))

(defn cmd! [keyspec desc exe] (add-entry! :cmd keyspec desc exe))

(defn sub-begin! [keyspec desc]
  (let [id (:next-menu-id @st)]
    (add-entry! :sub keyspec desc id)
    (swap! st #(-> %1
                   (assoc-in [:menus id :parent-id] (:curr-id %1))
                   (assoc :curr-id id)
                   (update :next-menu-id inc)))))

(defn sub-done! []
  (swap! st #(assoc %1 :curr-id (:parent-id (curr-menu %1)))))

(def Sub ^:sci/macro
  (fn [_&form _&env keyspec desc & sub-body]
    `(do
       (~(symbol "sub-begin!") ~keyspec ~desc)
       ~@sub-body
       (~(symbol "sub-done!")))))

(def Cmd ^:sci/macro
  (fn [_&form _&env keyspec desc exe]
    `(~(symbol "cmd!") ~keyspec ~desc ~exe)))

(def Col ^:sci/macro
  (fn [_&form _&env]
    `(~(symbol "col!"))))

(def Title ^:sci/macro
  (fn [_&form _&env title]
    `(~(symbol "title!") ~title)))

(defn eval-file [filename]
  (let [file-content (try (slurp filename) (catch Exception _ "(-> nil)"))
        code (if-not (str/starts-with? file-content "#!") file-content
               (->> file-content str/split-lines (drop 1) (str/join \newline)))]
    (sci/eval-string (str "(do " code " )")
                     {:bindings {'title! title!
                                 'col! col!
                                 'cmd! cmd!
                                 'sub-begin! sub-begin!
                                 'sub-done! sub-done!
                                 'Title Title
                                 'Col Col
                                 'Cmd Cmd
                                 'Sub Sub}})
    (println @st)))

(defn -main [& args]
  (doseq [in-file args]
    (eval-file in-file)))
