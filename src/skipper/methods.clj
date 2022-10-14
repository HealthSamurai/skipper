(ns skipper.methods)


;; initialize component - calculate their own configuration
(defn subscribe
  [system ev]
  (->> system
       (map second)
       (map :sk/publish)
       (map ev)
       (filter identity)
       (reduce merge)))


(defn discovery
  [system type]
  (->> system
       (filter (fn [[_ {tp :engine}]] (= tp type)))
       (vals)))


(defn get-component
  [system name]
  (get system name))


;; todo validate
(defn publish
  [config ev params]
  (assoc-in config [:sk/publish ev] params))


(defmulti  init (fn [_ztx _name params] (:engine params)))
(defmethod init :default [_ _ _] {})

(defmulti  bind (fn [_ztx _system config] (:engine config)))
(defmethod bind :default [_ _ _] {})

(defmulti plan (fn [_ztx config] (:engine config)))

(defmulti reconcile (fn [_ztx _conn _ns config] (:engine config)))


(defmethod reconcile :default
  [_ _ _ _])


(defmulti pre-reconcile (fn [_ztx params] (:engine params)))


(defmethod pre-reconcile :default
  [_ params]
  (println "Pre-reconcile not implemented: " (:engine params))
  {})


;; templating

(defmulti expand (fn [tp _id _resource] tp))


(defmethod expand :default
  [tp id resource]
  ;; (println ::expand-not-impl tp)
  {tp {id resource}})


(defmulti formatter (fn [fmt _data] fmt))


(def exec-map
  {:Namespace :k8s
   :ClusterIssuer :k8s
   :ConfigMap :k8s
   :Service :k8s
   :Secret :k8s
   :Ingress :k8s
   :Deployment :k8s
   :StatefulSet :k8s
   :DaemonSet :k8s
   :PersistentVolumeClaim :k8s
   :ClusterRole :k8s
   :ClusterRoleBinding :k8s
   :ServiceAccount :k8s
   :Job :k8s
   :PgExporter :k8s
   :JobScheduler :k8s
   :helm      :helm})


(defmulti exec (fn [_ztx _conn _ns kind _id _resource] (get exec-map kind (str "?" kind))))
(defmulti init-executor (fn [_ztx _conn ex] ex))
(defmulti deinit-executor (fn [_ztx _conn ex] ex))

(defmulti diff (fn [_ztx _conn _ns kind _id _resource] (get exec-map kind (str "?" kind))))
