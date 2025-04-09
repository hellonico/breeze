(ns myapp.core
 (:require
  [org.httpkit.server :as httpkit]
  [compojure.core :refer [GET POST defroutes]]
  [compojure.route :as route]
  [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
  [ring.middleware.params :refer [wrap-params]]
  [ring.middleware.keyword-params :refer [wrap-keyword-params]]
  [pyjama.state :as p]
  [ring.middleware.cors :refer [wrap-cors]]
  [taoensso.sente :as sente]
  [taoensso.sente.server-adapters.http-kit :refer [get-sch-adapter]]
  [clojure.core.async :as async :refer [<! >! go-loop]])
 (:gen-class))

(defn user-id-fn [ring-req]
 ;; You can generate a random UUID or use session ID
 (get-in ring-req [:session :uid]))

;; Setup Sente
(let [{:keys [ch-recv send-fn connected-uids ajax-post-fn ajax-get-or-ws-handshake-fn]}
      (sente/make-channel-socket-server!
       (get-sch-adapter)
       {:packer :edn
        :user-id-fn user-id-fn
        :csrf-token-fn nil})]
 (def ring-ajax-post ajax-post-fn)
 (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
 (def ch-chsk ch-recv)
 (def chsk-send! send-fn)
 (def connected-uids connected-uids))

(defn handle-msg! [{:keys [id ?data uid]}]
 (when (= id :chat/start)
  (let [state (atom (assoc ?data :processing true))
        client-chan (fn [_ text] (chsk-send! uid [:chat/token text]))]
   (p/ollama-chat state (partial client-chan nil))
   ;; Wait in background until done, then notify client
   (future
    (loop []
     (if (:processing @state)
      (do (Thread/sleep 500) (recur))
      ;; Processing done, notify client
      (chsk-send! uid [:chat/done nil])))))))

;; Message router
(defn start-router! []
 (go-loop []
  (when-let [msg (<! ch-chsk)]
   (handle-msg! msg)
   (recur))))

;; Routes
(defroutes
 app-routes
 (GET "/chsk" req (ring-ajax-get-or-ws-handshake req))
 (POST "/chsk" req (ring-ajax-post req))
 (route/files "/")
 (route/not-found "Not Found"))

(defn wrap-user-id [handler]
 (fn [req]
  (let [uid (or (get-in req [:session :uid])
                (str (random-uuid)))
        req' (assoc-in req [:session :uid] uid)]
   (handler req'))))


(def app
 (-> app-routes
     wrap-keyword-params
     wrap-user-id
     wrap-params
     (wrap-cors :access-control-allow-origin [#"http://localhost:3000"])  ; adjust if needed
     (wrap-defaults site-defaults)))

(defonce server (atom nil))
(defn start-server []
 (reset! server (httpkit/run-server app {:port 3000}))
 (start-router!)
 (println "Server running at http://localhost:3000"))

(defn -main [] (start-server))