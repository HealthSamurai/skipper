(ns skipper.resources.core-test
  (:require [skipper.core]
            [skipper.methods]
            [matcho.core :as matcho]
            [clojure.test :as t]))

(t/deftest skipper-resources-test
  (matcho/match
   (skipper.methods/expand
    :skipper.res/stateless
    :aidbox {:image   "aidboxone:edge"
             :http    {:api     {:host "mybox.com" :port 8080}
                       :metrics {:port 8765}}
             :configs {:AIDBOX_PORT 8080}
             :secrets {:AIDBOX_CLIENT_ID "root"
                       :AIDBOX_CLIENT_SECRET "secret"}})

   {})

  (matcho/match
   (skipper.core/clear-empty
    (skipper.methods/expand
     :skipper.res/stateful
     :grafana
     {:image "gr"
      :http {:web {:port 3000 :host "host"}}
      :init [{:command ["sh", "-c", "chown 777 /var/lib/grafana"]}]
      :volumes {:data {:mount  "/var/lib/grafana"
                       :storage (str 10 "Gi")}}}))

   {})

  )
