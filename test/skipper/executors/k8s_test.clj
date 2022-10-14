(ns skipper.executors.k8s-test
  (:require
   [skipper.executors.k8s :as sut]
   [skipper.executors.utils]
   [clojure.test :as t]
   [matcho.core :as matcho]
   [cheshire.core]))

(def old-resource
  (-> 
   {"kind" "StatefulSet",
    "apiVersion" "apps/v1",
    "metadata" {"name" "grafana",
                "namespace" "grafana",
                "uid" "c41e6a2b-546d-4412-aff9-ea00efd5ffa1",
                "resourceVersion" "57459",
                "generation" 1,
                "creationTimestamp" "2021-12-27T16:40:02Z",
                "managedFields" [{"manager" "kubectl",
                                  "operation" "Apply",
                                  "apiVersion" "apps/v1",
                                  "time" "2021-12-27T16:41:38Z",
                                  "fieldsType" "FieldsV1",
                                  "fieldsV1" {"f:spec"
                                              {"f:replicas" {},
                                               "f:selector" {},
                                               "f:template"
                                               {"f:metadata" {"f:labels" {"f:service" {}}},
                                                "f:spec"
                                                {"f:containers"
                                                 {"k:{\"name\":\"main\"}"
                                                  {"f:envFrom" {},
                                                   "f:volumeMounts"
                                                   {"k:{\"mountPath\":\"/var/lib/grafana\"}"
                                                    {"." {}, "f:mountPath" {}, "f:name" {}}},
                                                   "f:env"
                                                   {"k:{\"name\":\"config_version\"}"
                                                    {"." {}, "f:name" {}, "f:value" {}},
                                                    "k:{\"name\":\"files_version\"}"
                                                    {"." {}, "f:name" {}, "f:value" {}},
                                                    "k:{\"name\":\"secrets_version\"}"
                                                    {"." {}, "f:name" {}, "f:value" {}}},
                                                   "f:name" {},
                                                   "f:imagePullPolicy" {},
                                                   "f:resources" {},
                                                   "f:command" {},
                                                   "f:args" {},
                                                   "." {},
                                                   "f:image" {}}},
                                                 "f:securityContext" {},
                                                 "f:serviceAccount" {},
                                                 "f:volumes"
                                                 {"k:{\"name\":\"data\"}"
                                                  {"." {},
                                                   "f:name" {},
                                                   "f:persistentVolumeClaim" {"f:claimName" {}}}}}}}}}]},
    "spec"
    {"replicas" 1,
     "selector" {"matchLabels" {"service" "grafana"}},
     "template"
     {"metadata" {"creationTimestamp" nil, "labels" {"service" "grafana"}},
      "spec"
      {"volumes" [{"name" "data", "persistentVolumeClaim" {"claimName" "data"}}],
       "containers"
       [{"terminationMessagePolicy" "File",
         "terminationMessagePath" "/dev/termination-log",
         "image" "grafana/grafana:8.3.0",
         "name" "main",
         "args" ["old"]
         "command" "old-cmd"
         "ports" [{"port" 8080}]
         "env"
         [{"name" "config_version", "value" "v0"}
          {"name" "files_version", "value" "v-15128758"}
          {"name" "secrets_version", "value" "v0"}],
         "volumeMounts" [{"name" "data", "mountPath" "/var/lib/grafana"}],
         "resources" {"memory" "10"},
         "imagePullPolicy" "Always"}],
       "restartPolicy" "Always",
       "terminationGracePeriodSeconds" 30,
       "dnsPolicy" "ClusterFirst",
       "schedulerName" "default-scheduler"}},
     "serviceName" "",
     "podManagementPolicy" "OrderedReady",
     "updateStrategy" {"type" "RollingUpdate", "rollingUpdate" {"partition" 0}},
     "revisionHistoryLimit" 10},
    "status" {"replicas" 0}}
   (cheshire.core/generate-string)
   (cheshire.core/parse-string keyword)))

(def service 
  (-> 
   {"apiVersion" "v1",
    "kind" "Service",
    "metadata" {"annotations" {"cloud.google.com/neg" "{\"ingress\":true}"},
                "creationTimestamp" "2022-01-14T15:17:51Z",
                "managedFields" [{"apiVersion" "v1",
                                  "fieldsType" "FieldsV1",
                                  "fieldsV1" {"f:spec" {"f:ports" {"k:{\"port\":80,\"protocol\":\"TCP\"}" {"." {},
                                                                                                           "f:port" {},
                                                                                                           "f:protocol" {},
                                                                                                           "f:targetPort" {}}},
                                                        "f:selector" {"f:service" {}}}}, "manager" "kubectl",
                                  "operation" "Apply",
                                  "time" "2022-01-14T15:17:51Z"}],
                "name" "grafana",
                "namespace" "grafana",
                "resourceVersion" "9841635",
                "uid" "874fa14c-beb2-4b17-886f-3262f38827d2"},
    "spec" {"clusterIP" "10.80.26.150",
            "clusterIPs" ["10.80.26.150"],
            "ipFamilies" ["IPv4"],
            "ipFamilyPolicy" "SingleStack",
            "ports" [{"port" 80,
                      "protocol" "TCP",
                      "targetPort" 3000}],
            "selector" {"service" "grafana"},
            "sessionAffinity" "None",
            "type" "ClusterIP"},
    "status" {"loadBalancer" {}}}
   (cheshire.core/generate-string)
   (cheshire.core/parse-string keyword)))
   

(def new
  {:spec
   {:replicas 1,
    :selector {:matchLabels {:service "grafana"}},
    :template
    {:metadata {:labels {:service "grafana"}},
     :spec
     {:containers
      [{:args ["changed"]
        :volumeMounts [{:name "data", :mountPath "/var/lib/grafana"},]
        :name "main",
        :resources {:memory "30"}
        :command "changed"
        :env
        [{:name "config_version", :value "v0"}
         {:name "files_version", :value "v-15128758"}
         {:name "secrets_version", :value "v0"},]
        :ports [{:port 8081}],
        :imagePullPolicy "Always",
        :image "grafana/grafana:8.3.0",}],
      :volumes [{:name "data", :persistentVolumeClaim {:claimName "changed"}}]}}}})


(t/deftest test-skipper-executors-k8s
  (def managed-flds (sut/managed-fields old-resource))


  (matcho/match
   managed-flds
   {:spec
    {:replicas {},
     :selector {},
     :template
     {:metadata {:labels {:service {}}},
      :spec
      {:containers
       {:type :vector,
        :slices
        {{:name "main"}
         {:args {},
          :volumeMounts
          {:type :vector,
           :slices {{:mountPath "/var/lib/grafana"} {:mountPath {}, :name {}}}},
          :envFrom {},
          :name {},
          :command {},
          :env
          {:type :vector,
           :slices
           {{:name "config_version"} {:name {}, :value {}},
            {:name "files_version"} {:name {}, :value {}},
            {:name "secrets_version"} {:name {}, :value {}}}},
          :imagePullPolicy {},
          :image {},
          :resources {}}}},
       :securityContext {},
       :serviceAccount {},
       :volumes
       {:type :vector,
        :slices
        {{:name "data"} {:name {}, :persistentVolumeClaim {:claimName {}}}}}}}}})

  (matcho/match
   (sut/managed-fields service)
   {:spec
    {:ports
     {:type :vector,
      :slices
      {{:port 80, :protocol "TCP"} {:port {}, :protocol {}, :targetPort {}}}},
     :selector {:service {}}}})
  
  (matcho/match
   (sut/calculate-diff {} {:a 1} {:a 1})
   empty?)

  (matcho/match
   (sut/calculate-diff {} {:a 1} {:a 2})
   [{:action :change, :path [:a], :old 1, :value 2}])

  (matcho/match
   (sut/calculate-diff {} {:b 1} {:a 2})
   [{:action :add, :path [:a], :value 2}])

  (matcho/match
   (sut/calculate-diff {:x {}} {:x 1} {})
   [{:action :remove, :path [:x], :value nil, :old 1}])


  (t/is (sut/do-match {:a 1} {:a 1 :b 2}))
  (t/is (not (sut/do-match {:a 1} {:a 2 :b 2})))

  (t/is (=
         {:a 1 :b 2}
         (sut/find-matched {:a 1} [{:a 2} {:b 4} {:a 1 :b 2}])))

  (t/is (=
         (sut/diff-index-by {{:a 1} {} {:b 2} {}}
                            [{:a 2} {:a 1 :ok true} {:b 1} {:b 2 :ok true}])

         {{:a 1} {:a 1, :ok true}, {:b 2} {:b 2, :ok true}}
         ))

  (matcho/match
   (sut/calculate-diff {:spec {:image {} :removed {}}} {:spec {:image "a" :removed "x" :ignored "y"}} {:spec {:image "b" :volume "v"}})
   [{:action :add, :path [:spec :volume], :value "v"}
    {:action :remove, :path [:spec :removed], :value nil, :old "x"}
    {:action :change, :path [:spec :image], :old "a", :value "b"}
    nil])


  (matcho/match
   (sut/calculate-diff {:env {:slices {{:name "v1"} {:name {} :value {}}}}}
                       {:env [{:name "v1" :value "v1"}]}
                       {:env [{:name "v1" :value "v2"}]})
   [{:action :change, :path [:env {:name "v1"} :value], :old "v1", :value "v2"}])

  (matcho/match
   (sut/calculate-diff {:env {:slices {{:name "v1"} {:name {} :value {}}}}}
                       {:env [{:name "v1" :value "v1"}]}
                       {:env [{:name "v2" :value "v2"}]})

   [{:action :add, :path [:env {:name "v2"}], :value {:value "v2"}}
    {:action :remove, :path [:env {:name "v1"}], :value nil, :old {:name "v1", :value "v1"}}])


  (matcho/match
   (sut/calculate-diff managed-flds old-resource new)
   [{:action :change,
     :path [:spec :template :spec :containers {:name "main"} :args],
     :value ["changed"] :old ["old"]}
    {:action :change,
     :path [:spec :template :spec :containers {:name "main"} :command],
     :old "old-cmd", :value "changed"}
    {:action :change,
     :path [:spec :template :spec :containers {:name "main"} :ports],
     :value [{:port 8081}], :old [{:port 8080}]}
    {:action :change,
     :path [:spec :template :spec :containers {:name "main"} :resources :memory],
     :old "10", :value "30"}
    {:action :change,
     :path [:spec :template :spec :volumes {:name "data"} :persistentVolumeClaim :claimName],
     :old "data", :value "changed"}])



  )


(t/deftest diff

  (def sample
    {:defaults
     {:color {:mode "thresholds"},
      :custom {:align "auto", :displayMode "auto"},
      :decimals 0,
      :mappings [],
      :thresholds
      {:mode "absolute",
       :steps [{:color "green", :value nil} {:color "red", :value 80}]},
      :unit "none"},
     :overrides
     [{:matcher {:id "byName", :options "Total time"},
       :properties [{:id "unit", :value "s"}]}
      {:matcher {:id "byName", :options "Count"},
       :properties [{:id "unit", :value "short"}]}
      {:matcher {:id "byName", :options "Mean"},
       :properties [{:id "unit", :value "s"}]}
      {:matcher {:id "byName", :options "Value #99 percentile"},
       :properties
       [{:id "thresholds",
         :value
         {:mode "absolute",
          :steps
          [{:color "green", :value nil}
           {:color "#EAB839", :value 0.1}
           {:color "super-light-red", :value 0.5}
           {:color "red", :value 1}]}}
        {:id "unit", :value "s"}]}]})

  (def sample'
    {:defaults
     {:color {:mode "thresholds"},
      :custom {:align "auto", :displayMode "auto"},
      :decimals 0,
      :mappings [],
      :thresholds
      {:mode "absoluteheyhop",
       :steps [{:color "green", :value nil} {:color "red", :value 80}]},
      :unit "none"},
     :overrides
     [{:matcher {:id "byName", :options "Total time"},
       :properties [{:id "unit", :value "s"}]}
      {:matcher {:id "byName", },
       :properties [{:id "unit", :value "short"}]}
      {:matcher {:id 2, :options "Mean"},
       :properties [{:id "unit", :value "s" :extra "extra"}]}]})

  (matcho/match
   (skipper.executors.utils/difference [{:foo "bar"} ] [])
   [{:value {:foo "bar"}, :old nil, :path [0]}])

  (matcho/match
   (skipper.executors.utils/difference [{:foo "bar"} ] nil)
   [{:value [{:foo "bar"}] :old nil :path []}
    nil]
   )


  (matcho/match
   (skipper.executors.utils/difference sample sample')

   [{:value "absolute",
     :old "absoluteheyhop",
     :path [:defaults :thresholds :mode]}
    {:value "byName", :old 2, :path [:overrides 2 :matcher :id]}
    {:value nil, :old "extra", :path [:overrides 2 :properties 0 :extra]}
    {:value "Count", :old nil :path [:overrides 1 :matcher :options]}
    {:old nil,
     :value
     {:matcher {:id "byName", :options "Value #99 percentile"},
      :properties
      [{:id "thresholds",
        :value
        {:mode "absolute",
         :steps
         [{:color "green", :value nil}
          {:color "#EAB839", :value 0.1}
          {:color "super-light-red", :value 0.5}
          {:color "red", :value 1}]}}
       {:id "unit", :value "s"}]},
     :path [:overrides 3]}
    nil])

  (matcho/match
   (skipper.executors.utils/difference "hey" "hop")
   [{:value "hey", :old "hop", :path []} nil])

  )


(t/deftest resolve-k8s-context

  (t/testing "resolve kind context"

    (t/is (= (sut/resolve-k8s-context {:cloud   {:engine 'skipper/gcp
                                                 :project "aidbox-cloud-demo"
                                                 :region  "us-central1-c"}
                                       :cluster {:engine 'skipper/k8s
                                                 :cloud 'cloud
                                                 :name "infrabox"}})
            {:context  "gke_aidbox-cloud-demo_us-central1-c_infrabox"
             :cmd ["kubectl"
                   "config"
                   "use-context"
                   "gke_aidbox-cloud-demo_us-central1-c_infrabox"]}))

    (t/is (= (sut/resolve-k8s-context {:cloud   {:engine 'skipper/gcp
                                                 :project "aidbox-cloud-demo"
                                                 :region  "us-central1-c"}
                                       :cluster {:engine 'skipper/k8s
                                                 :cloud 'cloud
                                                 :name "infrabox"}})
             {:context  "gke_aidbox-cloud-demo_us-central1-c_infrabox"
              :cmd ["kubectl"
                    "config"
                    "use-context"
                    "gke_aidbox-cloud-demo_us-central1-c_infrabox"]}))

    (t/is (= (sut/resolve-k8s-context {:cluster {:engine 'skipper/k8s
                                                 :context "kind-kind"}})
             {:context  "kind-kind"
              :cmd ["kubectl"
                    "config"
                    "use-context"
                    "kind-kind"]}))


    (t/is (= (sut/resolve-k8s-context {:cloud   {:engine 'skipper/aws
                                                 :project "aidbox-cloud-demo"
                                                 :account "1111111111"
                                                 :region  "us-west-2"}
                                       :cluster {:engine 'skipper/k8s
                                                 :cloud 'cloud
                                                 :name "infrabox"}})
             {:context  "arn:aws:eks:us-west-2:1111111111:cluster/infrabox"
              :cmd ["kubectl"
                    "config"
                    "use-context"
                    "arn:aws:eks:us-west-2:1111111111:cluster/infrabox"]}))

    )
  )
