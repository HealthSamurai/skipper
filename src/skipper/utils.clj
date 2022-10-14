(ns skipper.utils
  (:require
    [clojure.string]
    [skipper.shell :as shell])
  (:import
    java.io.IOException
    java.net.Socket))


(defn service-name
  [id sid]
  (if (= sid :main)
    id
    (keyword (str (name id) "-" (name sid)))))


(defn host-name
  [id sid]
  (format "%s.%s.svc.cluster.local"
          (name (service-name id sid))
          (name id)))


(defn service-host
  [id port & [sid]]
  (format "%s.%s.svc.cluster.local:%s"
          (name (service-name id (or sid :main)))
          (name id)
          port))


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


(defn port-is-free
  [port]
  (try (with-open [^Socket _sc (Socket. "localhost" port)] false)
       (catch  IOException _ true)))


(defn wait-for
  [port sleep num]
  (loop [n 1]
    (println :connect {:attempt n :port port})
    (let [available (try (with-open [^Socket _sc (Socket. "localhost" port)] true)
                         (catch  IOException _ false))]
      (cond available :ok
            (< n num) (do
                        (Thread/sleep sleep)
                        (recur (inc n)))
            :else (throw (Exception. (str "Failed to connect to localhsot:" port)))))))


(defn port-forward
  [_ztx {port :port nsp :namespace service :service}]
  (if (port-is-free port)
    (let [cmd ["kubectl" "port-forward" (format "service/%s" service) (str "-n=" nsp) (format "%s:80" port)]]
      (println cmd)
      {:result (shell/run {:exec ["kubectl" "port-forward" (format "service/%s" service) (str "-n=" nsp) (format "%s:80" port)] :inherit-io true})})
    {:error (format "Port %s already used" port)}))


(defn file-path
  [^String path]
  (cond
    (clojure.string/starts-with? path "/")
    path
    (clojure.string/starts-with? path "~")
    (clojure.string/replace-first path "~" (System/getProperty "user.home"))
    :else
    (str (System/getProperty "infrabox.project") path)))


(defn slurp-resource
  [^String path]
  (->> path file-path slurp))
