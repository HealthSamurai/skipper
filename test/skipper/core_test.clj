(ns skipper.core-test
  (:require
   [zen.core :as zen]
   [skipper.methods]
   [skipper.core :as sut]
   [matcho.core :as matcho]
   [clojure.test :as t]
   [clj-time.core :as time]))



(def ztx (zen/new-context))

(defmethod skipper.methods/expand
  :test/db
  [_ id res]
  {:StatefulSet {id res}
   :Service     {id {}}
   :Secret      {id {}}
   :ConfigMap   {id {}}})

(def postgres-cfg
  '{:host (str "pg." (params :id) ".svc.local")})

(def postgres-tpl
  '{:test/db {:image (params :image)
              :secrets {:password (params :password)}
              :storage {:size (str (params :size) "Gi")}}})

(defmethod skipper.methods/init
  'test/postgres
  [_ztx id params]
  (update params :connection
          assoc :host (str "pg." (name id) ".svc.local")
                :password "generated"))

(defmethod skipper.methods/bind
  'test/postgres
  [_ztx _system config]
  config)

(defmethod skipper.methods/plan
  'test/postgres
  [_ztx _config]
  postgres-tpl)

(defmethod skipper.methods/init
  'test/monitor
  [_ztx _id params]
  params)

(defmethod skipper.methods/bind
  'test/monitor
  [_ztx system config]
  (let [scrapes (skipper.methods/subscribe system 'monitor/scrapes)]
    (assoc config :scrapes scrapes)))

(defmethod skipper.methods/plan
  'test/monitor
  [_ztx _config]
  {:StatefulSet {:pg {}}
   :Secret      {:pg {}}
   :Service     {:pg {}}
   :helm        {:kube-exporter {}}})

(defmethod skipper.methods/init
  'test/logging
  [_ztx id _params]
  (let [host (str "es." (name id) ".svc.local")]
    (-> {:host host}
        (skipper.methods/publish
         'monitor/scrapes
         {id {:interval "..." :host (str host "/metrics")}}))))

(defmethod skipper.methods/bind
  'test/logging
  [_ztx _system config]
  config)

(defmethod skipper.methods/plan
  'test/logging
  [_ztx _config]
  {:StatefulSet {:pg {}}
   :Secret {:pg {}}
   :Service {:pg {}}})

(defmethod skipper.methods/init
  'test/app
  [_ztx id _params]
  (let [host (str "aidbox." (name id) ".svc.local")]
    (-> {:service host}
        (skipper.methods/publish
         'monitor/scrapes
         {id {:interval "..." :host (str host "/metrics")}}))))


(defmethod skipper.methods/bind
  'test/app
  [_ztx system config]
  (let [db (get system (:db config))]
    (assoc config
           :connection (:connection db)
           :es (skipper.methods/discovery system 'test/logging))))

(defmethod skipper.methods/plan
  'test/app
  [_ztx _config]
  {:Deployment {:app {}}
   :Secret {:app {}}
   :Service {:app {}}})


(def config
  '{:logs    {:engine test/logging}
    :monitor {:engine test/monitor}
    :db      {:engine test/postgres
              :connection {:user "postgres"}}
    :app     {:engine test/app
              :version "edge"
              :db :db}})


(t/deftest test-skipper-basics
  (matcho/match
   (->>
    (sut/init ztx config)
    (sut/bind ztx))
   '{:logs
     {:engine test/logging,
      :host "es.logs.svc.local"}
     :monitor
     {:engine test/monitor
      :scrapes {:logs {:interval "...", :host "es.logs.svc.local/metrics"}
                :app {:interval "...", :host "aidbox.app.svc.local/metrics"}}}
     :db
     {:engine test/postgres
      :connection {:user "postgres", :host "pg.db.svc.local", :password "generated"}}
     :app
     {:engine test/app,
      :version "edge"
      :db :db
      :service "aidbox.app.svc.local"
      :connection {:host "pg.db.svc.local", :password "generated"}
      :es [{:host "es.logs.svc.local"}]}}
   )

  (t/is (= {} (sut/clear-empty {:a {:b {:c [nil {}]}}})))

  )


(t/deftest maintenance-window
  (def schedule {:tz       "Europe/Moscow"
                 :schedule {:monday [{:from "20:30" :to "22:00"}
                                     {:from "06:30" :to "08:00"}]
                            :tuesday [{:from "14:00" :to "16:00"}]
                            :saturday [{:from "14:00" :to "16:00"}]}})

  (t/is (sut/not-maintenance schedule (time/date-time 2022 6 20 8 0 1)))
  (t/is (sut/not-maintenance schedule (time/date-time 2022 6 20 23 0 1)))
  (t/is (sut/not-maintenance schedule (time/date-time 2022 6 20 17 0 1)))
  (t/is (not (sut/not-maintenance schedule (time/date-time 2022 6 20 18 0 1))))
  (t/is (sut/not-maintenance schedule (time/date-time 2022 6 20 2 0 1)))
  (t/is (sut/not-maintenance schedule (time/date-time 2022 6 20 3 0 1)))
  (t/is (not (sut/not-maintenance schedule (time/date-time 2022 6 20 4 0 1))))
  (t/is (sut/not-maintenance schedule (time/date-time 2022 6 20 5 0 1)))
  (t/is (sut/not-maintenance schedule (time/date-time 2022 6 21 8 0 1)))
  (t/is (sut/not-maintenance schedule (time/date-time 2022 6 21 23 0)))
  (t/is (not (sut/not-maintenance schedule (time/date-time 2022 6 21 11 0 1))))
  (t/is (not (sut/not-maintenance schedule (time/date-time 2022 6 25 11 0 1))))
  (t/is (sut/not-maintenance schedule (time/date-time 2022 6 26 11 0 1)))
  (t/is (sut/not-maintenance schedule (time/date-time 2022 6 26 12 0 1)))
  (t/is (sut/not-maintenance schedule (time/date-time 2022 6 26 15 0 1)))
  (t/is (sut/not-maintenance schedule (time/date-time 2022 6 26 23 0 1))))




;; 1. component binding
;; 2. build plan (templating)


