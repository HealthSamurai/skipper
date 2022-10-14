(ns skipper.resources.core
  (:require
    [clojure.string]
    [clojure.pprint]
    [skipper.utils :refer [file-path service-name]]
    [skipper.methods]
    [skipper.shell :as shell]
    [cheshire.core])
  (:import
    (clojure.lang
      PersistentArrayMap)
    (java.security
      MessageDigest)
    (java.util
      Base64)
    (java.util.zip
      CRC32)))


(set! *warn-on-reflection* true)


(defn crc32
  [x]
  (let [crc (CRC32.)]
    (.update crc (.getBytes ^String (.toString ^PersistentArrayMap x)))
    (Long/toHexString
      (.getValue crc))))


(defn create-tls
  [{cert :cert key :key} nm]
  (let [cmd ["kubectl" "create" "secret" "tls" (name nm) (str "--key=" (System/getProperty "infrabox.project") key) (str "--cert=" (System/getProperty "infrabox.project") cert) "--dry-run=client" "--save-config=true" "--output=json"]
        secret (cheshire.core/parse-string (apply str (:stdout (shell/exec {:exec cmd}))) keyword)]
    (select-keys secret [:data :type])))


(defn create-secret-for-cert-wildcard
  "Create secret for support wildcard certificate for multibox installation"
  [path name]
  (let [cmd ["kubectl" "create" "secret" "generic" name (str "--from-file=key.json=" path) "--dry-run=client" "-o" "json"]
        secret (cheshire.core/parse-string (apply str (:stdout (shell/exec {:exec cmd}))) keyword)]
    (select-keys secret [:data :type])))


(defn file-name
  [id nm]
  (keyword (str (name id) "-" (name nm))))


(defn base64-decode
  [b64]
  (let [decoder (Base64/getDecoder)
        resultBytes (.decode decoder ^String b64)]
    (String. resultBytes)))


(defn base64-encode
  [txt]
  (let [encoder (Base64/getEncoder)
        resultBytes (.encode encoder (.getBytes ^String txt))]
    (String. resultBytes)))


;; TODO: add to configmaps
(defn stringify [secret]
  (if-let [file (:file secret)]
    (slurp (file-path file))
    secret))

(defn encode-secrets
  [m]
  (->> m
       (reduce (fn [acc [k v]]
                 (if v
                   (assoc acc k (base64-encode (stringify v)))
                   acc))
               {})))


(defmethod skipper.methods/expand
  :skipper/secret
  [_tp id res]
  {:Secret {id {:data (encode-secrets res)}}})


(defn tls-name
  [id]
  (keyword (str (name id) "-tls")))


(defn ingress
  [id {:keys [http tls whitelist auth] :as _res}]
  (reduce
    (fn [ing [sid cfg]]
      (let [host (:host cfg)
            rule {:host host
                  :http {:paths [{:path     "/",
                                  :pathType "ImplementationSpecific",
                                  :backend  {:service {:name (name (service-name id sid)) :port {:number 80}}}}]}}]
        (if host
          (-> ing
              (update-in [:spec :rules] conj rule)
              (update-in [:spec :tls 0 :hosts] conj host))
          ing)))
    {:metadata
     {:annotations
      (merge {:kubernetes.io/ingress.class "nginx"}
             (when auth
               {:nginx.ingress.kubernetes.io/auth-type   "basic"
                :nginx.ingress.kubernetes.io/auth-secret (str (name id) "-" (:user auth))})
             (when whitelist
               {:nginx.ingress.kubernetes.io/whitelist-source-range (clojure.string/join "," (map (fn [ip] (str ip "/32")) whitelist))})
             (when-not tls
               {:cert-manager.io/cluster-issuer            "letsencrypt"
                :acme.cert-manager.io/http01-ingress-class "nginx"}))}
     :spec
     {:tls   [{:secretName (name (tls-name id)) :hosts []}]
      :rules []}}
    http))


(defn build-http
  [id labels http]
  (->> http
       (reduce
         (fn [svc [sid cfg]]
           (assoc svc (service-name id sid)
                      {:metadata (:metadata cfg)
                       :spec {:selector (merge labels (:labels cfg))
                              :ports    [{:protocol   "TCP"
                                          :targetPort (:port cfg)
                                          :port       80}]}})) {})))


(defn build-ports
  [http]
  (->> http (mapv (fn [[_ {port :port}]] {:containerPort port :protocol "TCP"}))))


(declare build-services)


(defn preprocess-files
  [files]
  (->> files
       (reduce (fn [acc [k v]]
                 (let [dir (clojure.string/join "/" (butlast (clojure.string/split (:path v) #"/")))
                       filename (keyword (last (clojure.string/split (:path v) #"/")))]
                   (-> acc
                       (update-in [dir :keys] conj k)
                       (update dir assoc-in [:data filename] (:data v))
                       (assoc-in [dir :mode] (:mode v))))) {})
       (reduce (fn [acc [path {:as v, :keys [keys]}]]
                 (if (= (count keys) 1)
                   (assoc acc (-> keys first keyword) (-> v (dissoc :keys) (assoc :path path)))
                   (let [dir-name (keyword (last (clojure.string/split path #"/")))]
                     (assoc acc dir-name (-> v (dissoc :keys) (assoc :path path)))))) {})))


(defn build-pvc-volumes
  [id volumes]
  (mapv (fn [[k v]]
          (let [n (name (file-name id k))]
            (cond
              (:emptyDir v) {:name n :emptyDir (:emptyDir v)}
              (:hostPath v) {:name n :hostPath (:hostPath v)}
              :else {:name n :persistentVolumeClaim {:claimName (or (:name v) n)}}))) volumes))


(defn build-pvc-mounts
  [id volumes]
  (mapv (fn [[k v]]
          (merge
            {:name      (name (file-name id k))
             :mountPath (if (:hostPath v) (get-in v [:hostPath :path]) (:mount v))}
            (when (:subPath v)
              {:subPath (:subPath v)})))
        volumes))


(defn build-pvc
  [id volumes]
  (->> volumes
       (remove (fn [[_k spec]] (or (:emptyDir spec) (:hostPath spec))))
       (reduce (fn [acc [k v]]
                 (let [spec (merge {:accessModes ["ReadWriteOnce"]
                                    :resources   {:requests {:storage (:storage v)}}}
                                   (when-let [class (:class v)]
                                     {:storageClassName class}))]
                   (assoc acc (or (keyword (:name v))
                                  (file-name id k)) {:spec spec}))) {})))


(defn build-file-volumes
  [id volumes]
  (mapv (fn [[k v]]
          {:name      (name (file-name id k))
           :configMap {:name        (name (file-name id k))
                       :defaultMode (:mode v)}}) volumes))


(defn build-file-mounts
  [id volumes]
  (mapv (fn [[k v]]
          {:name (name (file-name id k)) :mountPath (:path v)}) volumes))


(defn build-files
  [id files]
  (into {} (mapv (fn [[k v]] [(file-name id k) (select-keys v [:data])]) files)))


(defn build-services
  [id labels http]
  (->> http
       (reduce
         (fn [svc [sid cfg]]
           (let [nm (service-name id sid)]
             (assoc svc nm {:spec {:selector labels
                                   :ports    [{:protocol   "TCP"
                                               :targetPort (:port cfg)
                                               :port       (:port cfg)}]}})))
         {})))


(defn empty-as-nil
  [x]
  (if (empty? x) nil x))

(defn format-envs [& maps]
  (mapcat (fn [x]
            (skipper.methods/formatter (:sk/format x)
                                       (dissoc x :sk/format))) maps))

(defn pod-template
  [id labels res]
  (let [files (:files res)
        preprocessed-files (preprocess-files (:files res))
        volumeMounts (concat (build-file-mounts id preprocessed-files)
                             (build-pvc-mounts id (:volumes res)))
        volumes (concat (build-file-volumes id preprocessed-files)
                        (build-pvc-volumes id (:volumes res)))
        configs (:configs res)
        secrets (:secrets res)
        init (when-let [init (:init res)]
               (->> init
                    (mapv (fn [cnt]
                            (assoc cnt
                              :image (or (:image cnt) "busybox")
                              :volumeMounts (or (:volumeMounts cnt) volumeMounts))))))]


    {:metadata {:labels labels}
     :spec     (merge {:serviceAccount                (:serviceAccount res)
                       :securityContext               (:securityContext res)
                       :restartPolicy                 (:restartPolicy res)
                       :initContainers                init
                       :terminationGracePeriodSeconds (:terminationGracePeriodSeconds res)
                       :containers                    [{:name            "main"
                                                        :image           (:image res)
                                                        :volumeMounts    volumeMounts
                                                        :resources       (get res :resources)
                                                        :readinessProbe  (:readinessProbe res)
                                                        :livenessProbe   (:livenessProbe res)
                                                        :command         (:command res)
                                                        :args            (:args res)
                                                        :imagePullPolicy (get res :imagePullPolicy "Always")
                                                        :envFrom         [(when configs {:configMapRef {:name (name id)}})
                                                                          (when secrets {:secretRef {:name (name id)}})]
                                                        :env             (format-envs
                                                                           {:sk/format       :env
                                                                            :config_version  (when configs (str "v" (crc32 configs)))
                                                                            :files_version   (when files (str "v" (crc32 files)))
                                                                            :secrets_version (when secrets (str "v" (crc32 secrets)))}
                                                                           (assoc (:fieldRef res) :sk/format :field-ref)
                                                                           (assoc (:resourceFieldRef res) :sk/format :resource-field-ref))
                                                        :ports           (distinct (concat (build-ports (:expose res))
                                                                                           (build-ports (:http res))))}]
                       :volumes                       volumes}
                      (when (:imagePullSecrets res)
                        {:imagePullSecrets (:imagePullSecrets res)}))}))


(defmethod skipper.methods/expand
  :skipper/job
  [_tp id res]
  (let [_image (:image res)
        _replicas (get res :replicas 1)
        labels {:service (name id)}
        configs (empty-as-nil (:configs res))
        secrets (empty-as-nil (:secrets res))
        preprocessed-files (preprocess-files (:files res))
        files (empty-as-nil (build-files id preprocessed-files))
        pvc (build-pvc id (:volumes res))]
    {:ConfigMap             (merge {id (when configs {:data configs})} files)
     :Secret                {id (when secrets {:data (encode-secrets secrets)})}
     :PersistentVolumeClaim pvc
     :Job
     {id
      {:spec
       {:backoffLimit (:backoffLimit res)
        :template     (pod-template id labels (merge {:restartPolicy "Never"} res))}}}}))


(defmethod skipper.methods/expand
  :skipper/daemonset
  [_tp id res]
  (let [_image (:image res)
        _replicas (get res :replicas 1)
        labels {:service (name id)}
        configs (empty-as-nil (:configs res))
        secrets (empty-as-nil (:secrets res))
        preprocessed-files (preprocess-files (:files res))
        files (empty-as-nil (build-files id preprocessed-files))
        pvc (build-pvc id (:volumes res))]
    {:ConfigMap             (merge {id (when configs {:data configs})} files)
     :Secret                {id (when secrets {:data (encode-secrets secrets)})}
     :PersistentVolumeClaim pvc
     :DaemonSet
     {id
      {:spec
       {:selector {:matchLabels labels}
        :template (pod-template id labels res)}}}}))


(defn htpasswd
  [{:keys [user password]}]
  (->> (.getBytes ^String password "UTF-8")
       (.digest (MessageDigest/getInstance "SHA1"))
       (.encode (Base64/getEncoder))
       String.
       (str user ":{SHA}")))


(defn mk-svc [id svc]
  {id {:spec {:selector (:labels svc)
              :ports    [{:protocol   "TCP"
                          :targetPort (:port svc 80)
                          :port       80}]}}})

(defn auth-secret-name [id ing]
  (str (name id) "-" (get-in ing [:auth :user]) "-auth"))

(defn mk-ing [id {:keys [host whitelist auth tls] :as ing}]
  (when host
    {id {:metadata
         {:annotations
          (merge {:kubernetes.io/ingress.class "nginx"}
                 (when auth
                   {:nginx.ingress.kubernetes.io/auth-type   "basic"
                    :nginx.ingress.kubernetes.io/auth-secret (auth-secret-name id ing)})
                 (when whitelist
                   {:nginx.ingress.kubernetes.io/whitelist-source-range (clojure.string/join "," (map (fn [ip] (str ip "/32")) whitelist))})
                 (when-not tls
                   {:cert-manager.io/cluster-issuer            "letsencrypt"
                    :acme.cert-manager.io/http01-ingress-class "nginx"}))}
         :spec
         {:tls   [{:secretName (name (tls-name id))
                   :hosts      [host]}]
          :rules [{:host host
                   :http {:paths [{:path     "/",
                                   :pathType "ImplementationSpecific",
                                   :backend  {:service {:name (name id)
                                                        :port {:number 80}}}}]}}]}}}))

(defn mk-ingress-secret [id ing]
  (hash-map (auth-secret-name id ing)
            {:data (encode-secrets {:auth (htpasswd (:auth ing))})}))

(defmethod skipper.methods/expand :skipper/ingress
  [_tp id ing]
  (cond-> {}
          (:port ing) (assoc :Service (mk-svc id ing))
          (:host ing) (assoc :Ingress (mk-ing id ing))
          (:auth ing) (assoc :Secret (mk-ingress-secret id ing))))


(defmethod skipper.methods/expand :skipper.res/stateful
  [_tp id res]
  (let [_image (:image res)
        replicas (get res :replicas 1)
        labels (merge (:labels res) {:service (name id)})
        expose (:expose res)
        http (:http res)
        ing (ingress id res)
        configs (empty-as-nil (:configs res))
        secrets (empty-as-nil (:secrets res))
        preprocessed-files (preprocess-files (:files res))
        files (empty-as-nil (build-files id preprocessed-files))
        pvc (build-pvc id (:volumes res))]
    {:ConfigMap             (merge {id (when configs {:data configs})} files)
     :Secret                (merge {id (when secrets {:data (encode-secrets secrets)})}
                                   (when (:auth res)
                                     {(str (name id) "-" (get-in res [:auth :user]))
                                      {:data (encode-secrets
                                               {:auth (htpasswd (:auth res))})}}))
     :Service               (merge (build-services id labels expose)
                                   (build-http id labels http))
     :Ingress               {id (when (seq (get-in ing [:spec :rules])) ing)}
     :PersistentVolumeClaim pvc
     :StatefulSet
     {id
      {:spec
       {:replicas            replicas
        :selector            {:matchLabels labels}
        :podManagementPolicy (:podManagementPolicy res)
        :serviceName         (:serviceName res (name id))
        :template            (pod-template id labels res)}}}}))


(defmethod skipper.methods/expand :sa/secret
  [_tp id res]
  {:Secret {id (create-secret-for-cert-wildcard (file-path (:key res)) id)}})


(defmethod skipper.methods/expand :skipper.res/stateless
  [_tp id res]
  (let [image (:image res)
        replicas (get res :replicas 1)
        labels (merge (:labels res) {:service (name id)})
        expose (:expose res)
        http (:http res)
        tls (:tls res)
        ing (ingress id res)
        configs (empty-as-nil (:configs res))
        secrets (empty-as-nil (:secrets res))
        preprocessed-files (preprocess-files (:files res))
        files (empty-as-nil (build-files id preprocessed-files))
        volumeMounts (build-file-mounts id preprocessed-files)
        volumes (build-file-volumes id preprocessed-files)
        init (when-let [init (:init res)]
               (->> init
                    (mapv (fn [cnt]
                            (assoc cnt
                              :image (or (:image cnt) "busybox")
                              :volumeMounts (or (:volumeMounts cnt) volumeMounts))))))]

    {:ConfigMap (merge {id (when configs {:data configs})} files)
     :Secret    (merge
                  {id            (when secrets {:data (encode-secrets secrets)})
                   (tls-name id) (when tls (create-tls tls (tls-name id)))}
                  (when (:auth res)
                    {(str (name id) "-" (get-in res [:auth :user]))
                     {:data (encode-secrets
                              {:auth (htpasswd (:auth res))})}}))

     :Service   (merge (build-services id labels expose)
                       (build-http id labels http))
     :Ingress   {id (when (seq (get-in ing [:spec :rules])) ing)}
     :Deployment
     {id
      {:spec
       {:replicas replicas
        :selector {:matchLabels labels}
        :strategy (cond-> nil
                    (:recreate? res) (merge {:type "Recreate"}))
        :template
        {:metadata {:labels labels}
         :spec     {:imagePullSecrets (:imagePullSecrets res)
                    :serviceAccount   (:serviceAccount res)
                    :securityContext  (:securityContext res)
                    :initContainers   init
                    :containers       [{:name            "main"
                                        :image           image
                                        :volumeMounts    volumeMounts
                                        :resources       (get res :resources)
                                        :readinessProbe  (:readinessProbe res)
                                        :livenessProbe   (:livenessProbe res)
                                        :command         (:command res)
                                        :args            (:args res)
                                        :imagePullPolicy (get res :imagePullPolicy "Always")
                                        :envFrom         [(when configs {:configMapRef {:name (name id)}})
                                                          (when secrets {:secretRef {:name (name id)}})]
                                        :env             {:sk/format       :env
                                                          :config_version  (when configs (str "v" (crc32 configs)))
                                                          :files_version   (when files (str "v" (crc32 files)))
                                                          :secrets_version (when secrets (str "v" (crc32 secrets)))}
                                        :ports           (distinct (concat (build-ports expose)
                                                                           (build-ports http)))}]
                    :volumes          volumes}}}}}}))


(comment
  (def res {:image   "healthsamurai/aidboxdb:13.2"
            :http    {:main {:port 143 :host "hello"}}
            ;; :expose {:db {:port 5432}}
            :args    ["-c" "config_file=/etc/configs/postgres.conf"]
            :secrets {:POSTGRES_USER     "postgres"
                      :POSTGRES_PASSWORD "postgres"}
            :configs {:PGDATA ".../pgdata"}
            :files   {:config
                      {:path "/etc/configs/postgres.conf"
                       :data {:sk/format :pg/config
                              :var1      ""
                              :var2      ""}}}
            :volumes {:data {:mount   "/pgdata/data"        ;; ? subpath ?
                             :storage "100Gi"}}})


  (-> (skipper.methods/expand
        :skipper.res/stateful :pg res)
      #_(skipper.core/apply-formatters))

  (skipper.core/apply-formatters {:sk/format :pg/config :var "var"}))


