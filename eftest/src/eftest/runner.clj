(ns eftest.runner
  "Functions to run tests written with clojure.test or compatible libraries."
  (:require [clojure.java.io :as io]
            [clojure.test :as test]
            [clojure.tools.namespace.find :as find]
            [eftest.report :as report]
            [eftest.report.progress :as progress]
            [eftest.output-capture :as capture])
  (:import [java.util.concurrent Executors ExecutorService]))

(defmethod test/report :begin-test-run [_])

(defn- synchronize [f]
  (let [lock (Object.)] (fn [x] (locking lock (f x)))))

(defn- synchronized? [v]
  (or (-> v meta :eftest/synchronized true?)
      (-> v meta :ns meta :eftest/synchronized true?)))

(defn- known-slow? [v]
  (or (-> v meta :eftest/slow true?)
      (-> v meta :ns meta :eftest/slow true?)))

(defn- failed-test? []
  (or (not= :pass (get @report/*context* :state :pass))
      (< 0 (:error @test/*report-counters* 0))
      (< 0 (:fail @test/*report-counters* 0))))

(defn- wrap-test-with-timer [test-fn test-warn-time]
  (fn [v]
    (let [start-time (System/nanoTime)
          result     (test-fn v)
          end-time   (System/nanoTime)
          duration   (/ (- end-time start-time) 1e6)]
      (when (and (not (known-slow? v))
                 (number? test-warn-time)
                 (<= test-warn-time duration))
        (binding [clojure.test/*testing-vars* (conj clojure.test/*testing-vars* v)]
          (test/report {:type     :long-test
                        :duration duration
                        :var      v})))
      result)))

(defn- ^Callable bound-callback [f]
  (cast Callable (bound-fn* f)))

(defn- ^ExecutorService threadpool-executor [threads]
  (Executors/newFixedThreadPool threads))

(defn- pcalls* [threads fs]
  (let [executor (threadpool-executor threads)]
    (try
      (->> fs
           (map #(.submit executor (bound-callback %)))
           (doall)
           (map #(.get %))
           (doall))
      (finally
        (.shutdownNow executor)))))

(defn- pmap* [threads f xs]
  (pcalls* threads (map (fn [x] #(f x)) xs)))

(defn- multithread-vars? [{:keys [multithread?]}]
  (or (true? multithread?) (= multithread? :vars)))

(defn- multithread-namespaces? [{:keys [multithread?]}]
  (or (true? multithread?) (= multithread? :namespaces)))

(defn- test-vars
  [ns vars report {:as opts :keys [fail-fast? capture-output? test-warn-time threads]}]
  (let [once-fixtures (-> ns meta ::test/once-fixtures test/join-fixtures)
        each-fixtures (-> ns meta ::test/each-fixtures test/join-fixtures)
        test-var      (-> (fn [v]
                            (when-not (and fail-fast? (failed-test?))
                              (binding [report/*testing-path* [ns ::test/each-fixtures]]
                                (try
                                  (each-fixtures
                                    (if capture-output?
                                      #(binding [test/report report
                                                 report/*testing-path* [ns v]]
                                          (capture/with-test-buffer
                                            (test/test-var v)))
                                      #(binding [test/report report
                                                 report/*testing-path* [ns v]]
                                          (test/test-var v))))
                                  (catch Throwable t
                                    (test/do-report {:type :error
                                                     :message "Uncaught exception during fixture initialization."
                                                     :actual t}))))))
                          (wrap-test-with-timer test-warn-time))]
    (binding [report/*testing-path* [ns ::test/once-fixtures]]
      (try
        (once-fixtures
          (fn []
            (if (multithread-vars? opts)
              (do (->> vars (filter synchronized?) (map test-var)   (dorun))
                  (->> vars (remove synchronized?) (pmap* threads test-var) (dorun)))
              (doseq [v vars] (test-var v)))))
        (catch Throwable t
          (test/do-report {:type :error
                           :message "Uncaught exception during fixture initialization."
                           :actual t}))))))

(defn- test-ns [ns vars report opts]
  (let [ns (the-ns ns)]
    (binding [test/*report-counters* (ref test/*initial-report-counters*)]
      (test/do-report {:type :begin-test-ns, :ns ns})
      (test-vars ns vars report opts)
      (test/do-report {:type :end-test-ns, :ns ns})
      @test/*report-counters*)))

(defn- test-all [vars {:as opts :keys [capture-output? threads]}]
  (let [report (synchronize test/report)
        mapf   (if (multithread-namespaces? opts) (partial pmap* threads) map)
        f      #(->> (group-by (comp :ns meta) vars)
                     (mapf (fn [[ns vars]] (test-ns ns vars report opts)))
                     (apply merge-with +))]
    (if capture-output?
      (capture/with-capture (f))
      (f))))

(defn- require-namespaces-in-dir [dir]
  (map (fn [ns] (require ns) (find-ns ns)) (find/find-namespaces-in-dir dir)))

(defn- find-tests-in-namespace [ns]
  (->> ns ns-interns vals (filter (comp :test meta))))

(defn- find-tests-in-dir [dir]
  (mapcat find-tests-in-namespace (require-namespaces-in-dir dir)))

(defmulti find-tests
  "Find test vars specified by a source. The source may be a var, symbol
  namespace or directory path, or a collection of any of the previous types."
  {:arglists '([source])}
  type)

(defmethod find-tests clojure.lang.IPersistentCollection [coll]
  (mapcat find-tests coll))

(defmethod find-tests clojure.lang.Namespace [ns]
  (find-tests-in-namespace ns))

(defmethod find-tests clojure.lang.Symbol [sym]
  (if (namespace sym) (find-tests (find-var sym)) (find-tests-in-namespace sym)))

(defmethod find-tests clojure.lang.Var [var]
  (if (-> var meta :test) (list var)))

(defmethod find-tests java.io.File [dir]
  (find-tests-in-dir dir))

(defmethod find-tests java.lang.String [dir]
  (find-tests-in-dir (io/file dir)))

(defn run-tests
  "Run the supplied test vars. Accepts the following options:

    :fail-fast?      - if true, stop after first failure or error
    :capture-output? - if true, catch test output and print it only if
                       the test fails (defaults to true)
    :multithread?    - one of: true, false, :namespaces or :vars (defaults to
                       true). If set to true, namespaces and vars are run in
                       parallel; if false, they are run in serial. If set to
                       :namespaces, namespaces are run in parallel but the vars
                       in those namespaces are run serially. If set to :vars,
                       the namespaces are run serially, but the vars inside run
                       in parallel.
    :threads         - number of threads to run tests on
                       (defaults to availableProcessors + 2)
    :report          - the test reporting function to use
                       (defaults to eftest.report.progress/report)
    :test-warn-time  - print a warning for any test that exceeds this time
                       (measured in milliseconds)"
  ([vars] (run-tests vars {}))
  ([vars opts]
   (let [start-time (System/nanoTime)]
     (if (empty? vars)
       (do (println "No tests found.")
           test/*initial-report-counters*)
       (binding [report/*context* (atom {})
                 test/report      (:report opts progress/report)]
         (test/do-report {:type :begin-test-run, :count (count vars)})
         (let [opts     (merge {:capture-output? true
                                :multithread? true
                                :threads (+ 2 (.availableProcessors (Runtime/getRuntime)))}
                               opts)
               counters (test-all vars opts)
               duration (/ (- (System/nanoTime) start-time) 1e6)
               summary  (assoc counters :type :summary, :duration duration)]
           (test/do-report summary)
           summary))))))
