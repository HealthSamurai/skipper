(ns skipper.formatters.core
  (:require
    [cheshire.core]
    [clj-yaml.core]
    [clojure.string :as str]
    [skipper.methods :refer [formatter]]))


(defmethod formatter
  :yaml [_ data]
  (clj-yaml.core/generate-string data))


(defmethod formatter
  :json [_ data]
  (cheshire.core/generate-string data))


(defmethod formatter
  :pg/config
  [_ data]
  (->> data
       (mapv (fn [[k v]] (format "%s = %s" (name k) v)))
       (sort)
       (str/join "\n")))


(defmethod formatter
  :env
  [_ data]
  (->> data
       (filter (fn [[_ v]] (not (nil? v))))
       (map (fn [[k v]] {:name (name k) :value (str v)}))
       (sort-by (juxt :name :value))))

(defmethod formatter
  :field-ref
  [_ data]
  (->> data
       (filter (fn [[_ v]] (not (nil? v))))
       (map (fn [[k v]] {:name (name k) :valueFrom {:fieldRef {:fieldPath v}}}))
       (sort-by (juxt :name :valueFrom))))

(defmethod formatter
  :resource-field-ref
  [_ data]
  (->> data
       (filter (fn [[_ v]] (not (nil? v))))
       (map (fn [[k v]] {:name (name k) :valueFrom {:resourceFieldRef {:fieldPath v}}}))
       (sort-by (juxt :name :valueFrom))))

(defmethod formatter
  :cli/options [_ data]
  (->> data
       (mapv (fn [[k v]]
               (if v
                 (format "--%s=%s" (name k) v)
                 (format "--%s" (name k)))))
       (sort)))

(defmethod formatter
  :cli/params [_ data]
  (->> data
       (mapv (fn [[k v]]
               (if v
                 (format "-%s=%s" (name k) v)
                 (format "-%s" (name k)))))
       (sort)))


(defmethod formatter
  :ini [_ data]
  (with-out-str
    (doseq [[sec vars] data]
      (println (format "[%s]" (name sec)))
      (doseq [[k v] vars]
        (println (format "%s=%s" (name k) v))))))
