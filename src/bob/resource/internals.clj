;   This file is part of Bob.
;
;   Bob is free software: you can redistribute it and/or modify
;   it under the terms of the GNU Affero General Public License as published by
;   the Free Software Foundation, either version 3 of the License, or
;   (at your option) any later version.
;
;   Bob is distributed in the hope that it will be useful,
;   but WITHOUT ANY WARRANTY; without even the implied warranty of
;   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
;   GNU Affero General Public License for more details.
;
;   You should have received a copy of the GNU Affero General Public License
;   along with Bob. If not, see <http://www.gnu.org/licenses/>.

(ns bob.resource.internals
  (:require [clojure.string :as s]
            [aleph.http :as http]
            [failjure.core :as f]
            [clj-docker-client.core :as docker]
            [taoensso.timbre :as log]
            [bob.states :as states]
            [bob.resource.db :as db])
  (:import (java.io BufferedOutputStream
                    File
                    FileInputStream
                    FileOutputStream)
           (org.kamranzafar.jtar TarInputStream
                                 TarOutputStream)))

(defn url-of
  "Generates a GET URL for the external resource of a pipeline."
  [resource pipeline]
  (let [url    (-> (db/external-resource-url states/db
                                             {:name (:provider resource)})
                   (:url))
        params (db/resource-params-of states/db
                                      {:name     (:name resource)
                                       :pipeline pipeline})]
    (format "%s/bob_resource?%s"
            url
            (s/join
              "&"
              (map #(format "%s=%s" (:key %) (:value %))
                   params)))))

(defn fetch-resource
  "Downloads a resource(tar file) and returns the stream."
  [resource pipeline]
  (f/try-all [url (url-of resource pipeline)
              _   (log/infof "Fetching resource %s from %s"
                             (:name resource)
                             url)]
    ;; TODO: Potential out of memory issues here
    (:body @(http/get url))
    (f/when-failed [err]
      (log/errorf "Failed to fetch resource: %s" (f/message err))
      err)))

(defn prefix-dir-on-tar!
  "Adds a prefix to the tar entry paths to make a directory.

  Returns the path to the final archive."
  [in-stream prefix]
  (let [archive    (File/createTempFile "resource", ".tar")
        out-stream (-> archive
                       FileOutputStream.
                       BufferedOutputStream.
                       TarOutputStream.)]
    (loop [entry (.getNextEntry in-stream)]
      (when entry
        (.setName entry (format "%s/%s" prefix (.getName entry)))
        (.putNextEntry out-stream entry)
        (when-not (.isDirectory entry)
          (.transferTo in-stream out-stream)
          (.flush out-stream))
        (recur (.getNextEntry in-stream))))
    (.close in-stream)
    (.close out-stream)
    (.getAbsolutePath archive)))

(defn initial-image-of
  "Takes a InputStream of the resource, name and image and builds the initial image.
  This image is used by Bob as the starting image which holds the initial
  state for the rest of the steps.

  Works like this:
  - Copy the contents to a container.
  - Commit the container.
  - Return the id of the committed image.
  - Deletes the temp folder."
  [resource-stream image cmd resource-name]
  (f/try-all [_                 (log/debug "Patching tar stream for container mounting")
              archive           (-> resource-stream
                                    TarInputStream.
                                    (prefix-dir-on-tar! resource-name))
              _                 (log/debug "Creating temp container for resource mount")
              id                (docker/create states/docker-conn image "" {} {})
              _                 (log/debug "Copying resources to container")
              _                 (-> states/docker-conn
                                    (.copyArchiveToContainerCmd id)
                                    (.withTarInputStream (-> archive FileInputStream.))
                                    (.withRemotePath "/root")
                                    .exec)
              _                 (-> archive File. .delete)
              _                 (log/debug "Committing resourceful container")
              provisioned-image (docker/commit-container
                                 states/docker-conn
                                 id
                                 (format "%s/%d" id (System/currentTimeMillis))
                                 "latest"
                                 cmd)
              _                 (log/debug "Removing temp container")
              _                 (docker/rm states/docker-conn id)]
    provisioned-image
    (f/when-failed [err]
      (log/errorf "Failed to create initial image: %s" (f/message err))
      err)))

(defn add-params
  "Saves the map of GET params to be sent to the resource."
  [resource-name params pipeline]
  (when (seq params)
    (log/debugf "Adding params %s for resource %s" params resource-name)
    (db/insert-resource-params states/db
                               {:params (map #(vector (clojure.core/name (first %))
                                                      (last %)
                                                      resource-name
                                                      pipeline)
                                             params)})))

(defn valid-external-resource?
  "Checks if the resource has a valid URL."
  [resource]
  (some? (db/invalid-external-resources states/db
                                        {:name (:provider resource)})))

(defn get-resource-params
  "Fetches list of parameters associated with the resource"
  [pipeline name]
  (reduce
    (fn [r {:keys [key value]}]
      (assoc r (keyword key) value))
    {}
    (db/resource-params-of states/db
                           {:name     name
                            :pipeline pipeline})))

(comment
  (def resource {:name     "my-source"
                 :type     "external"
                 :provider "git"
                 :params   {}})

  (valid-external-resource? resource)

  (url-of resource "dev:test")

  (->> (db/resource-params-of states/db
                              {:name     "my-source"
                               :pipeline "dev:test"})))
