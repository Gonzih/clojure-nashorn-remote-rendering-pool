(ns rendering-pool.handler
  (:require [org.httpkit.server :refer [with-channel on-close websocket? on-receive run-server send!]]
            [clojure.java.io :as io]
            [clojure.core.async :as async]
            [rendering-pool.pool :as pool])
  (:import [javax.script ScriptEngineManager]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]
           [jdk.nashorn.api.scripting NashornScriptEngine])
  (:gen-class))

(def logger (agent nil))

(defn prn-stdout [_ args]
  (apply println args))

(defn log [& args]
  (send logger prn-stdout args))

(def setup-s "
        var global = global || this;
        var self = self || this;
        var window = window || this;
        window.location = window.location || false;
        var document = document || this;
        var console = global.console || {};
        var History = History || {};
        var history = history || {};
        ['error', 'log', 'info', 'warn'].forEach(function (fn) {
          if (!(fn in console)) {
            console[fn] = function () {};
          }
        });
              ")

(defn read-code [] (slurp "/path-to/javascripts/server-side-vd.js"))

(def library-code (atom ""))

(defn ^NashornScriptEngine bootstrap-nashorn []
  (log "Setting up Nashorn")
  (log "Loading" (count @library-code) "chars of code")
  (let [^NashornScriptEngine nashorn (.getEngineByName (ScriptEngineManager.) "nashorn")]
    (.eval nashorn setup-s)
    (.eval nashorn @library-code)
    (log "Done loading Nashorn")
    nashorn))

(def pool-size 25)
(def delivery-timeout 60000) ; msec
(def response-timeout 1000) ; msec

(def input-chan (atom (async/chan)))
(def control-chan (atom (async/chan)))

(defn generate-pool [size input-chan control-chan]
  (doall
    (pmap (fn [_]
            (pool/run-actor log
                            (bootstrap-nashorn)
                            input-chan
                            control-chan))
          (range size))))

(defn gen-response [c s]
  {:status c
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str s)})

(defn notify-about-timeout [web-channel message]
  (log "Notifying about timeout" message)
  (send! web-channel (gen-response 408 message)))

(defn proxy-the-result [output-chan error-chan web-channel]
  (async/go
    (log "Waiting for the reply")
    (async/alt!
      output-chan ([result]
                   (log "Sending 200 to the web chan")
                   (send! web-channel
                          (gen-response 200
                                        result)))
      error-chan  ([error]
                   (log "Sending 500 to the web chan")
                   (send! web-channel
                          (gen-response 500
                                        (str error
                                             " "
                                             (.getMessage error)))))
      (async/timeout delivery-timeout)
      (notify-about-timeout web-channel "Response timeout"))))

(defn eval-js [code web-channel]
  (async/go
    (let [output-chan (async/chan)
          error-chan (async/chan)
          timeout-chan (async/timeout delivery-timeout)]
      ; (log "Putting data in to the channel")
      (async/alt!
        ; send value to input chan
        [[@input-chan {:code code :output-chan output-chan :error-chan error-chan}]]
        (proxy-the-result output-chan error-chan web-channel)

        ; or timeout
        timeout-chan
        (notify-about-timeout web-channel "Delivery timeout")))))

(defn upgrade-pool []
  (let [old-control-chan @control-chan
        new-input-chan (async/chan)
        new-control-chan (async/chan)]
    (log "Upgrading pool")
    (reset! library-code (read-code))
    (generate-pool pool-size new-input-chan new-control-chan)
    (reset! input-chan new-input-chan)
    (reset! control-chan new-control-chan)
    (dotimes [_ pool-size]
      (async/>!! old-control-chan :exit))))

(defn async-handler [request]
  (with-channel request channel
    (case (:uri request)
      "/upgrade" (async/thread
                   (upgrade-pool)
                   (send! channel (gen-response 200 "done!")))
      "/eval" (try
                (eval-js (slurp (:body request))
                         channel)
                (catch Exception e
                  (log "Error" e (.getMessage e))
                  (send! channel
                        (gen-response 500 "Error while sending data to the worker")))))))

; look at long polling example
(defn -main [& args]
  (log "Running!")
  (reset! library-code (read-code))
  (generate-pool pool-size @input-chan @control-chan)
  (log "Starting web server")
  (run-server async-handler {:port 9090}))
