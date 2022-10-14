(ns skipper.executors.helm
  (:require
    [cheshire.core]
    [skipper.shell :as shell]
    [klog.core]
    [clojure.string :as str]
    [skipper.executors.utils]
    [skipper.methods]
    [clojure.pprint]
    ))


(defn exec
  [args]
  (let [res (shell/exec {:exec args})]
    (klog.core/info :sk/helm {:msg (str "Helm: " (str/join " " args))})
    (when (not= 0 (:status res))
      (klog.core/error :sk/helm {:msg (str "Helm:" (with-out-str (clojure.pprint/pprint {:stderr (:stderr res)
                                                                                         :stdout (:stdout res)})))}))
    res))


(defmethod skipper.methods/init-executor
  :helm
  [ztx _ _]
  (let [res (exec ["helm" "list" "-A" "-o" "json"])
        out (first (:stdout res))
        pkg (->> (cheshire.core/parse-string out keyword)
                 (reduce (fn [acc x]
                           (assoc-in acc (map keyword [(:namespace x) (:name x)]) x)) {}))]
    (swap! ztx assoc :helm/packages pkg)))


(defmethod skipper.methods/deinit-executor
  :helm
  [_ _ _])

(defn keyword-to-str [k]
  (if (string? k)
    (str "\"" (str/replace k #"\." "\\\\.") "\"")
    (-> k str rest str/join (str/replace #"\." "\\\\.") )))


(defn *flat-path
  [path acc node]
  (cond (map? node)
        (reduce (fn [acc [k v]] (*flat-path (conj path  (keyword-to-str k)) acc v)) acc node)

        (vector? node)
        (apply merge (map-indexed (fn [idx v] (*flat-path (conj path  (str "[" idx "]")) acc v))  node))

        :else (assoc acc path node)))


(defn flat-path
  [node]
  (*flat-path [] {} node))


(defn fix-arrays [s] (str/replace s #"\.\[" "["))

(defn to-params
  [values]
  (->> values
       (flat-path)
       (reduce (fn [acc [k v]] (conj acc "--set" (str (fix-arrays (str/join "." k)) "=" v))) [])))


(defn strip-ns
  [x]
  (-> x name keyword))

(defn chart-values [ns id]
  (->
   (exec ["helm" "get" "values" (name id) "-n" (name ns) "-o" "json"])
   (:stdout)
   (first)
   (cheshire.core/parse-string keyword)))

(defn calculate-diff
  [ztx _ ns _rt id res]
  (let [pkg (get-in @ztx [:helm/packages (strip-ns ns) (strip-ns id)])]
    (if (nil? pkg)
      {:action :add}
      (let [version (last (str/split (:chart pkg) #"-"))
            d (skipper.executors.utils/difference
               {:version (:version res)
                :values (:values res)}
               {:version version
                :values (chart-values ns id)})]
        (when (not-empty d)
          {:action :change
           :diff d})))))

(defmethod skipper.methods/exec
  :helm
  [ztx _ ns rt id res]
  (if-let [d (calculate-diff ztx _ ns rt id res)]
    (do
      (if (= (:action d) :add)
        (klog.core/info :sk/helm {:msg (str "Create: " (name rt) " " (name (or ns "")) "/" (name id))})
        (klog.core/info :sk/helm {:msg (str (klog.core/yellow "[Change] ") (str (name rt) " " (name ns) "/" (name id)))}))
      (when-let [repo (:repo res)]
        (exec (into ["helm" "repo" "add"] repo))
        (exec ["helm" "repo" "update"]))
      (let [cmd (concat
                  ["helm"
                   "upgrade" "--install"
                   ;; "install"
                   (name id)
                   (str (when-let [r (first (:repo res))]
                          (str r "/"))
                        (:package res))
                   "--namespace" (name ns)]
                  (to-params (:values res))
                  (when-let [v (:version res)]
                    ["--version" v]))
            res (exec cmd)]
        res))
    (klog.core/info :sk/helm {:msg (str "[Helm] " "Unchanged:" (name ns) "/" (name id) res)})))

(defn print-diff
  [ns rt id {action :action diff :diff}]
  (cond
    (= :add action)
    (klog.core/info :sk/helm {:msg (str "Create: " (name rt) " " (name (or ns "")) "/" (name id))})
    (= :change action)
    (try
      (when (seq diff)
        (klog.core/info :sk/helm {:msg (str (klog.core/yellow "[Change] ") (str (name rt) " " (name ns) "/" (name id)))})
        (doseq [d diff]
          (klog.core/warn :sk/diff-title {:msg ["-"  "Path:" "[" (str/join " " (:path d)) "]"]})
          (klog.core/warn :sk/diff {:msg {:old (:old d)
                                          :new (:value d)}})))
      (catch Exception e (println e)))))

(defmethod skipper.methods/diff
  :helm
  [ztx _ ns rt id res]
  (klog.core/info :sk/helm {:msg (str (klog.core/green "[Helm] ") ns)})
  (when-let [d (calculate-diff ztx _ ns rt id res)]
    (print-diff ns rt id d) d))
