(ns nihilite.transport.ws.dispatch
  "RFC 6455 §5.5 / §6 — per-frame dispatcher. Consumes a parsed
   frame and returns one of `:continue` / `:closed` / `:eof`.
   Owns the per-connection continuation buffer for fragmented
   messages, the safe-eval-line invocation, and the per-frame
   read loop.

   State per connection: user-ns, repl-state, accum-buf,
   accum-opcode. None of this is shared; each call to
   `nihilite.transport.ws.handle/handle-ws` allocates fresh
   state."
  (:require [clojure.tools.logging :as log]
            [nihilite.transport.io :as io]
            [nihilite.transport.ws.frames :as frames])
  (:import [java.io ByteArrayOutputStream OutputStream]))

(defn- ws-frame-handle-ping
  "RFC 6455 §5.5.3: respond with PONG carrying the same payload, but
   only if FIN=1 and payload ≤125 bytes. Otherwise close 1002."
  [^OutputStream out ^bytes payload fin?]
  (if (and fin? (<= (alength ^bytes payload) 125))
    (do (frames/write-ws-frame out 0xA payload) :continue)
    (do (frames/write-ws-close out 1002 "ping-rule-violation") :closed)))

(defn- ws-frame-handle-pong
  "PONG is ignored — never sent by server, but if client sends one
   anyway we just continue."
  [_out _payload _fin?]
  :continue)

(defn- ws-frame-handle-close
  "RFC 6455 §5.5.1: peer-initiated close; reply with 1000 + empty
   reason, then terminate."
  [^OutputStream out _payload _fin?]
  (frames/write-ws-close out 1000 "")
  :closed)

(defn- ws-frame-handle-continuation-fin
  "Flush accumulated message: eval the reassembled text, send the
   response as a single WebSocket TEXT frame, reset accumulators."
  [^OutputStream out user-ns repl-state accum-buf accum-opcode
   opcode-before-continuation]
  (let [msg-bytes (.toByteArray accum-buf)
        _ (.reset accum-buf)
        saved-op opcode-before-continuation
        _ (reset! accum-opcode nil)
        text (String. ^bytes msg-bytes "UTF-8")
        response (io/safe-eval-line text user-ns repl-state)
        out-bytes (.getBytes ^String response "UTF-8")]
    (log/debug "ws FRAG-FIN opcode=" saved-op
          "size=" (alength msg-bytes))
    (frames/write-ws-frame out (or saved-op 0x1) out-bytes)
    :continue))

(defn- ws-frame-handle-data
  "TEXT 0x1 / BINARY 0x2: accumulate payload.
   CONTINUATION 0x0 without FIN: append to accum.
   CONTINUATION 0x0 with FIN: flush eval.
   Protocol violations: close 1002 or 1009."
  [^OutputStream out user-ns repl-state accum-buf accum-opcode
   op ^bytes payload fin?]
  (let [first? (nil? @accum-opcode)]
    (cond
      ;; CONTINUATION without prior data frame
      (and (= op 0x0) first?)
      (do (frames/write-ws-close out 1002 "continuation-without-start") :closed)

      ;; DATA frame after we've already started accumulating
      (and (or (= op 0x1) (= op 0x2)) (not first?))
      (do (frames/write-ws-close out 1002 "data-without-fin") :closed)

      ;; Data frame: flush on FIN, otherwise hold for continuation.
      (or (= op 0x1) (= op 0x2))
      (do
        (.write accum-buf payload)
        (cond
          (> (.size accum-buf) frames/ws-max-accum-bytes)
          (do (frames/write-ws-close out 1009 "too-big") :closed)
          fin?
          (ws-frame-handle-continuation-fin
            out user-ns repl-state accum-buf accum-opcode op)
          :else
          (do (reset! accum-opcode op) :continue)))

      ;; CONTINUATION with FIN — flush message
      (and (= op 0x0) fin?)
      (ws-frame-handle-continuation-fin
        out user-ns repl-state accum-buf accum-opcode @accum-opcode)

      ;; CONTINUATION without FIN — append payload
      (= op 0x0)
      (do
        (.write accum-buf payload)
        (if (> (.size accum-buf) frames/ws-max-accum-bytes)
          (do (frames/write-ws-close out 1009 "too-big") :closed)
          :continue))

      :else
      (do (log/warn "ws unhandled opcode" op)
          (frames/write-ws-close out 1002 "unhandled-opcode")
          :closed))))

(defn ws-frame-dispatch
  "Per-frame dispatcher. Returns :continue, :closed, or :eof."
  [^OutputStream out user-ns repl-state accum-buf accum-opcode frame]
  (cond
    (nil? frame)
    (do (log/info "ws EOF, closing") :eof)

    (:ws-error frame)
    (let [{:keys [ws-error reason]} frame]
      (log/error "ws protocol-error" ws-error reason)
      (frames/write-ws-close out (long ws-error) (or reason ""))
      :closed)

    :else
    (let [op (:opcode frame)
          payload (:payload frame)
          fin? (:fin? frame)]
      (case (long op)
        0x8 (ws-frame-handle-close out payload fin?)
        0x9 (ws-frame-handle-ping   out payload fin?)
        0xA (ws-frame-handle-pong   out payload fin?)
        ;; 0x0/0x1/0x2 all flow through the data handler
        (ws-frame-handle-data out user-ns repl-state accum-buf accum-opcode
                              op payload fin?)))))

(defn ws-loop
  "Inner read-and-dispatch loop. Returns when :closed or :eof. The
   recur is at the very tail of the function body — no try, no
   cond, no nested let/if around it."
  [^java.io.BufferedInputStream buf-in ^OutputStream out user-ns repl-state
   accum-buf accum-opcode]
  (loop []
    (let [frame (frames/read-ws-frame buf-in)
          action (ws-frame-dispatch out user-ns repl-state accum-buf accum-opcode frame)]
      (case action
        (:continue) (recur)
        nil))))
