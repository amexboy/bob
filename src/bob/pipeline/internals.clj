;   This file is part of Bob.
;
;   Bob is free software: you can redistribute it and/or modify
;   it under the terms of the GNU General Public License as published by
;   the Free Software Foundation, either version 3 of the License, or
;   (at your option) any later version.
;
;   Bob is distributed in the hope that it will be useful,
;   but WITHOUT ANY WARRANTY; without even the implied warranty of
;   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
;   GNU General Public License for more details.
;
;   You should have received a copy of the GNU General Public License
;   along with Bob. If not, see <http://www.gnu.org/licenses/>.

(ns bob.pipeline.internals
  (:require [clojure.core.async :refer [go]]
            [korma.db :refer [defdb]]
            [korma.core :refer [update set-fields where
                                insert values]]
            [failjure.core :as f]
            [bob.db.core :refer [steps runs]]
            [bob.execution.internals :as e]
            [bob.util :refer [unsafe! format-id get-id]])
  (:import (java.util List)))

;; TODO: Extract DB stuff

(defn update-pid
  [pid id]
  (f/attempt-all [_ (unsafe! (update steps
                                     (set-fields {:pid pid})
                                     (where {:id id})))]
    pid
    (f/when-failed [err] err)))

;; TODO: Can optimize the multiple (config-of) calls

(defn- next-step
  [^String id ^List next-command]
  (let [repo (format "%s/%d" id (System/currentTimeMillis))
        tag  "latest"]
    (f/attempt-all [_  (unsafe! (.commitContainer e/docker
                                                  id
                                                  repo
                                                  tag
                                                  (e/config-of (-> e/docker
                                                                   (.inspectContainer id)
                                                                   (.config)
                                                                   (.image))
                                                               next-command)
                                                  nil
                                                  nil))
                    id (e/build (format "%s:%s" repo tag) next-command)]
      (format-id id)
      (f/when-failed [err] err))))

(defn- exec-step
  [id step]
  (if (f/failed? id)
    (reduced id)
    (f/attempt-all [result (f/ok-> (next-step id (:cmd step))
                                   (update-pid (:id step))
                                   (e/run))]

      result
      (f/when-failed [err] err))))

(defn exec-steps
  [^String image ^List steps ^String name]
  (let [run-id (get-id)]
    (go (f/attempt-all [_  (unsafe! (insert runs (values {:id       run-id
                                                          :pipeline name
                                                          :status   "running"})))
                        id (f/ok-> (e/pull image)
                                   (e/build (:cmd (first steps)))
                                   (update-pid (:id (first steps)))
                                   (e/run))
                        id (reduce exec-step id (rest steps))
                        _  (unsafe! (update runs
                                            (set-fields {:status "passed"})
                                            (where {:id run-id})))]
          id
          (f/when-failed [err] (do (unsafe! (update runs
                                                    (set-fields {:status "failed"})
                                                    (where {:id run-id})))
                                   (f/message err)))))))
