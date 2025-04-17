(ns myapp.storage
 (:require [clojure.java.io :as io]
           [clojure.edn :as edn]
           [clojure.string :as str]))

(defn sanitize-filename [s]
 (-> s
     (str/replace #"[^a-zA-Z0-9-_]" "_")
     (str/trim)
     (subs 0 (min 40 (count s))))) ;; Limit to 40 chars

(defn save-chat! [session-id state]
 ;(println "saved")
 (when-let [user-message (some #(when (= (:role %) :user) (:content %)) (:messages state))]
  (let [filename (str (sanitize-filename user-message) ".edn")
        home     (System/getProperty "user.home")
        ;folder   (io/file home ".breeze" (str session-id))
        folder   (io/file home ".breeze")
        ]
   (.mkdirs folder)
   (spit (io/file folder filename)
         (pr-str state)))))


(defn get-breeze-dir []
 (str (System/getProperty "user.home") "/.breeze"))

(defn load-saved-sessions []
 (let [dir (io/file (get-breeze-dir))]
  (->> (.listFiles dir)
       (filter #(and (.isFile %) (.getName %)
                     (.endsWith (.getName %) ".edn")))
       (map (fn [f]
             (let [fname (.getName f)
                   base-name (subs fname 0 (- (count fname) 4)) ;; remove ".edn"
                   content (try
                            (with-open [r (io/reader f)]
                             (edn/read (java.io.PushbackReader. r)))
                            (catch Exception e
                             (println "Failed to read" fname ":" (.getMessage e))
                             nil))]
              [base-name content])))
       (remove (comp nil? second)) ;; drop files that failed to load
       (into {}))))