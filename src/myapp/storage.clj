(ns myapp.storage
 (:require [clojure.edn :as edn]
           [clojure.java.io :as io]
           [clojure.string :as str])
 (:import (java.io PushbackReader)))

(defn sanitize-filename [s]
 (-> s
     (str/replace #"[^a-zA-Z0-9-_]" "_")
     (str/trim)
     (subs 0 (min 40 (count s)))))                          ;; Limit to 40 chars

(defn save-chat! [session-id state]
 (when-let [user-message (some #(when (= (:role %) :user) (:content %)) (:messages state))]
  (let [filename (str (sanitize-filename user-message) ".edn")
        home (System/getProperty "user.home")
        ;folder   (io/file home ".breeze" (str session-id))
        folder (io/file home ".breeze")
        ]
   (.mkdirs folder)
   (spit (io/file folder filename)
         (pr-str state)))))


(defn get-breeze-dir []
 (str (System/getProperty "user.home") "/.breeze"))

; TODO duplicates
(defn get-session-path [filename]
 (let [home (System/getProperty "user.home")
       file (io/file home ".breeze" (str filename ".edn"))]
  file))

(defn- load-session-content [file]
 (with-open [r (io/reader file)]
  (edn/read (PushbackReader. r))))

(defn load-session-by-filename [filename]
 (let [file (get-session-path filename)]
  (when (.exists file)
   (load-session-content file))))

(defn remove-session[filename]
 (println "delete " filename)
 (.delete (get-session-path filename)))

(defn load-saved-sessions []
 (let [dir (io/as-file (get-breeze-dir))]
  (->> (.listFiles dir)
       (filter #(and (.isFile %)
                     (.endsWith (.getName %) ".edn")))
       (sort-by #(.lastModified %) >)
       (mapv (fn [f]
              {:filename      (subs (.getName f) 0 (- (count (.getName f)) 4))
               ; a bit heavy
               :model (:model (load-session-content f))
               :last-modified (.lastModified f)})))))