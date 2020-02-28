(ns carmine-streams.core-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [carmine-streams.core :as cs]
            [taoensso.carmine :as car]
            [clojure.string :as string]))

(def conn-opts {})

(defn- clear-redis! [f]
  (car/wcar conn-opts (car/flushall))
  (f))

(use-fixtures :each clear-redis!)

(deftest kvs->map-test
  (is (= {}
         (cs/kvs->map nil)
         (cs/kvs->map [])))

  (is (= {:a 1 :b 2 :c 3}
         (cs/kvs->map ["a" 1 "c" 3 "b" 2]))))

(deftest create-idempotency-test
  (dotimes [_ 3]
    (is (cs/create-consumer-group! conn-opts "foo" "bar"))))

(deftest create-start-and-shutdown-test
  (let [conn-opts {}
        stream "my-stream"
        group "my-group"
        consumer-name-prefix "my-consumer-"
        consumed-messages (atom #{})]

    (testing "can create stream and consumer group"
      (is (cs/create-consumer-group! conn-opts stream group))

      (is (= {:name "my-group"
              :consumers []
              :pending 0
              :last-delivered-id "0-0"
              :unconsumed 0}
             (cs/group-stats conn-opts stream group))))

    (testing "can create consumers"
      (let [consumers (mapv #(future (cs/start-consumer! conn-opts stream group
                                                         (fn [v]
                                                           (let [data (update v :temperature read-string)]
                                                             (if (neg? (:temperature data))
                                                               (throw (Exception. "Too cold!"))
                                                               (swap! consumed-messages conj data))))
                                                         (str consumer-name-prefix %)))
                            (range 3))]
        (Thread/sleep 100) ;; wait for futures to start

        (testing "stats show the consumers"
          (let [group-stats (cs/group-stats conn-opts stream group)]
            (is (= {:name "my-group"
                    :pending 0
                    :last-delivered-id "0-0"
                    :unconsumed 0}
                   (dissoc group-stats :consumers)))

            (is (= [{:name "my-consumer-0" :pending 0}
                    {:name "my-consumer-1" :pending 0}
                    {:name "my-consumer-2" :pending 0}]
                   (map #(dissoc % :idle) (:consumers group-stats))))))

        (testing "can write to stream and messages are consumed"
          (car/wcar conn-opts (car/xadd stream "0-1" :temperature 19.7))
          (Thread/sleep 100)

          (is (= #{{:temperature 19.7}}
                 @consumed-messages)))

        (testing "exceptions in callback leaves message pending"
          (car/wcar conn-opts (car/xadd stream "0-2" :temperature -14.1))
          (Thread/sleep 100)

          (let [group-stats (cs/group-stats conn-opts stream group)]
            (is (= {:name "my-group"
                    :pending 1
                    :last-delivered-id "0-2"
                    :unconsumed 0}
                   (dissoc group-stats :consumers)))))

        (testing "can stop consumers"
          (cs/stop-consumers! conn-opts stream group consumer-name-prefix)
          (is (every? #(nil? (deref % 100 ::timed-out)) consumers)))))))
