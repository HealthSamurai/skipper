(ns test-utils
  (:require [skipper.core]))

(defn zen-merge [a b]
  (skipper.core/deep-merge a b))
