(ns nihilite.readline.paren-balance
  "Net bracket balance of a partial Clojure form. Used by the
   raw REPL to detect when a multi-line continuation is complete.

   Counts (), [], {} while correctly skipping the contents of:
     - `;` line comments (to end of line)
     - `#_` form-discarding comments (balance of the next form)
     - string literals  \"...\"  (with \\\\ and \\\" escapes)
     - character literals  \\x \\space \\newline \\u00FF  (single char)
     - nested parens inside any of the above

   A bracket inside a skipped region must never move the balance,
   otherwise unbalanced strings or comments wedge the REPL forever
   in continuation-prompt mode.")

(defn paren-balance
  "Net bracket balance of `s` (positive = more opens)."
  [^String s]
  (loop [i 0 bal 0 len (.length s)]
    (cond
      (>= i len) bal

      (and (>= i 1) (= (.charAt s (dec i)) \#)
           (= (.charAt s i) \_))
      (let [skipped (loop [j (inc i) b 0]
                      (cond
                        (>= j len) j
                        (or (= (.charAt s j) \() (= (.charAt s j) \[)
                            (= (.charAt s j) \{)) (recur (inc j) (inc b))
                        (or (= (.charAt s j) \)) (= (.charAt s j) \])
                            (= (.charAt s j) \})) (recur (inc j) (dec b))
                        (zero? b) j
                        :else (recur (inc j) b)))]
        (recur skipped bal len))

      (= (.charAt s i) \;)
      (let [nl (.indexOf s (int \newline) (inc i))]
        (recur (if (neg? nl) len (inc nl)) bal len))

      (= (.charAt s i) \")
      (let [end (loop [j (inc i)]
                  (cond
                    (>= j len) j
                    (= (.charAt s j) \\)
                    (recur (+ j 2))
                    (= (.charAt s j) \")
                    (inc j)
                    :else (recur (inc j))))]
        (recur end bal len))

      (and (= (.charAt s i) \\) (< (inc i) len))
      ;; char literal: skip backslash + 1 char; multi-char forms have no brackets.
      (let [next (min (+ i 2) len)]
        (recur next bal len))

      (or (= (.charAt s i) \()
          (= (.charAt s i) \[)
          (= (.charAt s i) \{))
      (recur (inc i) (inc bal) len)

      (or (= (.charAt s i) \))
          (= (.charAt s i) \])
          (= (.charAt s i) \}))
      (recur (inc i) (dec bal) len)

      :else
      (recur (inc i) bal len))))
