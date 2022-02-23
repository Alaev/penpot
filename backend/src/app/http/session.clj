;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http.session
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.config :as cfg]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.util.async :as aa]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

;; A default cookie name for storing the session. We don't allow
;; configure it.
(def cookie-name "auth-token")

;; --- IMPL

(defn- create-session
  [{:keys [conn tokens] :as cfg} {:keys [profile-id headers] :as request}]
  (let [token  (tokens :generate {:iss "authentication"
                                  :iat (dt/now)
                                  :uid profile-id})
        now    (dt/now)
        params {:user-agent (get headers "user-agent")
                :profile-id profile-id
                :created-at now
                :updated-at now
                :id token}]
    (db/insert! conn :http-session params)))

(defn- delete-session
  [{:keys [conn] :as cfg} {:keys [cookies] :as request}]
  (when-let [token (get-in cookies [cookie-name :value])]
    (db/delete! conn :http-session {:id token}))
  nil)

(defn- retrieve-session
  [{:keys [conn] :as cfg} id]
  (when id
    (db/exec-one! conn ["select id, profile_id from http_session where id = ?" id])))

(defn- retrieve-from-request
  [cfg {:keys [cookies] :as request}]
  (->> (get-in cookies [cookie-name :value])
       (retrieve-session cfg)))

(defn- add-cookies
  [response {:keys [id] :as session}]
  (let [cors?   (contains? cfg/flags :cors)
        secure? (contains? cfg/flags :secure-session-cookies)]
    (assoc response :cookies {cookie-name {:path "/"
                                           :http-only true
                                           :value id
                                           :same-site (if cors? :none :lax)
                                           :secure secure?}})))

(defn- clear-cookies
  [response]
  (assoc response :cookies {cookie-name {:value "" :max-age -1}}))

(defn- middleware
  [events-ch store handler]
  (fn [request respond raise]
    (if-let [{:keys [id profile-id] :as session} (retrieve-from-request store request)]
      (do
        (a/>!! (::events-ch cfg) id)
        (l/set-context! {:profile-id profile-id})
        (handler (assoc request :profile-id profile-id :session-id id) respond raise))
      (handler request respond raise))))

;; --- STATE INIT: SESSION

(defmethod ig/pre-init-spec ::session [_]
  (s/keys :req-un [::db/pool]))

(defmethod ig/prep-key ::session
  [_ cfg]
  (d/merge {:buffer-size 128}
           (d/without-nils cfg)))

(defmethod ig/init-key ::session
  [_ {:keys [pool] :as cfg}]
  (let [events (a/chan (a/dropping-buffer (:buffer-size cfg)))
        cfg    (-> cfg
                   (assoc :conn pool)
                   (assoc ::events-ch events))]
    (-> cfg
        (assoc ::events-ch events-ch)
        (assoc :middleware (partial middleware events-ch store))
        (assoc :create (fn [profile-id]
                         (fn [request response]
                           (let [request (assoc request :profile-id profile-id)
                                 session (create-session cfg request)]
                             (add-cookies response session)))))
        (assoc :delete (fn [request response]
                         (delete-session cfg request)
                         (-> response
                             (assoc :status 204)
                             (assoc :body "")
                             (clear-cookies)))))))

(defmethod ig/halt-key! ::session
  [_ data]
  (a/close! (::events-ch data)))


;; --- STATE INIT: SESSION UPDATER

(declare update-sessions)

(s/def ::session map?)
(s/def ::max-batch-age ::cfg/http-session-updater-batch-max-age)
(s/def ::max-batch-size ::cfg/http-session-updater-batch-max-size)

(defmethod ig/pre-init-spec ::updater [_]
  (s/keys :req-un [::db/pool ::wrk/executor ::mtx/metrics ::session]
          :opt-un [::max-batch-age
                   ::max-batch-size]))

(defmethod ig/prep-key ::updater
  [_ cfg]
  (merge {:max-batch-age (dt/duration {:minutes 5})
          :max-batch-size 200}
         (d/without-nils cfg)))

(defmethod ig/init-key ::updater
  [_ {:keys [session metrics] :as cfg}]
  (l/info :action "initialize session updater"
          :max-batch-age (str (:max-batch-age cfg))
          :max-batch-size (str (:max-batch-size cfg)))
  (let [input (aa/batch (::events-ch session)
                        {:max-batch-size (:max-batch-size cfg)
                         :max-batch-age (inst-ms (:max-batch-age cfg))})]
    (a/go-loop []
      (when-let [[reason batch] (a/<! input)]
        (let [result (a/<! (update-sessions cfg batch))]
          (mtx/run! metrics {:id :session-update-total :inc 1})
          (cond
            (ex/exception? result)
            (l/error :task "updater"
                     :hint "unexpected error on update sessions"
                     :cause result)

            (= :size reason)
            (l/debug :task "updater"
                     :hint "update sessions"
                     :reason (name reason)
                     :count result))

          (recur))))))

(defn- update-sessions
  [{:keys [pool executor]} ids]
  (aa/with-thread executor
    (db/exec-one! pool ["update http_session set updated_at=now() where id = ANY(?)"
                        (into-array String ids)])
    (count ids)))

;; --- STATE INIT: SESSION GC

(declare sql:delete-expired)

(s/def ::max-age ::dt/duration)

(defmethod ig/pre-init-spec ::gc-task [_]
  (s/keys :req-un [::db/pool]
          :opt-un [::max-age]))

(defmethod ig/prep-key ::gc-task
  [_ cfg]
  (merge {:max-age (dt/duration {:days 15})}
         (d/without-nils cfg)))

(defmethod ig/init-key ::gc-task
  [_ {:keys [pool max-age] :as cfg}]
  (l/debug :hint "initializing session gc task" :max-age max-age)
  (fn [_]
    (db/with-atomic [conn pool]
      (let [interval (db/interval max-age)
            result   (db/exec-one! conn [sql:delete-expired interval interval])
            result   (:next.jdbc/update-count result)]
        (l/debug :task "gc"
                 :hint "clean http sessions"
                 :deleted result)
        result))))

(def ^:private
  sql:delete-expired
  "delete from http_session
    where updated_at < now() - ?::interval
       or (updated_at is null and
           created_at < now() - ?::interval)")
