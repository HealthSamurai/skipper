(ns skipper.executors.k8s
  (:require [clojure.set]
            [clojure.pprint]
            [clojure.string :as str]

            [skipper.executors.utils]
            [skipper.utils :as utils]
            [skipper.methods]
            [skipper.shell :as shell]

            [cheshire.core]
            [clj-yaml.core]
            [inflections.core :as infl]
            [matcho.core]
            [klog.core]
            [org.httpkit.client :as http])
  (:import (java.io
            IOException)
           (java.net ServerSocket Socket)))


(defn port-is-free
  [port]
  (try (with-open [^Socket _ (Socket. "localhost" ^int port)] false)
       (catch IOException _e true)))


(def manager "kubectl")


(def resources-meta
  {:Namespace                {:apiVersion "v1" :cluster true}
   :ClusterIssuer            {:apiVersion "cert-manager.io/v1" :cluster true}
   :ClusterRole              {:apiVersion "rbac.authorization.k8s.io/v1" :cluster true}
   :ClusterRoleBinding       {:apiVersion "rbac.authorization.k8s.io/v1" :cluster true}
   :ServiceAccount           {:apiVersion "v1"}
   :PersistentVolumeClaim    {:apiVersion "v1"}
   :ConfigMap                {:apiVersion "v1"}
   :Secret                   {:apiVersion "v1"}
   :Service                  {:apiVersion "v1"}
   :Job                      {:apiVersion "batch/v1"}
   :StatefulSet              {:apiVersion "apps/v1"}
   :DaemonSet                {:apiVersion "apps/v1"}
   :PgExporter               {:apiVersion "aidbox.pg/v1"}
   :JobScheduler             {:apiVersion "aidbox.job/v1"}
   :Deployment               {:apiVersion "apps/v1"}
   :Ingress                  {:apiVersion "networking.k8s.io/v1"}})


(defn plural
  [s]
  (when s (infl/plural (str/lower-case s))))


(defn query-k8s
  [port path]
  (->
   (cheshire.core/parse-string
    (:body @(http/get (format "http://localhost:%s%s" port path)))
    keyword)))


(defn k8s-request
  [{url :url :as opts} port]
  (let [res  (http/request (cond->
                            (merge opts
                                   {:url (format "http://localhost:%s%s" port url)
                                    :headers (merge {"content-type" "application/json"
                                                     "accept" "application/json"}
                                                    (:headers opts))})
                             (:body opts) (update :body cheshire.core/generate-string)))]
    (update @res :body (fn [x] (when x (cheshire.core/parse-string x keyword))))))


(defn build-resource-url
  [meta ns rt id]
  (let [prefix (if (= "v1" (:apiVersion meta))
                 (str "/api/v1/")
                 (str "/apis/" (:apiVersion meta) "/"))
        ns-path (if (:cluster meta)
                  ""
                  (if ns
                    (str "namespaces/" (name ns) "/")
                    (throw (Exception. "Namespace expected"))))
        kind (plural (name rt))
        nm (name id)]
    (str prefix ns-path kind "/" nm)))

(defn build-k8s-resource
  [rt id resource]
  (-> resource
      (assoc :apiVersion (:apiVersion (get resources-meta rt))
             :kind       (name rt))
      (assoc-in [:metadata :name] (name id))))

(defn k8s-apply
  [port ns rt id resource]
  (let [path (build-resource-url (get resources-meta rt) ns rt id)
        resource (build-k8s-resource rt id resource)
        result (k8s-request {:url path
                             :query-params {:fieldManager manager
                                            :force "true"
                                            :type "merge"}
                             :headers {"content-type" "application/apply-patch+yaml"
                                       "user-agent" "skipper"}
                             :method :patch
                             :body resource} port)]

    (when (or (not (:status result))
              (> (:status result) 300))
      (println " " "Status:" (:status result))
      (println " " :k8s/error (get-in result [:body :message] (:body result)))
      (println " " :k8s/resource ">>>>")
      (println (clj-yaml.core/generate-string resource))
      (println " " :k8s/resource "<<<<<"))
    (select-keys result [:status :body :error])))


(defn k8s-read
  [port ns rt id]
  (if-let [info (resources-meta rt)]
    (let [resp (k8s-request
                {:url (build-resource-url info ns rt id)
                 :method :get}
                port)]
      (cond
        (= 200 (:status resp)) (:body resp)
        (= 404 (:status resp)) nil
        :else (throw (Exception. (pr-str resp)))))
    (throw (Exception. (str "No meta for " rt)))))


(defn *select-fields
  [fields res]
  (cond
    (map? res)
    (->> fields
         (reduce (fn [acc [k ks]]
                   (let [field (keyword (last (str/split (name k) #":" 2)))]
                     (if-let [v (and field (get res field))]
                       (assoc acc field (*select-fields ks v))
                       acc))) {}))
    :else
    res))


(defn process-managed-fields
  [fields]
  (->> fields
       (reduce (fn [acc [k v]]
                 (cond
                   (= (str k) ":.")
                   acc

                   (str/starts-with? (str k) ":f:")
                   (let [k (keyword (last (str/split (str k) #":" 3)))]
                     (assoc acc k (process-managed-fields v)))

                   (str/starts-with? (str k) ":k:")
                   (let [match (cheshire.core/parse-string (last (str/split (str k) #":" 3)) keyword)]
                     ;; (assert (= 1 (count match)) (pr-str match))
                     (-> (assoc acc :type :vector)
                         (assoc-in [:slices match] (process-managed-fields v))))
                   :else
                   (throw (Exception. (pr-str (name k))))))
               {})))


(defn managed-fields
  [res]
  (->> (get-in res [:metadata :managedFields])
       (filter (fn [x] (= manager (:manager x))))
       (first)
       :fieldsV1
       (process-managed-fields)))


(defn keyset [m] (into #{} (keys m)))


(defn do-match
  [pat x]
  (= pat (select-keys x (keys pat))))


(defn find-matched
  [slice v]
  (let [res (->> v (filter #(do-match slice %)))]
    (assert (>= 1 (count res)) (pr-str res))
    (first res)))


(defn diff-index-by
  [slices v & [accumulate-new]]
  (let [idx (->> (keys slices)
                 (reduce (fn [acc slice]
                           (if-let [m (find-matched slice v)]
                             (assoc acc slice m)
                             acc))
                         {}))]
    (if accumulate-new
      (->> v
           (reduce (fn [idx v']
                     (if (->> (keys slices) (filter (fn [s] (do-match s v'))) first)
                       idx
                       (assoc idx v' v'))) idx))
      idx)))


(defn *calculate-diff
  [acc path flds old new]
  (cond
    (sequential? new)
    (cond
      (and (sequential? old) flds (or (map? (first new)) (map? (first old))))
      (let [slices (:slices flds)
            old-idx  (diff-index-by slices old)
            new-idx  (diff-index-by slices new true)
            old-ks (keyset old-idx)
            new-ks (keyset new-idx)
            create-keys (clojure.set/difference   new-ks old-ks)
            update-keys (clojure.set/intersection new-ks old-ks)
            delete-keys (clojure.set/difference (clojure.set/intersection (keyset slices) old-ks) new-ks)
            acc (->> create-keys
                     (reduce (fn [acc slice]
                               (conj acc {:action :add :path (conj path slice) :value (get new-idx slice)}))
                             acc))
            acc (->> delete-keys
                     (reduce (fn [acc slice]
                               (conj acc {:action :remove :path (conj path slice) :value nil :old (get old-idx slice)}))
                             acc))]
        (->> update-keys
             (reduce (fn [acc slice]
                       (let [ov (get old-idx slice)
                             nv (get new-idx slice)]
                         (if (= ov nv)
                           acc
                           (*calculate-diff acc (conj path slice) (get slices slice) ov nv))))
                     acc)))
      (nil? old)
      (conj acc {:action :add :path path :value new})
      :else
      (conj acc {:action :change :path path :value new :old old}))

    (and (map? old) (map? new))
    (let [new-ks (keyset new)
          old-ks (keyset old)
          managed-ks (keyset flds)
          create-keys (clojure.set/difference new-ks old-ks)
          update-keys (clojure.set/intersection new-ks old-ks)
          delete-keys (clojure.set/difference (clojure.set/intersection managed-ks old-ks) new-ks)
          acc (->> create-keys
                   (reduce (fn [acc k]
                             (conj acc {:action :add :path (conj path k) :value (get new k)}))
                           acc))
          acc (->> delete-keys
                   (reduce (fn [acc k]
                             (conj acc {:action :remove :path (conj path k) :value nil :old (get old k)}))
                           acc))]
      (->> update-keys
           (reduce (fn [acc k]
                     (let [ov (get old k)
                           nv (get new k)]
                       (if (= ov nv)
                         acc
                         (*calculate-diff acc (conj path k) (get flds k) ov nv))))
                   acc)))
    :else
    (conj acc {:action :change :path path :old old :value new})))


(defn calculate-diff
  [flds old-res res]
  (*calculate-diff [] [] flds old-res (if (:metadata res) (update res :metadata dissoc :name) res)))


(defn aws-init
  [{:keys [cluster cloud]}]
  [(concat ["aws" "eks" "update-kubeconfig"]
           (when-let [n (:name cluster)]
             [(str "--name=" n)])
           (when-let [p (:profile cloud)]
             [(str "--profile=" p)])
           (when-let [r (:region cloud)]
             [(str "--region=" r)]))])



(defn gcp-connect-command
  "Prepare cmd"
  [file cloud]
  (let [file-path (utils/file-path file)
        content (cheshire.core/parse-string (slurp file-path) keyword)]
    ["gcloud" "auth" "activate-service-account" (:client_email content)
     "--key-file" file-path
     "--project" (:project cloud)]))

(defn gcp-init
  [{:keys [cluster cloud]} {:keys [key] :as _cli-params}]
  (cond-> []
          key
          (conj (gcp-connect-command key cloud))
          true
          (conj (into [] (concat ["gcloud" "container" "clusters" "get-credentials" (:name cluster)
                                  "--project" (:project cloud)]
                                 (when-let [r (:region cloud)]
                                   ["--region" r])
                                 (when-let [z (:zone cloud)]
                                   ["--zone" z]))))))


(defn azure-init
  [{:keys [cluster cloud]}]
  [["az" "aks" "get-credentials"
    "--subscription" (:subscription cloud)
    "--name" (:name cluster)
    "--resource-group" (:resource-group cloud)]])


(defn init-cmd
  "Return cmds vector for connect subcommand"
  [cli-params {:keys [cluster cloud] :as conn}]
  (cond (= (:engine cloud) 'skipper/aws)
        (aws-init conn)

        (= (:engine cloud) 'skipper/gcp)
        (gcp-init conn cli-params)

        (= (:engine cloud) 'skipper/azure)
        (azure-init conn)

        :else [["kubectl" "config" "use-context" (:context cluster)]]))

(defn resolve-k8s-context
  "Try to find target context in kubeconfig"
  [{:keys [cluster cloud] :as _conn}]
    (klog.core/info :sk/k8s {:msg (str (klog.core/green "[Kubernetes] ") (when cloud (str "Cloud:" (or (:project cloud) (:profile cloud)))))})
    (klog.core/info :sk/k8s {:msg (str (klog.core/green "[Cluster] ") (or (:name cluster) (:context cluster)))})
    (cond (= (:engine cloud) 'skipper/aws)
          (let [context (str "arn:aws:eks:" (:region cloud) ":" (:account cloud) ":cluster/" (:name cluster))]
            {:context context
             :cmd ["kubectl" "config" "use-context" context]})

          (= (:engine cloud) 'skipper/gcp)
          (let [context (str "gke_" (:project cloud) "_" (:region cloud) "_" (:name cluster))]
            {:context context
             :cmd ["kubectl" "config" "use-context" context]})

          (= (:engine cloud) 'skipper/azure)
          (let [context (:name cluster)]
            {:context context
             :cmd ["kubectl" "config" "use-context" context]
             ;;(first (azure-init conn))
             })

          :else {:context (:context cluster)
                 :cmd ["kubectl" "config" "use-context" (:context cluster)]}))


(defmethod skipper.methods/init-executor
  :k8s
  [ztx conn _]
  (let [port (with-open [socket (ServerSocket. 0)]
               (.getLocalPort socket))]
    (if (port-is-free port)
      (let [{cmd :cmd context :context} (resolve-k8s-context conn)
            res (shell/exec {:exec cmd})]
        (if (= 0 (:status res))
          (do
            (klog.core/info :sk/k8s {:msg (str "Switched to context [" context "]")})
              (let [prx (shell/run {:exec ["kubectl" "proxy" (format "--port=%s" port)] :inherit-io true})]
                ;; Call k8s api
                (shell/exec {:exec ["kubectl" "get" "ns"]})
                (swap! ztx assoc :k8s/proxy prx)
                (swap! ztx assoc :k8s/port port)))
          (do
            (klog.core/error :sk/k8s {:msg (str "Could not connect to \n" (with-out-str (clojure.pprint/pprint conn)))})
            (klog.core/error :sk/k8s {:msg (clojure.string/join "\n" (:stderr res))})
            (throw (Exception. (str "Could not connect to " conn))))))
      (throw (Exception. (format "Port is used: %s" port))))))


(defmethod skipper.methods/deinit-executor
  :k8s
  [ztx _conn _]
  (let [^Process prx (:k8s/proxy @ztx)]
    (when (and prx (.isAlive prx))
      (.destroy prx)
      (.waitFor prx))))

(defn try-parse
  [s]
  (try (cheshire.core/parse-string s)
       (catch Exception _e
         (try
           (clj-yaml.core/parse-string s)
           (catch Exception _e s)))))

(defn print-diff
  [ns rt id {action :action diff :diff}]
  (cond
    (= :add action)
    (klog.core/info :sk/k8s {:msg (str "Create: " (name rt) " " (name (or ns "")) "/" (name id))})
    (= :change action)
    (try
      (when (seq diff)
        (klog.core/info :sk/k8s {:msg (str (klog.core/yellow "[Change] ") (str (name rt) " " (name ns) "/" (name id)))})
        (doseq [d diff]
          (klog.core/warn :sk/diff-title {:msg ["-" (str/capitalize (name (:action d))) "Path:" "[" (str/join " " (map (fn [v] (if (map? v) (:name v) (name v))) (:path d))) "]"]})
          (let [changes (skipper.executors.utils/difference (try-parse (:value d)) (try-parse (:old d)))]
            (doseq [c changes]
              (klog.core/warn :sk/diff {:msg {:path (:path c)
                                              :old               (:old c)
                                              :new               (:value c)}})))))
      (catch Exception e (println e)))))

(defmethod skipper.methods/diff
  :k8s
  [ztx _conn ns rt id res]
  (let [port (:k8s/port @ztx)
        d (if-let [old-res (k8s-read port ns rt id)]
            (let [fld (managed-fields old-res)
                  diff (seq (calculate-diff fld old-res res))]
              (when diff
                {:action :change
                 :diff diff}))
            {:action :add
             :resource res})]
    (print-diff ns rt id d) d))

(defmethod skipper.methods/exec
  :k8s
  [ztx _conn ns rt id res]
  (let [port (:k8s/port @ztx)]
    (if-let [old-res (k8s-read port ns rt id)]
      (let [flds (managed-fields old-res)
            diff (calculate-diff flds old-res res)]
        (if (empty? diff)
          (klog.core/info :sk/k8s {:msg (str "Unchanged: " (name rt) " " (name (or ns "")) "/" (name id))})
          (do
            (klog.core/info :sk/k8s {:msg (str "Update: " (name rt) " " (name (or ns "")) "/" (name id))})
            (k8s-apply port ns rt id res))))
      (do
        (klog.core/info :sk/k8s {:msg (str "Create: " (name rt) " " (name (or ns "")) "/" (name id))})
        (k8s-apply port ns rt id res)))))
