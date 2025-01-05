(ns zkm.core
 (:require [clojure.string :as str]
           [clojure.core.async :refer [go <! >! >!! <!! chan]]
           [clojure.java.io :as io]
           [sci.core :as sci]
           [babashka.process :as p :refer [process destroy-tree]]
           [cheshire.core :as json]
           [zkm.bins :as bins])
 (:import (com.sun.jna.platform.unix X11))
 (:gen-class))

(def theme
  {:bg          0xdd191724 :bg-full     0xff191724 :fg          0xffe0def4
   :fg-alt      0xffe0def4 :bg-alt      0xff403d52 :black       0xff2623aa
   :grey        0xff6e6a86 :grey-dim    0xaa6e6a86 :red         0xffeb6f92
   :green       0xff31748f :yellow      0xfff6c177 :yellow-dim  0xccf6c177
   :blue        0xff9ccfd8 :blue-dim    0xcc9ccfd8 :magenta     0xffc4a7e7
   :cyan-dim    0xccebbcba :cyan        0xffebbcba})

(def magenta (:magenta theme))
(def grey (:grey theme))
(def grey-dim (:grey-dim theme))
(def blue (:blue theme))
(def blue-dim (:blue-dim theme))
(def cyan (:cyan theme))
(def cyan-dim (:cyan-dim theme))
(def yellow (:yellow theme))
(def yellow-dim (:yellow-dim theme))

(def root-id 1)

(def st (atom {:next-menu-id (inc root-id) :curr-id root-id}))

(defn curr-menu [& args]
  (let [{:keys [menus curr-id]} (or (first args) @st)]
    (-> (get menus curr-id)
        (update :entries #(or %1 []))
        (update :cols #(or %1 [])))))

(defn swap!-curr [f]
  (swap! st #(assoc-in %1 [:menus (:curr-id %1)] (f (curr-menu %1)))))

(defn title! [title] (swap!-curr #(assoc %1 :title title)))

(defn col! [] (swap!-curr #(update %1 :cols conj (count (:entries %1)))))

(defn add-entry! [etype keyspec desc & args]
  (swap!-curr #(->> (apply vector etype keyspec desc args)
                    (update %1 :entries conj))))

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
                                 'Sub Sub}})))

(def log-chan (chan))

(defn log [& args] (go (>! log-chan args)))

(defn to-zkg-sym [el]
  (cond
    (vector? el) (as-> (str/join " " (map to-zkg-sym (last el))) x
                   (str "(" (name (first el)) " " x ")" ))
    (keyword? el) (str ":" (name el))
    :else (json/generate-string el)))

(defn Cols [& args] [:Cols args])
(defn Rows [& args] [:Rows args])
(defn Mkup [& args] [:Mkup args])
(defn Hr [& args] [:Hr args])

(def ^:dynamic *sys* {})

(defn -menus [] (:menus *sys*))
(defn -active-id [] (:active-id *sys*))
(defn -menu ([id] (get (-menus) id)) ([] (get (-menus) (-active-id))))
(defn -root? [] (= root-id (-active-id)))

(defn render-title []
  (->> (loop [active? true
              curr '()
              {:keys [title parent-id]} (-menu)]
         (let [fs (if active? :bold :normal)
               fg (if active? magenta grey)
               next (conj curr (Mkup :font-style fs :fg fg title))]
           (if-let [parent (-menu parent-id)] (recur false next parent) next)))
       (apply Mkup)))

(defn render-cols []
  (let [{:keys [entries cols]} (-menu)]
    (->> (loop [i 0
                [[etype kspec raw-desc] & erest] entries
                [nextc & crest :as curr-cols] cols
                col-index 0
                outs [[]]]
           (let [newcol? (= i nextc)
                 next-cols (if newcol? crest curr-cols)
                 next-col-index (if newcol? (inc col-index) col-index)
                 cmd? (= etype :cmd)
                 desc-color (if cmd? blue yellow)
                 base-desc ((if (vector? raw-desc) #(str/join " " %1) identity)
                             raw-desc)
                 desc (str (if cmd? "" "+") base-desc)
                 entry {:k (Mkup :fg cyan (str kspec))
                        :s (Mkup :fg grey "  ")
                        :d (Mkup :fg desc-color desc)}
                 next-outs (-> (if newcol? (conj outs []) outs)
                               (update next-col-index conj entry))]
             (if (empty? erest) next-outs
               (recur (inc i) erest next-cols next-col-index next-outs))))
         (map #(Cols (apply Rows :fill-contents :start (map :k %1))
                     (apply Rows (map :s %1))
                     (apply Rows :fill-contents :end (map :d %1))))

         (interpose (Cols "  "))
         reverse
         (#(conj %1 (Cols " ")))
         reverse
         (#(conj %1 (Cols " ")))
         (into []))))

(defn render []
  (Rows :fg (:fg theme)
    (Mkup :fill :center (render-title))
    (Hr :fg grey-dim)
    (apply Cols :fill :around :fill-contents :end (render-cols))
    (Hr :fg grey-dim)
    (Cols :fill :between
          (Mkup :fg grey (Mkup :fg cyan-dim " Esc") " to quit")
          (Mkup "  active  ")
          "            ")))

(defn handle [sys e]
  (case (:etype e)
    :kill nil
    sys))

(defn -main [& args]
  (println (.XStringToKeysym X11/INSTANCE "F1"))
  (go (while true (apply println (<! log-chan))))
  (let [event-chan (chan)
        done-chan (chan)]
    #_(go (let [zkg-proc (process bins/zkg)]
          (go (try
                (with-open [rdr (io/reader (:out zkg-proc))]
                  (binding [*in* rdr]
                    (loop []
                      (when-let [l (try (read-line) (catch Exception _ false))]
                        (let [[ks' etype' mods'] (str/split l #" ")
                              ks (sci/eval-string ks')
                              etype (sci/eval-string (str ":" etype'))
                              mods (sci/eval-string mods')]
                          (>! event-chan {:ks ks :etype etype :mods mods}))
                        (recur)))))
                (catch Exception _ nil)))
          (go (let [data (try (deref zkg-proc) (catch Exception e {:err e}))]
                (>! event-chan (merge data {:etype :kill}))))
          (<! done-chan)
          (destroy-tree zkg-proc)))
    (doseq [in-file args] (eval-file in-file))
    (println "preprocessing done. entering event loop")
    (println args)
    #_(let [ztr-proc (process bins/ztr)
          ztr-in (io/writer (:in ztr-proc))]
      (binding [*out* ztr-in]
        (loop [sys {:menus (:menus @st) :active-id root-id}]
          (let [el (binding [*sys* sys] (render))]
            (println (str "(Render " (to-zkg-sym el) ")"))
            (when-let [next-sys (handle sys (<!! event-chan))]
              (recur next-sys)))))
      (destroy-tree ztr-proc)
      (>!! done-chan true))))
