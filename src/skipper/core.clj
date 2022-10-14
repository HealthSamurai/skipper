(ns skipper.core
  (:require
    [clojure.edn]
    [clojure.pprint]
    [clojure.walk]
    [clojure.string]

    [skipper.executors.core]
    [skipper.shell]
    [skipper.formatters.core]
    [skipper.executors.k8s]
    [skipper.methods]
    [skipper.resources.core]

    [edamame.core :as edamame]
    [zen.core :as zen]
    [klog.core]
    [clj-time.core])
  (:import (org.joda.time DateTime)))


(defn read-config
  "Load end file by given file path"
  [ztx config-file]
  (->> config-file
       slurp
       edamame/parse-string
       (zen/load-ns ztx)))


(defn load-config
  "Load zen ns by end config"
  [ztx config]
  (zen/load-ns ztx config))


(defn deep-merge
  "efficient deep merge"
  ([a b & more]
   (apply deep-merge (deep-merge a b) more))
  ([a b]
   (loop [[[k v :as i] & ks] b
          acc a]
     (if (nil? i)
       acc
       (let [av (get a k)]
         (if (= v av)
           (recur ks acc)
           (recur ks
                  (cond
                    (and (map? v) (map? av)) (assoc acc k (deep-merge av v))
                    (and (nil? v) (map? av)) (assoc acc k av)
                    :else (assoc acc k v)))))))))

(defn prepare-exec-message
  "Prepare zeh.shell/exec output"
  [success error]
  (cond
    (not (empty? success))
    success
    (not (empty? error))
    error
    :else
    "Empty output"))


(defn is-helm? [plan]
  (some (fn [[_ v] ] (:helm v)) plan))

(defn connection
  [ztx entry]
  (let [deploy (zen/get-symbol ztx entry)
        cluster (->> deploy :cluster
                     (zen/get-symbol ztx))
        cloud  (->> cluster :cloud
                    (zen/get-symbol ztx))]
    {:cluster cluster
     :cloud cloud}))

(defn get-common-labels
  [ztx entry]
  (-> ztx
      (zen/get-symbol entry)
      :common-labels))



(defn strip-ns
  [kw]
  (keyword (name kw)))


(defn components
  [ztx entry]
  (->> (zen/get-symbol ztx entry)
       (:components)
       (reduce (fn [acc name]
                 (assoc acc name (zen/get-symbol ztx name))) {})))


(defn cluster
  [ztx entry _config]
  (->> (zen/get-tag ztx entry)
       (first)
       (zen/get-symbol ztx)
       (:cluster)
       (zen/get-symbol ztx)))


(defn validate
  [ztx config]
  (let [errs (:errors @ztx)]
    (when-not (empty? errs)
      (clojure.pprint/pprint errs))
    config))


(defn init
  "Initialize component
   Set discovery endpoints, publish scrappers and etc"
  [ztx config]
  (reduce-kv
   (fn [acc id params]
     (assoc acc id (deep-merge params
                               (skipper.methods/init ztx id params)
                               {:id id})))
   {}
   config))


(defn bind
  "Bind different components"
  [ztx config]
  (reduce-kv
    (fn [acc id params]
      (assoc acc id (deep-merge params
                                (skipper.methods/bind ztx acc params))))
    config
    config))


(defn apply-formatters
  [tpl]
  (clojure.walk/postwalk
    (fn [x]
      (if (and (map? x) (:sk/format x))
        (skipper.methods/formatter (:sk/format x) (dissoc x :sk/format))
        x)) tpl))


(defn *expand [ztx tp id res]
  (skipper.methods/expand tp id res))

(defn expand
  [ztx tpl _params & [{noformat? :noformat}]]
  (->> tpl
       (reduce (fn [result [tp resources]]
                 (deep-merge
                   result
                   (->> resources
                        (reduce (fn [result [id res]]
                                  (cond->> (*expand ztx tp id res)
                                    (not noformat?)
                                    (apply-formatters)
                                    true
                                    (deep-merge result)))
                                result))))
               {})))


(defn is-empty?
  [v]
  (or (nil? v) (and (coll? v) (empty? v))))


(defn clear-empty
  [res]
  (cond
    (map? res)
    (->> res (reduce (fn [acc [k v]]
                       (let [v' (clear-empty v)]
                         (if (is-empty? v')
                           acc
                           (assoc acc k
                                  (if (and (map? v') (:sk/keep v'))
                                    (dissoc v' :sk/keep)
                                    v')))))
                     {}))
    (sequential? res)
    (->> res
         (mapv clear-empty)
         (filterv #(not (is-empty? %))))

    :else
    res))


(defn get-engines
  "Populate specific engines from config based on cli args - e"
  [ztx]
  (let [source (set (seq (map symbol (get-in @ztx [:cli-params :e] []))))]
    (->> (zen.core/get-tag ztx 'skipper/component)
         (map #(zen.core/get-symbol ztx %))
         (filter #(contains? source (:engine %)))
         (map #(:zen/name %)))))

(defn get-targets
  "Populate specific engines from config based on cli args - e and c"
  [ztx config]
  (let [elements (into []
                       (concat
                         (seq (map symbol (get-in @ztx [:cli-params :c])))
                         (get-engines ztx)))]
    (if (seq elements)
      (select-keys config elements)
      config)))


(defn do-plan
  "Prepare plan"
  [ztx config]
  (->> config
       (reduce (fn [system [name params]]
                 (assoc system name (expand ztx (skipper.methods/plan ztx params)
                                            params {:noformat (:skipper/noformat @ztx)})))
               config)
       clear-empty
       (get-targets ztx)))


(defn plan
  [ztx config]
  (let [p (do-plan ztx config)]
    (doseq [[_ tps] p]
      (doseq [[rt ress] tps]
        (doseq [[id res] ress]
          (klog.core/log :cli/plan {:msg (skipper.executors.k8s/build-k8s-resource rt id res)}))))
    p))


(defn delete-ns
  [ztx entry plan]
  (let [conn (connection ztx entry)]
    (try
      (doseq [executor (cond-> [:k8s] (is-helm? plan) (conj :helm))]
        (skipper.methods/init-executor ztx conn executor))
      (doseq [ns (map (fn [x] (strip-ns x)) (keys plan))]
        (let [{status :status error :stderr success :stdout} (skipper.shell/exec {:exec ["kubectl" "delete" "ns" (name ns)]})]
          (if (= 0 status)
            (klog.core/info :sk/k8s {:msg (clojure.string/join "\n" (prepare-exec-message success error))})
            (klog.core/error :sk/k8s {:msg (clojure.string/join "\n" (prepare-exec-message success error))}))))
      (finally
        (doseq [executor (cond-> [:k8s] (is-helm? plan) (conj :helm))]
          (skipper.methods/deinit-executor ztx conn executor))
        (klog.core/info :sk/k8s {:msg (klog.core/green "Finish")})))))

(defn build
  [ztx system]
  (->>
    (init ztx system)
    (bind ztx)
    (plan ztx)))



(defn create-target-time
  "Create target maintenance time"
  [current hour min tz]
  (clj-time.core/from-time-zone (clj-time.core/date-time
                                  (clj-time.core/year current)
                                  (clj-time.core/month current)
                                  (clj-time.core/day current)
                                  hour
                                  min)
                                tz))

(defn not-maintenance
  "Compare current UTC time with provided maintenance window.
  Input:
    maintenance: skipper/maintenance;
  Output: boolean"
  ([maintenance] (not-maintenance maintenance (clj-time.core/now) ))
  ([maintenance ^DateTime time]
   (let [tz (clj-time.core/time-zone-for-id (:tz maintenance))
         current-time (clj-time.core/to-time-zone time tz)
         dow (nth [:monday
                   :tuesday
                   :wednesday
                   :thursday
                   :friday
                   :saturday
                   :sunday] (dec (clj-time.core/day-of-week current-time)))
         schedule (:schedule maintenance)]
     (if (dow schedule)
       (empty? (->> (dow schedule)
                         (filter (fn [t]
                                   (let [[from-hour from-min] (clojure.string/split (:from t) #":")
                                         [to-hour to-min] (clojure.string/split (:to t) #":")]
                                     (and (clj-time.core/after? current-time
                                                                (create-target-time current-time
                                                                                    (Integer/parseInt from-hour)
                                                                                    (Integer/parseInt from-min)
                                                                                    tz))
                                          (clj-time.core/before? current-time
                                                                 (create-target-time current-time
                                                                                     (Integer/parseInt to-hour)
                                                                                     (Integer/parseInt to-min)
                                                                                     tz))))))))
       true))))


(defn execute
  [ztx entry dry-run plan]
  (let [conn (connection ztx entry)
        common-labels (get-common-labels ztx entry)
        acc (atom {})
        force-deploy (get-in @ztx [:cli-params :force])]
    (try
      (doseq [executor (cond-> [:k8s] (is-helm? plan) (conj :helm))]
        (skipper.methods/init-executor ztx conn executor))
      (doseq [[raw-ns tps] plan]
        (let [ns (strip-ns raw-ns)
              ns-resource {:metadata {:name ns}}]
          (if dry-run
            (when-let [d (skipper.methods/diff ztx conn nil :Namespace ns ns-resource)]
              (swap! acc assoc-in [raw-ns :Namespace ns] d))
            ;; Create all namespaces
            (skipper.methods/exec ztx conn nil :Namespace ns ns-resource)))
        (let [maintenance-sym (-> (zen.core/get-symbol ztx raw-ns)
                              (get-in [:maintenance]))
              maintenance (when maintenance-sym
                            (zen.core/get-symbol ztx maintenance-sym))]
          (if (and (not dry-run) (not force-deploy) maintenance (not-maintenance maintenance))
            (klog.core/warn :cli/execute {:msg (str "[" raw-ns "]" " You cannot deploy changes outside maintenance window")})
            (doseq [[rt ress] tps]
              (doseq [[id res] ress]
                (let [res (if common-labels
                            (update-in res [:metadata :labels] merge common-labels)
                            res)]
                  (if dry-run
                    (when-let [d (skipper.methods/diff ztx conn raw-ns rt id res)]
                      (swap! acc assoc-in [raw-ns rt id] d))
                    (skipper.methods/exec ztx conn raw-ns rt id res))))))))
      (when (and dry-run (empty? @acc))
        (klog.core/info :cli/execute {:msg "Diff empty. No changes."}))
      @acc
      (finally
        (doseq [executor (cond-> [:k8s] (is-helm? plan) (conj :helm))]
          (skipper.methods/deinit-executor ztx conn executor))
        (klog.core/info :cli/execute {:msg (klog.core/green "Finish")})))))


(defn diff
  [ztx entry plan]
  (execute ztx entry true plan))


(defn reconcile
  [ztx entry & [bind]]
  (let [conn (skipper.core/connection ztx entry)
        config (->> (zen/get-symbol ztx entry)
                    :components
                    (reduce
                      (fn [acc cmp] (assoc acc cmp (zen/get-symbol ztx cmp)))
                      {})
                    (get-targets ztx))]
    (try
      (doseq [executor  [:k8s :helm]]
        (skipper.methods/init-executor ztx conn executor))
      (doseq [[name _cmp] config]
        (skipper.methods/reconcile ztx conn name (get bind name)))
      (finally
        (doseq [executor [:k8s :helm]]
          (skipper.methods/deinit-executor ztx conn executor))))))


(defn connect
  [ztx entry]
  (let [exec (get-in @ztx [:cli-params :exec])
        conn (connection ztx entry)
        cmds (skipper.executors.k8s/init-cmd (get-in @ztx [:cli-params]) conn)]
    (if exec
      (loop [cmd cmds
             result {}]
        (let [{status :status error :stderr success :stdout} (skipper.shell/exec {:exec (first cmd)})]
          (if (= 0 status)
            (let [is-result (get-in result [:result :report])
                  new-result (assoc-in result
                                       [:result :report]
                                       (str (get-in result [:result :report] "") (when is-result "\n")
                                            (clojure.string/join "\n" (prepare-exec-message success error))))]
              (if (empty? (rest cmd))
                new-result
                (recur (rest cmd) new-result)))
            {:error {:errors error
                     :report error}})))
      {:result {:report (clojure.string/join "\n" (map #(clojure.string/join " " %) cmds))}})))
