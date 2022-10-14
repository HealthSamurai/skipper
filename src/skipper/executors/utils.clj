(ns skipper.executors.utils)

(defn right-diff
  [errors path left right]
  (cond
    (and (map? left)
         (map? right))
    (reduce (fn [errors [k v]]
              (let [path (conj path k)
                    ev   (get left k)]
                (right-diff errors path ev v)))
            errors right)
    (and (sequential? right)
         (sequential? left))
    (reduce (fn [errors [k v]]
              (let [path (conj path k)
                    ev   (nth (vec left) k nil)]
                (right-diff errors path ev v)))
            errors
            (map (fn [left i] [i left]) right (range)))

    :else (if (= right left)
            errors
            (if right
              (conj errors (assoc {:value left :old right} :path path))
              errors))))


(defn difference
  [new old]
  (into (right-diff [] [] new old)
        (->> (right-diff [] [] old new)
             (filter #(nil? (:value %)))
             (map (fn [x] (merge x {:old (:value x) :value (:old x)}))))))
