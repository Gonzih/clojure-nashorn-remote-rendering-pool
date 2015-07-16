(ns rendering-pool.pool
  (:require [clojure.core.async :as async])
  (:import [jdk.nashorn.api.scripting NashornScriptEngine]))

(defmacro time-to
  "Evaluates expr and prints the time it took.  Returns the value of
 expr."
  {:added "1.0"}
  [log-fn expr]
  `(let [start# (. System (nanoTime))
         ret# ~expr]
     (~log-fn (str "Elapsed time: " (/ (double (- (. System (nanoTime)) start#)) 1000000.0) " msecs"))
     ret#))

(defn run-actor [log-fn ^NashornScriptEngine engine input-chan control-chan]
  (async/thread
    (log-fn "Starting new worker")
    (async/go-loop []
      (async/alt!
        control-chan ([command]
                      (case command
                        :exit (log-fn "Exiting!")))
        input-chan ([{:keys [code output-chan error-chan]}]
                    (log-fn "Received code")
                    (try
                      (let [result (time-to log-fn (.eval engine code))]
                        (log-fn "Sending response")
                        (async/>! output-chan result))
                      (catch Exception e
                        (log-fn "Sending error" e)
                        (async/>! error-chan e)))
                    (recur))))))
