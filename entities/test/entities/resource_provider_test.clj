; Copyright 2018-2022 Rahul De
;
; Use of this source code is governed by an MIT-style
; license that can be found in the LICENSE file or at
; https://opensource.org/licenses/MIT.

(ns entities.resource-provider-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as spec]
            [xtdb.api :as xt]
            [common.schemas]
            [entities.util :as u]
            [entities.resource-provider :as resource-provider]))

;; TODO: Better way to wait for consistency than sleep
(deftest ^:integration resource-provider
  (let [id :bob.resource-provider/github]
    (testing "creation"
      (u/with-system
        (fn [db queue-chan]
          (let [resource-provider {:name "github"
                                   :url  "my.resource.com"}
                create-res        (resource-provider/register-resource-provider db queue-chan resource-provider)
                _ (Thread/sleep 1000)
                effect            (xt/entity (xt/db db) id)]
            (is (= "Ok" create-res))
            (is (= id (:xt/id effect)))
            (u/spec-assert :bob.db/resource-provider effect)))))
    (testing "deletion"
      (u/with-system
        (fn [db queue-chan]
          (let [resource-provider {:name "github"}
                delete-res        (resource-provider/un-register-resource-provider db queue-chan resource-provider)
                _ (Thread/sleep 1000)
                effect            (xt/entity (xt/db db) id)]
            (is (= "Ok" delete-res))
            (is (nil? effect))))))))
