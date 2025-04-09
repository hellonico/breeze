(ns myapp.core
 (:require
  [reagent.core :as r]
  [reagent.dom :as rdom]
  [taoensso.sente :as sente]
  [taoensso.sente.packers.transit :as sente-transit]))

;; --- State ---
(defonce app-state (r/atom {:messages   []
                            :input      ""
                            :streaming? false}))

;; --- WebSocket Setup ---
(let [{:keys [ch-recv send-fn state] :as chsk}
      (sente/make-channel-socket-client!
       "/chsk"
       {:type   :auto
        :packer (sente-transit/get-transit-packer)})]

     (def chsk-send! send-fn)
     (def ch-chsk ch-recv)
     (def chsk-state state))

(defn handle-token [token]
      (swap! app-state update-in [:messages (dec (count (:messages @app-state))) :content] str token))

(defn handle-message! [{:keys [event]}]
      (let [[id data] event]
           (case id
                 :chat/token (handle-token data)
                 :chat/done (do
                             (swap! app-state assoc :streaming? false)
                             )
                 nil)))

(defonce stop-router! (atom nil))
(defn start-router! []
      (reset! stop-router!
              (sente/start-client-chsk-router! ch-chsk handle-message!)))

;; --- Scroll to bottom on update ---
(defonce scroll-ref (atom nil))
(defn scroll-to-bottom! []
      (when-let [el @scroll-ref]
                (set! (.-scrollTop el) (.-scrollHeight el))))

(add-watch app-state :scroll
           (fn [_ _ _ _] (js/setTimeout scroll-to-bottom! 50)))

(defn send-prompt! []
      (let [input (:input @app-state)]
           (when (not (clojure.string/blank? input))
                 ;; Add user message
                 (swap! app-state update :messages conj {:role :user :content input})
                 ;; Add empty assistant message (we'll fill this as streaming progresses)
                 (swap! app-state update :messages conj {:role :assistant :content ""})
                 ;; Clear the input field and set the streaming flag
                 (swap! app-state assoc :input "" :streaming? true)

                 ;; Send the entire list of messages in the chat request
                 (chsk-send!
                  [:chat/start
                   {:url      "http://localhost:11434"
                    :model    "llama3.2"
                    :messages (:messages @app-state)}]))))

;; --- Chat Bubble ---
(defn message-bubble [{:keys [role content]}]
      [:div {:class (str "message "
                         (case role
                               :user "message-user"
                               :assistant "message-assistant"))
             :style {:align-self (if (= role :user) "flex-end" "flex-start")}}
       content])

;; --- Input Box ---
(defn input-box []
      (let [streaming? (:streaming? @app-state)]
           [:div.field.has-addons.mt-4
            [:div.control.is-expanded
             [:input.input {:type        "text"
                            :placeholder "Type a message..."
                            :value       (:input @app-state)
                            :on-change   #(swap! app-state assoc :input (-> % .-target .-value))
                            :on-key-down #(when (and (= (.-key %) "Enter") (not streaming?))
                                                (send-prompt!))
                            :disabled    streaming?}]]
            [:div.control
             [:button.button.is-link {:on-click send-prompt!
                                      :disabled streaming?}
              "Send"]]]))

;; --- Main App Component ---
(defn app []
      (r/create-class
       {:component-did-mount start-router!
        :reagent-render
        (fn []
            [:div
             [:h1.title "Pyjama GPT"]
             [:div#chat-box.chat-container
              {:ref #(reset! scroll-ref %)}
              (for [[i msg] (map-indexed vector (:messages @app-state))]
                   ^{:key i} [message-bubble msg])]
             (when (:streaming? @app-state)
                   [:p.has-text-grey "Assistant is typing..."])
             [input-box]])}))

;; --- Mount ---
(defn ^:export init []
      (rdom/render [app] (.getElementById js/document "app")))
