(ns zen.ops.k8s.openapi-test
  (:require [infrabox.zen.ops.k8s.openapi :as sut]
            [zen.core :as zen]
            [infrabox.zen.ops.k8s.core :as k8s]
            [matcho.core :as matcho]
            [clojure.string :as str]
            [clojure.test :as t]))

(defn read-op [ztx tp]
  (zen/get-symbol ztx (sut/api-name "read" {:k8s/type tp})))

(t/deftest ^:todo test-swagger-to-zen

  (def ztx (zen/new-context {}))
  (k8s/init-context ztx {})

  (def errors (take 10 (zen/errors ztx)))
  (t/is (empty? errors))

  errors

  (comment
    (sut/list-ops ztx)

    (sut/list-schemas ztx)
    )

  (matcho/match
    (zen/get-symbol ztx 'k8s.v1/Pod)
    {:zen/tags #{'zen/schema 'k8s/schema 'k8s/resource},
     :zen/name 'k8s.v1/Pod})

  (sut/describe ztx 'k8s.v1/Pod)

  (matcho/match
    (zen/get-symbol ztx 'k8s.v1.Pod/list)
    {:zen/tags #{'k8s/op},
     :zen/name 'k8s.v1.Pod/list})

  (t/is (seq (zen/get-tag ztx 'k8s/op)))
  (t/is (seq (zen/get-tag ztx 'k8s/schema)))

  (matcho/match
    (zen/validate ztx #{'k8s.v1/Pod} {:metadata {:name 1}})
    {:errors
     [{:message "Expected type of 'string, got 'long"
       :type    "string.type"
       :path    [:metadata :name]}]})

  (matcho/match
   (sut/validate
    ztx
    {:apiVersion "apps/v1",
     :kind       "Deployment",
     :metadata   {:name "nginx-deployment", :labels {:app "nginx"}},
     :spec
     {:replicas 3,
      :selector {:matchLabels {:app "nginx"}},
      :template
      {:metadata {:labels {:app "nginx"}},
       :spec
       {:containers
        [{:name  "nginx",
          :image "nginx:1.14.2",
          :ports [{:containerPort 80}]}]}}}})

   {:errors empty?})

  (matcho/match
     (sut/validate
      ztx
      {:apiVersion "apps/v1",
       :kind       "Deployment",
       :metadata   {:name "nginx-deployment", :labels {:app "nginx"}},
       :spec
       {:replicas 3,
        :selector {:matchLabels {:app "nginx"}},
        :template
        {:metadata {:labels {:app "nginx"}},
         :spec
         {:containers
          [{:name  "nginx",
            :image "nginx:1.14.2",
            :ports [{:containerPort "80"}]}]}}}})
     {:errors
      [{:message "Expected type of 'integer, got 'string",
        :type    "primitive-type",
        :path    [:spec :template :spec :containers 0 :ports 0 :containerPort],}]})

  (sut/list-schemas ztx "custom")

  (matcho/match
   (sut/validate
    ztx
    {:apiVersion "apiextensions.k8s.io/v1",
     :kind       "CustomResourceDefinition",
     :metadata   {:name "crontabs.stable.example.com"},
     :spec       {:group    "stable.example.com",
                  :versions [{:name    "v1",
                              :served  true,
                              :storage true,
                              :schema
                              {:openAPIV3Schema
                               {:type       "object",
                                :properties {:spec
                                             {:type       "object",
                                              :properties {:cronSpec {:type "string"},
                                                           :image    {:type "string"},
                                                           :replicas {:type "integer"}}}}}}}],
                  :scope    "Namespaced",
                  :names    {:plural     "crontabs",
                             :singular   "crontab",
                             :kind       "CronTab",
                             :shortNames ["ct"]}}})

   {:errors empty?})

  (matcho/match
   (sut/validate
    ztx
    {:apiVersion "apiextensions.k8s.io/v1",
     :kind       "CustomResourceDefinition",
     :metadata   {:name "crontabs.stable.example.com"},
     :spec       {:group    "stable.example.com",
                  :versions [{:name    "v1",
                              :served  true,
                              :storage true,
                              :schema
                              {:openAPIV3Schema
                               {:type       "object",
                                :properties {:spec
                                             {:type       "object",
                                              :properties {:cronSpec {:type "string"},
                                                           :image    {:type "string"},
                                                           :replicas {:type "integer"}}}}}}}],
                  :scope    "Namespaced",
                  :names    {:plural     "crontabs",
                             :singularUps   "crontab",
                             :kind       "CronTab",
                             :shortNames ["ct"]}}})

   {:errors
    [{:type "unknown-key",
      :message "unknown key :singularUps",
      :path [:spec :names :singularUps]}]})

  (matcho/match
   (zen/get-symbol ztx 'k8s.apps.v1.Deployment/create)

   {:zen/tags #{'k8s/op},
    :zen/desc "create a Deployment",
    :k8s/api {:group "apps", :kind "Deployment", :version "v1"},
    :k8s/oid "createAppsV1NamespacedDeployment"
    :k8s/action "post" 
    :openapi/method :post
    :openapi/url ["apis" "apps" "v1" "namespaces" :namespace "deployments"],
    :params
    {:type 'zen/map
     :require #{:body :namespace}
     :keys {:body {:openapi/in "body"
                   :confirms #{'k8s.apps.v1/Deployment}}
            :dryRun {:zen/desc #"When present"
                     :type 'zen/string
                     :k8s/uniqueItems true
                     :openapi/in "query"}
            :fieldManager {:zen/desc #"fieldManager is a"
                           :type 'zen/string
                           :openapi/in "query"
                           :k8s/uniqueItems true}
            :namespace {:zen/desc #"object name"
                        :openapi/in "path"
                        :type 'zen/string
                        :k8s/uniqueItems true}
            :pretty {:zen/desc #"If 'true'"
                     :type 'zen/string
                     :openapi/in "query"
                     :k8s/uniqueItems true}}}
    ;; :result {:confirms #{'k8s.apps.v1/Deployment}}
    })


  (matcho/match
   (sut/build-request ztx {:method 'k8s.v1.Pod/create
                           :params {:body {:metadata {:name "name"}}
                                    :namespace "default"}})

   {:body "{\"metadata\":{\"name\":\"name\"}}",
    :url "api/v1/namespaces/default/pods",
    :method :post})

  (matcho/match
   (sut/build-request ztx {:method 'k8s.v1.Pod/list
                           :params {:namespace "default"}})

   {:url "api/v1/namespaces/default/pods", :method :get})

  (t/is
   (zen/get-symbol
    ztx
    (sut/api-name "read" {:apiVersion "apps/v1",
                          :kind       "Deployment",
                          :metadata   {:name "nginx-deployment", :labels {:app "nginx"}},
                          :spec {}})))

  (def dop (read-op ztx 'k8s.apps.v1/Deployment))
  (t/is dop)

  (def crop (read-op ztx 'k8s.rbac.authorization.k8s.io.v1/ClusterRole))

  (t/is crop)
  (sut/list-ops ztx "ingres")

  (def inop (read-op ztx 'k8s.networking.k8s.io.v1/Ingress))
  (t/is inop)

  (def eop (read-op ztx 'k8s.events.k8s.io.v1/Event))
  (t/is eop)


  ;; k8s.rbac.authorization.k8s.io.v1.ClusterRole/delete
  (sut/list-ops ztx "cronjob")

  (sut/list-resources ztx "ingres")

  ;; k8s.batch.api.k8s.io.v1/Job

  ;; k8s.events.api.k8s.io.v1/Event
  ;; k8s.events.k8s.io.v1.Event/create

  (sut/list-ops ztx "Pod")


  (matcho/match
   (zen/get-symbol ztx 'k8s.v1.Pod/list)
   (sut/gen-list-def ztx {:k8s/type 'k8s.v1/Pod}))


  (sut/gen-list-def ztx {:k8s/type 'k8s.networking.k8s.io.v1/Ingress})

  (sut/gen-list-def ztx {:k8s/type 'k8s.apps.v1/Deployment})
  (sut/gen-read-def ztx {:k8s/type 'k8s.apps.v1/Deployment})

  (matcho/match
   (zen/get-symbol ztx 'k8s.apps.v1.Deployment/read)
   (sut/gen-read-def ztx {:k8s/type 'k8s.apps.v1/Deployment}))

  (sut/gen-list-all-def ztx {:k8s/type 'k8s.apps.v1/Deployment})



  (matcho/match
   (zen/get-symbol ztx 'k8s.apps.v1.Deployment/create)
   (sut/gen-create-def ztx {:k8s/type 'k8s.apps.v1/Deployment}))

  (matcho/match
   (zen/get-symbol ztx 'k8s.apps.v1.Deployment/replace)
   (sut/gen-replace-def ztx {:k8s/type 'k8s.apps.v1/Deployment}))


  (matcho/match
   (zen/get-symbol ztx 'k8s.v1.Service/patch)
   (dissoc 
    (sut/gen-patch-def ztx {:k8s/type 'k8s.v1/Service})
    :openapi/content-type))


  (sut/list-ops ztx "deployment")

  (matcho/match
   (zen/get-symbol ztx 'k8s.apps.v1.Deployment/replace-status)
   (sut/gen-replace-status-def ztx {:k8s/type 'k8s.apps.v1/Deployment}))

  (zen/get-symbol ztx 'k8s.v1/Patch)

  (matcho/match
   (zen/get-symbol ztx 'k8s.apps.v1.Deployment/patch-status)

   (sut/gen-patch-status-def ztx {:k8s/type 'k8s.apps.v1/Deployment})
   )


  (matcho/match
   (zen/get-symbol ztx 'k8s.apiextensions.k8s.io.v1.CustomResourceDefinition/delete)
   (sut/gen-delete-all-def ztx {:k8s/type 'k8s.apiextensions.k8s.io.v1/CustomResourceDefinition})
   )

  ;; k8s.networking.api.k8s.io.v1/Ingress
  (doseq [rn (take 100 (sut/list-resources ztx))]
    (let [r (zen/get-symbol ztx rn)]
      (when (and (= (name rn) (get-in r [:k8s/api 0 :kind]))
                 (not (str/ends-with? (name rn) "List")))
        (when-not (zen/get-symbol ztx (sut/api-name "read" {:k8s/type rn}))
          (println :no-read rn)))))


  
  ;; (def k8s (cheshire.core/parse-string (slurp (clojure.java.io/resource "k8s-swagger.json")) keyword))

  ;; (def grps-map (sut/build-groups-idx k8s))


  )
