(ns skipper.shell
  (:require
    [clojure.java.io :as io]))


(defn read-env
  [override]
  (->
    (->> (System/getenv)
         (reduce (fn [acc [k v]]
                   (assoc acc (keyword k) v)) {}))
    (merge override)))


(defn read-stream
  [s]
  (let [r (io/reader s)]
    (loop [acc []]
      (if-let [l (.readLine r)]
        (recur (conj acc l))
        acc))))


(defn proc
  [{dir :dir env :env inh :inherit-io args :exec}]
  (let [proc (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String args))
        _ (when dir (.directory proc (io/file dir)))
        _ (when inh (.inheritIO proc))
        _ (when env
            (let [e (.environment proc)]
              #_(.clear e)
              (doseq [[k v] env]
                (.put e (name k) (str v)))))]
    proc))


(defn exec
  [{_dir :dir _env :env _args :exec :as opts}]
  (let [prc (proc opts)
        p (.start ^ProcessBuilder prc)]
    (.waitFor p)
    {:status (.exitValue p)
     :stdout (read-stream (.getInputStream p))
     :stderr (read-stream (.getErrorStream p))}))


(defn run
  [{_dir :dir _env :env _args :exec :as opts}]
  (let [prc (proc opts)]
    (.start ^ProcessBuilder prc)))


(comment
  (exec {:exec ["git" "status"]})
  (def conn
    (aidbox-ops.pg.utils/connection {:user "postgres"
                                     :port 7777
                                     :host "localhost"
                                     :database "postgres"
                                     :password "UKMIEGKRSLLQGXATELOX"}))
  conn
  (defn strip-nils [x] (->> x (reduce (fn [acc [k v]] (if (nil? v) (dissoc acc k) acc))  x)))
  (->>
   (aidbox-ops.pg.utils/query conn "select * from pg_settings where source <> 'session'")
   (mapv strip-nils)
   (reduce (fn [acc {nm :name :as prop}]
             (assoc acc (keyword nm)
                    ;;prop
                    (cond-> {:val (:reset_val prop)}
                      (not (= (:reset_val prop) (:boot_val prop)))
                      (assoc :boot_val (:boot_val prop))
                      (:pending_restart prop) (assoc :pending_restart true))))
           {}))

  (exec {:exec ["docker" "ps"]})
  (exec {:exec ["docker" "exec" "blue" "ls" "/"]})
  )
