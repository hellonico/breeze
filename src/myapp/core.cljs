(ns myapp.core
 (:require
  [reagent.core :as r]
  [reagent.dom :as rdom]
  ["markdown-it" :as MarkdownIt]
  [taoensso.sente :as sente]
  [taoensso.sente.packers.transit :as sente-transit]))

(def md-parser (MarkdownIt. #js {:breaks true}))

;; --- State ---
(defonce app-state
         (r/atom {:input            ""
                  :messages         []
                  :streaming?       false
                  :active-page      :chat
                  :models           []
                  :settings         {:url           "http://localhost:11434"
                                     :model         "llama3.2:latest"
                                     ;:layout :chat-bubbles
                                     :layout        :marked-map
                                     :group         ""
                                     :system-prompt ""}
                  :sessions         {}
                  :selected-session ""
                  }))

;; --- WebSocket Setup ---
(let [{:keys [ch-recv send-fn state] :as chsk}
      (sente/make-channel-socket-client!
       "/chsk"
       {:type   :auto
        :packer (sente-transit/get-transit-packer)})]

 (def chsk-send! send-fn)
 (def ch-chsk ch-recv)
 (def chsk-state state))

(defn load-session! [filename]
 (chsk-send! [:sessions/load filename]))

(defn fetch-sessions! []
 (chsk-send! [:sessions/request nil]))

(defn handle-token [token]
 (swap! app-state update-in [:messages (dec (count (:messages @app-state))) :content] str token))

(defn handle-message! [{:keys [event]}]
 (let [[id data] event]
  (case id
   :models/list-result
   (swap! app-state assoc :models data)

   :sessions/list
   (swap! app-state assoc :sessions data)

   :sessions/load
   (let [raw-messages (:messages data)
         filtered-messages (remove #(clojure.string/blank? (:content %)) raw-messages)
         system-msg (some #(when (= (:role %) :system) %) filtered-messages)
         user-messages (remove #(= (:role %) :system) filtered-messages)
         ]

    (swap! app-state assoc
           :messages user-messages
           :settings (-> (:settings @app-state)
                         ; TODO: maybe no need to do one by one
                         (assoc :model (:model data))
                         (assoc :layout (or (:layout data) (-> @app-state :settings :layout)))
                         (assoc :group (:group data))
                         (assoc :system-prompt (:content system-msg)))
           :active-page :chat
           :streaming? false
           :input ""))

   :chat/token
   (handle-token data)

   :chat/done
   (swap! app-state assoc :streaming? false)

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
          (fn [msgs] (into (vec msgs)
                           [{:role :user :content input}
                            {:role :assistant :content ""}])))
   (swap! app-state assoc :input "" :streaming? true)

   ;; Build message list to send, with optional system prompt
   (let [messages (:messages @app-state)
         messages-to-send (if (and (<= (count messages) 2)  ;; Only user + assistant
                                   (not (clojure.string/blank? system-prompt)))
                           (into [{:role :user :content system-prompt}] messages)
                           ;(into [{:role :system :content system-prompt}] messages)
                           messages)]
    ;; Send to backend
    (chsk-send!
     [:chat/start
      {:url      url
       :model    model
       :messages messages-to-send}])))))


(defn clear-chat! []
 (swap! app-state assoc :messages []))

(defn load-models []
 (let [url (get-in @app-state [:settings :url])]
  (chsk-send! [:models/list {:url url}])))

(defn nav-bar []
 [:div.tabs.is-toggle
  [:ul
   [:li {:class (when (= :chat (:active-page @app-state)) "is-active")}
    [:a {:on-click #(swap! app-state assoc :active-page :chat)} "Chat"]]
   [:li {:class (when (= :settings (:active-page @app-state)) "is-active")}
    [:a {:on-click #((swap! app-state assoc :active-page :settings)
                     (load-models)
                     )} "Settings"]]

   [:li {:class (when (= :sessions (:active-page @app-state)) "is-active")}
    [:a {:on-click #(do
                     (swap! app-state assoc :active-page :sessions)
                     (fetch-sessions!))} "Sessions"]]]])

(defn remove-session [filename]
 (when (js/confirm (str "Delete session '" filename "'?"))
  (chsk-send! [:sessions/remove filename])
  (swap! app-state update :sessions (fn [sessions] (remove #(= (:filename %) filename) sessions)))))

(defn sessions-page []
 [:div
  [:h2.title "Saved Sessions"]
  [:div.sessions-table
   [:table.table.is-fullwidth.is-hoverable
    [:thead
     [:tr
      [:th "Session"]
      [:th "Model"]
      [:th "Date"]
      [:th ""]]]
    [:tbody
     (for [{:keys [filename last-modified model]} (:sessions @app-state)]
      ^{:key filename}
      [:tr {:on-double-click #(do (load-session! filename))}
       [:td (clojure.string/replace filename "_" " ")]
       [:td (str model)]
       [:td (.toLocaleString (js/Date. last-modified))]
       [:td [:button.button.is-danger.is-small
             {:on-click #(remove-session filename)}
             [:span.icon
              [:i.fas.fa-trash]]]]])
     ]]]])


(defn settings-page []
 (let [models (:models @app-state)
       {:keys [url model system-prompt]} (:settings @app-state)]
  [:div
   [:h2.title "Settings"]

   [:div.field
    [:label.label "Layout"]
    [:div.control
     [:div.select
      [:select
       {:value     (name (:layout (:settings @app-state)))
        :on-change #(swap! app-state assoc-in [:settings :layout]
                           (keyword (.. % -target -value)))}
       [:option {:value "chat-bubbles"} "Chat bubbles"]
       [:option {:value "left-right"} "Left/Right (All Messages)"]
       [:option {:value "last-message"} "Left/Right (Last Message Only)"]
       [:option {:value "marked-map"} "MindMap"]
       ]]]]

   [:div.field
    [:label.label "API URL"]
    [:div.control
     [:input.input
      {:type      "text"
       :value     url
       :on-change #(when-let [url (.. % -target -value)] (swap! app-state assoc-in [:settings :url] url) (load-models))}]]]


   [:div.field
    [:label.label "Model"]
    [:div.control
     [:select.select
      {:value     model
       :on-change #(swap! app-state assoc-in [:settings :model] (.. % -target -value))}
      (for [m models]
       ^{:key m}
       [:option {:value m} m])]]]

   [:div.field
    [:label.label "System Prompt"]
    [:div.control
     [:textarea.textarea
      {:value       system-prompt
       :placeholder "Optional system-level prompt for the assistant"
       :on-change   #(swap! app-state assoc-in [:settings :system-prompt] (.. % -target -value))}]]]]))



(defn message-bubble [{:keys [role content]} index]
 (let [hovered? (r/atom false)
       delete-from-here #(swap! app-state update :messages
                                (fn [msgs]
                                 (vec (subvec (vec msgs) 0 index))))]
  (fn [{:keys [role content index]}]
   (let [html (.render md-parser content)
         copy-to-clipboard #(js/navigator.clipboard.writeText (if (= role :user) content html))
         ]
    [:div.message-wrapper
     {:on-mouse-enter #(reset! hovered? true)
      :on-mouse-leave #(reset! hovered? false)
      :style          {:display        "flex"
                       :flex-direction "column"
                       :align-items    (if (= role :user) "flex-end" "flex-start")
                       :position       "relative"}}

     ;; Copy + Delete icons
     (when @hovered?
      [:div
       {:style {:position  "absolute"
                :top       "4px"
                :right     "8px"
                :display   "flex"
                :gap       "0.5em"
                :font-size "0.8em"
                :cursor    "pointer"}}
       [:span
        {:on-click copy-to-clipboard
         :title    "Copy to clipboard"}
        "📋"]
       [:span
        {:on-click delete-from-here
         :title    "Delete from here"}
        "🗑"]])

     ;; Message bubble
     (r/create-element
      "div"
      #js {:className               (str "message "
                                         (case role
                                          :user "message-user"
                                          :assistant "message-assistant"))
           :style                   #js {:maxWidth  "100%"
                                         :alignSelf (if (= role :user) "flex-end" "flex-start")}
           :dangerouslySetInnerHTML #js {:__html html}})]))))



(defn input-box []
 (let [value (:input @app-state)
       streaming? (:streaming? @app-state)]
  [:div.input-container
   {:style {:position "relative"}}
   [:div.input-wrapper
    {:style          {:display        "flex"
                      :flex-direction "column"
                      :position       "relative"}
     :on-mouse-enter #(swap! app-state assoc :show-buttons? true)
     :on-mouse-leave #(swap! app-state assoc :show-buttons? false)
     }
    [:textarea.textarea
     {:placeholder "Type a message..."
      :value       value
      :rows        1
      :style       {:resize     "none"
                    :overflow   "hidden"
                    :min-height "2.5em"
                    :max-height "20em"}
      :on-change   #(let [new-val (.. % -target -value)]
                     (swap! app-state assoc :input new-val)
                     (let [el (.-target %)
                           style (.-style el)
                           scroll-height (.-scrollHeight el)]
                      (set! (.-height style) (str scroll-height "px"))
                      (set! (.-height style) "auto"))

                     ;(let [el (.. % -target)]
                     ;     (set! (.-style.height el) "auto")
                     ;     (set! (.-style.height el) (str (.-scrollHeight el) "px")))
                     )
      :on-key-down
      (fn [e]
       (let [enter? (= "Enter" (.-key e))
             shift? (.-shiftKey e)]
        (when (and enter? shift?)
         (.preventDefault e)
         (send-prompt!))))}]
    (when (:show-buttons? @app-state)
     [:div.buttons-container
      {:style {:margin-top "0.3em"
               :display    "flex"
               :gap        "0.5em"}}
      [:button.button.is-link {:on-click send-prompt!
                               :disabled streaming?}
       "Send"]
      [:button.button.is-danger {:on-click clear-chat!} "Clear"]])]]))

(defn render-bubble-layout []
 [:<>
  [:div#chat-box.chat-container
   {:ref #(reset! scroll-ref %)}
   (for [[i msg] (map-indexed vector (:messages @app-state))]
    ^{:key i} [message-bubble msg i])]
  (when (:streaming? @app-state)
   [:p.has-text-grey "Assistant is typing..."])
  [input-box]
  ]                                                         ;; Clear chat button

 )

(defn render-left-right-layout []
 (let [messages (:messages @app-state)]
  [:div.chat-split
   {:style {:display "flex" :height "100%"}}


   ;; Left: user messages
   [:div.left-pane
    {:style {:width   "30%" :overflow "auto"
             :padding "1em" :borderRight "1px solid #ccc"}}
    [input-box]
    (for [{:keys [role] :as msg} (reverse messages)
          :when (= role :user)]
     ^{:key (hash msg)} [message-bubble msg])

    ]

   ;; Right: assistant messages
   [:div.right-pane
    {:style {:flex "1" :overflow "auto" :padding "1em"}}
    (when (:streaming? @app-state)
     [:p.has-text-grey "Assistant is typing..."])
    (for [{:keys [role] :as msg} (reverse messages)
          :when (= role :assistant)]
     ^{:key (hash msg)} [message-bubble msg])]]))

(defn render-last-message-layout [messages]
 (let [last-user (last (filter #(= (:role %) :user) messages))
       last-assistant (last (filter #(= (:role %) :assistant) messages))]
  [:div.chat-split
   {:style {:display "flex" :height "100%"}}


   ;; Left: latest user message + input
   [:div.left-pane
    {:style {:width   "30%" :overflow "auto"
             :padding "1em" :borderRight "1px solid #ccc"
             :display "flex" :flexDirection "column"}}
    [input-box]

    (when last-user
     [message-bubble last-user])
    ;; You’ll want your normal input/send/clear bar here:
    ]

   ;; Right: latest assistant message
   [:div.right-pane
    {:style {:flex "1" :overflow "auto" :padding "1em"}}
    (when (:streaming? @app-state)
     [:p.has-text-grey "Assistant is typing..."])

    (when last-assistant
     [message-bubble last-assistant])]]))

(defn mindmap-view [markdown-text]
 (let [_ (println ">>>" markdown-text)]
  [:div
   {:style {:width "100%" :height "100%"}
    :class "markmap"
    :dangerouslySetInnerHTML
    {:__html markdown-text}}]))

(defn render-markmap-layout [messages]
 (let [;messages (:messages @app-state)
       last-user (last (filter #(= (:role %) :user) messages))
       last-assistant (last (filter #(and (= (:role %) :assistant)
                                          (seq (:content %)))
                                    messages))

       map-content (:content last-assistant)
       ]
  (when (exists? (.-autoLoader js/markmap))
   (.renderAll (.-autoLoader js/markmap)))
  [:div.chat-split
   {:style {:display "flex" :height "100%"}}


   ;; Left: latest user message + input
   [:div.left-pane
   ;[:div
    {:style {:width   "30%" :overflow "auto"
             :padding "1em" :borderRight "1px solid #ccc"
             :display "flex" :flexDirection "column"}}
    [input-box]

    (when last-user
     [message-bubble last-user])

    ; TODO: add this somewhere
    ;(when (:streaming? @app-state)
    ; [:p.has-text-grey "Assistant is typing..."])
    ]

   ;; Right: latest assistant message
   [:div.right-pane
    {:style {:flex "1" :overflow "auto" :padding "1em"}}

    (mindmap-view map-content)
    ]]))

(def messages
 (r/reaction
  (:messages @app-state)))

(def active-page
 (r/reaction
  (:active-page @app-state)))
(def layout
 (r/reaction
  (-> @app-state :settings :layout)))

(defn app []
 (r/create-class
  {:component-did-mount start-router!
   :reagent-render
   (fn []
    [:div
     [:h1.title "Pyjama GPT"]
     [nav-bar]
     (case @active-page

      :chat

      (case @layout
       :chat-bubbles (render-bubble-layout)
       :left-right (render-left-right-layout)
       :last-message (render-last-message-layout @messages)
       :marked-map (render-markmap-layout @messages)
       )

      :sessions [sessions-page]

      :settings
      [settings-page])])}))

(defn ^:export init []
 (rdom/render [app] (.getElementById js/document "app")))
