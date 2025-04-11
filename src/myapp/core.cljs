(ns myapp.core
 (:require
  [reagent.core :as r]
  [reagent.dom :as rdom]
  ["markdown-it" :as MarkdownIt]
  [taoensso.sente :as sente]
  [taoensso.sente.packers.transit :as sente-transit]))

(def md-parser (MarkdownIt.))

;; --- State ---
(defonce app-state
         (r/atom {:input ""
                  :messages []
                  :streaming? false
                  :active-page :chat ;; <- New
                  :settings {:url "http://localhost:11434"
                             :model "llama3.2"
                             :system-prompt ""}}))

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
                             ;; Update the last assistant message once streaming finishes
                             (println (:messages @app-state))
                             ;(swap! app-state update :messages
                             ;       #(conj (butlast %) {:role :assistant :content (str "Streaming complete!")}))
                             )
                 nil)))

(defonce stop-router! (atom nil))
(defn start-router! []
      (reset! stop-router!
              (sente/start-client-chsk-router! ch-chsk handle-message!)))

(defonce scroll-ref (atom nil))
(defn scroll-to-bottom! []
      (when-let [el @scroll-ref]
                (set! (.-scrollTop el) (.-scrollHeight el))))

(add-watch app-state :scroll
           (fn [_ _ _ _] (js/setTimeout scroll-to-bottom! 50)))

(defn send-prompt! []
      (let [{:keys [url model system-prompt]} (:settings @app-state)
            input (:input @app-state)]
           (when-not (clojure.string/blank? input)
                     ;; Optimistically update UI state first
                     (swap! app-state update :messages
                            (fn [msgs] (conj msgs
                                             {:role :user :content input}
                                             {:role :assistant :content ""})))
                     (swap! app-state assoc :input "" :streaming? true)

                     ;; Build message list to send, with optional system prompt
                     (let [messages (:messages @app-state)
                           messages-to-send (if (and (<= (count messages) 2) ;; Only user + assistant
                                                     (not (clojure.string/blank? system-prompt)))
                                             (into [{:role :system :content system-prompt}] messages)
                                             messages)]
                          ;; Send to backend
                          (chsk-send!
                           [:chat/start
                            {:url url
                             :model model
                             :messages messages-to-send}])))))


(defn clear-chat! []
      (swap! app-state assoc :messages []))


(defn nav-bar []
      [:div.buttons
       [:button.button.is-link
        {:on-click #(swap! app-state assoc :active-page :chat)}
        "Chat"]
       [:button.button.is-link.is-light
        {:on-click #(swap! app-state assoc :active-page :settings)}
        "Settings"]])

(defn settings-page []
      (let [{:keys [url model system-prompt]} (:settings @app-state)]
           [:div
            [:h2.title "Settings"]
            [:div.field
             [:label.label "API URL"]
             [:div.control
              [:input.input
               {:type "text"
                :value url
                :on-change #(swap! app-state assoc-in [:settings :url] (.. % -target -value))}]]]

            [:div.field
             [:label.label "Model"]
             [:div.control
              [:input.input
               {:type "text"
                :value model
                :on-change #(swap! app-state assoc-in [:settings :model] (.. % -target -value))}]]]

            [:div.field
             [:label.label "System Prompt"]
             [:div.control
              [:textarea.textarea
               {:value system-prompt
                :placeholder "Optional system-level prompt for the assistant"
                :on-change #(swap! app-state assoc-in [:settings :system-prompt] (.. % -target -value))}]]]]))

(defn message-bubble [{:keys [role content]}]
      (let [html (.render md-parser content)]
           (r/create-element
            "div"
            #js {:className (str "message "
                                 (case role
                                       :user "message-user"
                                       :assistant "message-assistant"))
                 :style #js {:alignSelf (if (= role :user) "flex-end" "flex-start")}
                 :dangerouslySetInnerHTML #js {:__html html}})))


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
              "Send"]
             [:button.button.is-danger {:on-click clear-chat!} "Clear"]
             ]]))

(defn app []
      (r/create-class
       {:component-did-mount start-router!
        :reagent-render
        (fn []
            [:div
             [:h1.title "Pyjama GPT"]
             [nav-bar]
             (case (:active-page @app-state)
                   :chat
                   [:<>
                    [:div#chat-box.chat-container
                     {:ref #(reset! scroll-ref %)}
                     (for [[i msg] (map-indexed vector (:messages @app-state))]
                          ^{:key i} [message-bubble msg])]
                    (when (:streaming? @app-state)
                          [:p.has-text-grey "Assistant is typing..."])
                    [input-box]
                    ] ;; Clear chat button

                   :settings
                   [settings-page])])}))

(defn ^:export init []
      (rdom/render [app] (.getElementById js/document "app")))
