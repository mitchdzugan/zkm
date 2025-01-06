(ns zkm.core
 (:require [clojure.string :as str]
           [clojure.set :as set]
           [clojure.core.async :refer [go <! >! >!! <!! chan]]
           [clojure.java.io :as io]
           [sci.core :as sci]
           [babashka.process :as p :refer [process shell destroy-tree]]
           [cheshire.core :as json]
           [zkm.bins :as bins])
 (:import (java.io BufferedWriter)
          (com.sun.jna.platform.unix X11))
 (:gen-class))

(def log-chan (chan))

(defn log [& args] (go (>! log-chan args)))

(def theme
  {:bg          0xdd191724 :bg-full     0xff191724 :fg          0xffe0def4
   :fg-alt      0xffe0def4 :bg-alt      0xff403d52 :black       0xff2623aa
   :grey        0xff6e6a86 :grey-dim    0xaa6e6a86 :red         0xffeb6f92
   :red-dim     0x66eb6f92 :green       0xff31748f :yellow      0xfff6c177
   :yellow-dim  0x88f6c177 :blue        0xff9ccfd8 :blue-dim    0x889ccfd8
   :magenta     0xffc4a7e7 :magenta-dim 0x99c4a7e7 :cyan-dim    0x88ebbcba
   :cyan        0xffebbcba})

(def magenta (:magenta theme))
(def magenta-dim (:magenta-dim theme))
(def grey (:grey theme))
(def grey-dim (:grey-dim theme))
(def blue (:blue theme))
(def blue-dim (:blue-dim theme))
(def cyan (:cyan theme))
(def cyan-dim (:cyan-dim theme))
(def yellow (:yellow theme))
(def yellow-dim (:yellow-dim theme))
(def red (:red theme))
(def red-dim (:red-dim theme))

;const DISPLAY_NAMES = {
;    left: '',
;    up: '',
;    right: '',
;    down: '',
;    tab: "⭾",
;    return: "⏎",
;    escape: "Esc",
;    backspace: "⌫",
;};

(def DISPLAY_NAMES
  {"left" ""
   "up" ""
   "right" ""
   "down" ""
   "tab" ""})

(def MODS {:c "^" :a "⎇" :s "⇧" :m "❖"})
(def MOD_LIST [:m :c :a :s])

(defn keysym-of [name] (.intValue (.XStringToKeysym X11/INSTANCE name)))

(def shift? #{(keysym-of "Shift_L") (keysym-of "Shift_R")})
(def ctrl? #{(keysym-of "Control_L") (keysym-of "Control_R")})
(def alt? #{(keysym-of "Alt_L") (keysym-of "Alt_R")})
(def meta? #{(keysym-of "Meta_L") (keysym-of "Super_L") (keysym-of "Hyper_L")
             (keysym-of "Meta_R") (keysym-of "Super_R") (keysym-of "Hyper_R")})

(def root-id 1)

(def st (atom {:next-menu-id (inc root-id) :curr-id root-id}))

(defn mk-press-id ([ks] (mk-press-id ks #{})) ([ks mods] {:ks (int ks) :mods mods}))

(defn mk-press-id-by-name
  ([fin] (mk-press-id-by-name fin #{}))
  ([fin mods] (mk-press-id (keysym-of (str fin)) mods)))

(def backspace-press-id (mk-press-id-by-name "BackSpace"))

(defn curr-menu [& args]
  (let [{:keys [menus curr-id]} (or (first args) @st)]
    (-> (get menus curr-id)
        (update :binds #(or %1 {backspace-press-id (fn [_] [:back])}))
        (update :entries #(or %1 []))
        (update :cols #(or %1 [])))))

(defn swap!-curr [f]
  (swap! st #(assoc-in %1 [:menus (:curr-id %1)] (f (curr-menu %1)))))

(defn title! [title] (swap!-curr #(assoc %1 :title title)))

(defn col! [] (swap!-curr #(update %1 :cols conj (count (:entries %1)))))

(defn single? [c] (= 1 (count c)))

(def finkey-remaps
  (let [ucase (->> (range 26)
                   (map #(-> [(str (char (+ %1 65)))
                              {:m #{:s} :k (str (char (+ %1 97)))}]))
                   (into {}))
        snums (->> [[")" "parenright"]
                    ["!" "exclam"]
                    ["@" "at"]
                    ["#" "numbersign"]
                    ["$" "dollar"]
                    ["%" "percent"]
                    ["^" "asciicircum"]
                    ["&" "ampersand"]
                    ["*" "asterisk"]
                    ["(" "parenleft"]
                    ]
                   (map-indexed (fn [n strs] {:n n :strs strs}))
                   (mapcat #(map (fn [s] {:n (:n %1) :s s}) (:strs %1)))
                   (map #(-> [(:s %1) {:m #{:s} :k (str (:n %1))}]))
                   (into {}))]
    (merge ucase
           snums
           {" " {:k "space"}
            "." {:k "period"}
            "," {:k "comma"}
            "/" {:k "slash"}
            "left" {:k "Left"}
            "up" {:k "Up"}
            "right" {:k "Right"}
            "down" {:k "Down"}})))

(defn to-key-parts [keyspec]
  (cond
    (keyword? keyspec)      (to-key-parts (name keyspec))
    (number? keyspec)       [nil (str keyspec)]
    (and (vector? keyspec)
         (single? keyspec)) [nil (first keyspec)]
    (vector? keyspec)       [(nth keyspec 0 nil) (nth keyspec 1 nil)]
    :else                   (let [spl (str/split keyspec #":" 2)]
                              (if (single? spl) [nil (first spl)] spl))))

(defn add-entry! [etype keyspec desc & args]
  (let [[modspec finkeyspec] (to-key-parts keyspec)
        {modbase :m fin :k} (get finkey-remaps finkeyspec {:k finkeyspec})
        modvisible (->> modspec
                        (into [])
                        (mapcat #(get {\c [:c] \a [:a] \s [:s] \m [:m]} %1 []))
                        (reduce #(conj %1 %2) #{}))
        mods (set/union modvisible modbase)
        press-id (mk-press-id-by-name fin mods)
        keyspec-display {:modvisible modvisible :mods mods :finkey finkeyspec}]
    (swap!-curr #(-> (->> (apply vector etype keyspec-display desc args)
                          (update %1 :entries conj))
                     (assoc-in [:binds press-id]
                               (fn [entries]
                                 (nth entries (count (:entries %1)))))))))

(defn cmd! [keyspec desc exe] (add-entry! :cmd keyspec desc exe))

(defn sub-begin! [keyspec desc]
  (let [id (:next-menu-id @st)]
    (add-entry! :sub keyspec desc id)
    (swap! st #(-> %1
                   (assoc-in [:menus id :parent-id] (:curr-id %1))
                   (assoc-in [:menus id :title] desc)
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

(defn -menus [sys] (:menus sys))
(defn -curr-id [sys] (:curr-id sys))
(defn -menu
  ([sys id] (get (-menus sys) id))
  ([sys] (get (-menus sys) (-curr-id sys))))
(defn -root? [sys] (= root-id (-curr-id sys)))
(defn -active-mods [sys] (:active-mods sys))
(defn -bound-entry [sys press-id]
  (let [{:keys [entries binds]} (get (:menus sys) (:curr-id sys))]
    ((get binds press-id (fn [_] nil)) entries)))

(defn -render-title [sys]
  (->> (loop [active? true
              curr '()
              {:keys [title parent-id]} (-menu sys)]
         (let [fs (if active? :bold :normal)
               fg (if active? magenta magenta-dim)
               next (conj curr (Mkup :font-style fs :fg fg title))]
           (if-let [parent (-menu sys parent-id)] (recur false next parent) next)))
       (interpose (Mkup :fg grey-dim " > "))
       (apply Mkup)))

(defn entry-type [[etype] & _] etype)

(defmulti entry-color entry-type)
(defmethod entry-color :cmd [_ p?] (if p? blue blue-dim))
(defmethod entry-color :sub [_ p?] (if p? yellow yellow-dim))

(defmulti entry-prefix entry-type)
(defmethod entry-prefix :cmd [_] "")
(defmethod entry-prefix :sub [_] "+")

(defmulti entry-handle entry-type)
(defmethod entry-handle :cmd [[_ _ _ exe] sys] (assoc sys :exe exe))
(defmethod entry-handle :sub [[_ _ _ submenu-id] sys]
  (assoc sys :curr-id submenu-id))
(defmethod entry-handle :back [_ sys]
  (let [curr-id (:curr-id sys)
        curr (get (:menus sys) curr-id)]
    (assoc sys :curr-id
           (get curr :parent-id curr-id))))

(defn -entry-possible? [sys [_ {:keys [mods]}]]
  (let [active-mods (or (-active-mods sys) #{})]
   (every? #(mods %1) active-mods)))

(defn entry-desc-str [[_ _ d]] (if (sequential? d) (str/join " " d) d))

(defn -render-entry-desc [sys entry]
  (let [poss? (-entry-possible? sys entry)]
    (->> (str (entry-prefix entry) (entry-desc-str entry))
         (Mkup :fg (entry-color entry poss?)))))

(defn -render-active-mods [sys]
  (let [active-mods (or (-active-mods sys) #{})]
    (->> MOD_LIST
         (map #(let [a? (active-mods %1)
                     fg (if a? red red-dim)
                     fs (if a? :bold :normal)]
                 (Mkup :fg fg :font-style fs (get MODS %1))))
         (apply Mkup))))


(defn -render-entry-keyspec [sys [_ {:keys [modvisible finkey]} :as entry]]
  (let [active-mods (or (-active-mods sys) #{})
        active? #(active-mods %1)
        poss? (-entry-possible? sys entry)
        finkey-color (if poss? cyan cyan-dim)]
    (Mkup
      (->> MOD_LIST
           (remove #(modvisible %1))
           (map #(Mkup :fg 0x00000000 (get MODS %1)))
           (apply Mkup))
      (->> MOD_LIST
           (filter #(modvisible %1))
           (map #(Mkup :fg (if poss? red red-dim)
                       :font-style (if (and poss? (active? %1)) :bold :normal)
                       (get MODS %1)))
           (apply Mkup))
      (Mkup :fg finkey-color
            (str (get DISPLAY_NAMES finkey finkey))))))

(defn -render-cols [sys]
  (let [{:keys [entries cols]} (-menu sys)]
    (->> (loop [i 0
                [entry & erest] entries
                [nextc & crest :as curr-cols] cols
                col-index 0
                outs [[]]]
           (let [newcol? (= i nextc)
                 next-cols (if newcol? crest curr-cols)
                 next-col-index (if newcol? (inc col-index) col-index)
                 entry {:k (-render-entry-keyspec sys entry)
                        :s (Mkup :fg grey "  ")
                        :d (-render-entry-desc sys entry)}
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

(defn -render [sys]
  (Rows :fg (:fg theme)
    (Mkup :fill :center (-render-title sys))
    (Hr :fg grey-dim)
    (apply Cols :fill :around :fill-contents :end (-render-cols sys))
    (Hr :fg grey-dim)
    (Cols :fill :between
          (Mkup :fg grey (Mkup :fg cyan " Esc") " quit    ")
          (-render-active-mods sys)
          (if (-root? sys)
            "            "
            (Mkup :fg grey (Mkup :fg cyan "⌫") " prev menu")))))

(defn mods-set [mods]
  (->> [{:k :s :n 0x001} {:k :c :n 0x004} {:k :a :n 0x008} {:k :m :n 0x040}]
       (remove #(= 0 (bit-and mods (:n %1))))
       (map :k)
       (into #{})))

(defn adjust-mods-for-current-event [mods ks press?]
  (->> [{:k :s :p shift?} {:k :c :p ctrl?} {:k :a :p alt?} {:k :m :p meta?}]
       (filter #((:p %1) ks))
       (reduce #((if press? conj disj) %1 (:k %2)) mods)))

(defn -handle-press [sys ks raw-mods]
  (let [mods (mods-set raw-mods)
        active-mods (adjust-mods-for-current-event mods ks true)
        sys' (assoc sys :active-mods active-mods)]
    (if-let [bound (-bound-entry sys (mk-press-id ks mods))]
      (entry-handle bound sys')
      sys')))

(defn -handle-release [sys ks raw-mods]
  (let [mods (mods-set raw-mods)]
    (assoc sys :active-mods (adjust-mods-for-current-event mods ks false))))

(defn -handle [sys e]
  (case (:etype e)
    :kill nil
    :press (-handle-press sys (:ks e) (:mods e))
    :release (-handle-release sys (:ks e) (:mods e))
    sys))

(defn -main [& args]
  (let [zkg-proc (process bins/zkg)
        ztr-proc (process bins/ztr)
        force-closed #(try (destroy-tree %1) (catch Exception _ nil))
        force-all-closed (fn [] (force-closed zkg-proc) (force-closed ztr-proc))
        event-chan (chan 10)
        done-chan (chan)
        exe-chan (chan)
        render-chan (chan 10)]
    (go (while true (apply println (<! log-chan))))
    (go (go (try
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
        (let [{exe-user :exe} (<! exe-chan)
              exe (cond (sequential? exe-user) (str/join " " exe-user)
                        :else exe-user)
              shell-opts {:continue true}]
          (force-all-closed)
          (if exe
            (>! done-chan (:exit (shell shell-opts bins/bash "-lc" exe)))
            (>! done-chan 1))))
    (doseq [in-file args] (eval-file in-file))
    (go (let [ztr-in (io/writer (:in ztr-proc))]
          (binding [*out* ztr-in]
            (loop [to-render (<! render-chan)]
              (println to-render)
              (when (not= to-render "(Done)")
                (recur (<! render-chan)))))
          (force-all-closed)))
    (loop [sys {:menus (:menus @st) :curr-id root-id}]
      (let [render-str (str "(Render " (to-zkg-sym (-render sys)) ")")]
        (>!! render-chan render-str)
        (let [next-sys (-handle sys (<!! event-chan))]
          (if (and next-sys (nil? (:exe next-sys)))
            (recur next-sys)
            (go (go (>! render-chan "(Done)"))
                (>! exe-chan {:exe (:exe next-sys)}))))))
    (let [exit-status (<!! done-chan)]
      (force-all-closed)
      (shutdown-agents)
      (System/exit exit-status))))
