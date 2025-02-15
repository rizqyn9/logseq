(ns electron.url
  (:require [electron.handler :as handler]
            [electron.state :as state]
            [electron.utils :refer [send-to-renderer]]
            [clojure.string :as string]
            [promesa.core :as p]))

;; Keep same as main/frontend.util.url
(def decode js/decodeURI)
(def decode-param js/decodeURIComponent)

(defn get-URL-decoded-params
  "Get decoded URL parameters from parsed js/URL.
   `nil` for non-existing keys."
  [^js parsed-url keys]
  (let [params (.-searchParams parsed-url)]
    (map (fn [key]
           (when-let [value (.get params key)]
             (decode-param value)))
         keys)))

(defn graph-identifier-error-handler
  [graph-identifier]
  (if graph-identifier
    (send-to-renderer "notification" {:type "error"
                                      :payload (str "Failed to open link. Cannot match graph identifier `" graph-identifier "` to any linked graph.")})
    (send-to-renderer "notification" {:type "error"
                                      :payload (str "Failed to open link. Missing graph identifier after `logseq://graph/`.")})))

(defn local-url-handler
  "Given a URL with `graph identifier` as path, `page` (optional) and `block-id` 
   (optional) as parameters, open the local graphs accordingly.
   `graph identifier` is the name of the graph to open, e.g. `lambda`"
  [^js win parsed-url]
  (let [graph-identifier (decode (string/replace (.-pathname parsed-url) "/" ""))
        [page-name block-id] (get-URL-decoded-params parsed-url ["page" "block-id"])
        graph-name (when graph-identifier (handler/get-graph-name graph-identifier))]
    (if graph-name
      (p/let [_ (handler/broadcast-persist-graph! graph-name)]
          ;; TODO: call open new window on new graph without renderer (remove the reliance on local storage)
          ;; TODO: allow open new window on specific page, without waiting for `graph ready` ipc then redirect to that page
        (when (or page-name block-id)
          (let [then-f (fn [win' graph-name']
                         (when (= graph-name graph-name')
                           (send-to-renderer win' "redirectWhenExists" {:page-name page-name
                                                                        :block-id block-id})))]
            (state/set-state! :window/once-graph-ready then-f)))
        (send-to-renderer win "openNewWindowOfGraph" graph-name))
      (graph-identifier-error-handler graph-identifier))))

(defn logseq-url-handler
  [^js win parsed-url]
  (let [url-host (.-host parsed-url)] ;; return "" when no pathname provided
    (cond
      (= "auth-callback" url-host)
      (send-to-renderer win "loginCallback" (.get (.-searchParams parsed-url) "code"))

      ;; identifier of graph in local
      (= "graph" url-host)
      (local-url-handler win parsed-url)

      :else
      (send-to-renderer "notification" {:type "error"
                                        :payload (str "Failed to open link. Cannot match `" url-host
                                                      "` to any target.")}))))