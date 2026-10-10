(ns nihilite.crafter.descriptor
  "JVM method-descriptor grammar.

   Six pure functions, no state and no bytecode: given a descriptor string,
   decide whether it is well formed, how many parameters it declares, and
   what its type tokens are. They lived inside registry until the three
   layers made the shape of that file visible -- 810 lines, of which this
   block was 115 and touched not one registry atom.

   They sit in crafter because that is where dependency-free material belongs:
   registry may reach down here, and nothing here reaches anywhere. Parses
   only, never resolves: resolving a name means loading the type, and a hook
   installed to observe a class-loading path would re-enter the advice."
  (:require [clojure.string :as str]))

(def ^:private primitive-descriptor-chars
  "The JVM's type codes a descriptor may spell directly. V is here
   because a return type may be void; it is not a legal parameter type,
   which valid-descriptor? does not distinguish, since a spec whose
   descriptor claims otherwise cannot match a real method either."
  #{\B \C \D \F \I \J \S \V \Z})

(defn- valid-object-type-name?
  "Whether a `L...;` body is a well-formed internal class name: a
   sequence of `/`-separated identifiers, each starting with a letter or
   `_` or `$`. Deliberately does not resolve the name -- resolving means
   loading the type, and a hook installed to observe a class-loading path
   would re-enter the advice."
  [^String n]
  (and (pos? (count n))
       ;; Every character must be legal in an identifier. Checking each
       ;; one, rather than only the first of each slash-separated part,
       ;; is what rejects the dotted binary form: a descriptor spells
       ;; java.lang.String as Ljava/lang/String;, and a name carrying a
       ;; dot could never match a method.
       (every? #(or (Character/isLetterOrDigit %)
                    (= % \_)
                    (= % \$)
                    (= % \/))
              n)
       (not (Character/isDigit (.charAt n 0)))
       (every? #(pos? (count %))
               (str/split n #"/" -1))))

(defn- valid-type-token?
  "Whether `tok` is one JVM type: `[` prefixes (any number) followed by
   a primitive letter, an object name in `L...;`, or -- for a return type
   only, which this cannot tell -- nothing else."
  [^String tok]
  (and (pos? (count tok))
       (let [i (loop [i 0]
                 (if (and (< i (count tok)) (= \[ (.charAt tok i)))
                   (recur (inc i))
                   i))]
         (if (< i (count tok))
           (let [c (.charAt tok i)]
             (cond
               (contains? primitive-descriptor-chars c)
               (= i (dec (count tok)))

               (= \L c)
               (let [semi (.lastIndexOf tok (int \;))]
                 (and (= semi (dec (count tok)))
                      (> semi (inc i))
                      (valid-object-type-name? (subs tok (inc i) semi))))

               :else false))
           false))))

(defn- split-type-tokens
  "Splits a run of concatenated JVM types into one string per type.

   Scanning by hand rather than by regex: a `L...;` name can contain
   letters that look like type codes, so only the grammar knows where a
   name ends, and a regex would have to encode the same grammar anyway."
  [^String s]
  (let [n (count s)]
    (loop [i 0
           start 0
           out []]
      (cond
        (>= i n)
        (if (= start n) out (conj out (subs s start)))

        (= \[ (.charAt s i))
        (recur (inc i) start out)

        (= \L (.charAt s i))
        (let [semi (.indexOf s (int \;) i)]
          (if (neg? semi)
            (conj out (subs s start))
            (recur (inc semi) (inc semi) (conj out (subs s start (inc semi))))))

        :else
        (recur (inc i) (inc i) (conj out (subs s start (inc i))))))))

(defn valid-descriptor?
  "Whether `s` is a syntactically valid JVM method descriptor: `(`
   followed by zero or more parameter types, `)`, then exactly one return
   type.

   A malformed descriptor is not a harmless slip. It becomes the spec's
   :method-key and its :method-descriptor, and both decide which loaded
   method a hook matches: ByteBuddy matches on the descriptor and
   nihilite.builder.registry.index keys its buckets by it. A descriptor this
   rejects is one ByteBuddy cannot match either, so the hook would
   register successfully and never fire -- the 'registers but never
   fires' mode docs/hook-limits.md documents, reached by a route the
   reader would not expect.

   Parses only, never resolves."
  [^String s]
  (and (>= (count s) 3)
       (= 40 (int (.charAt s 0)))
       (let [close (.indexOf s (int 41))]
         (and (pos? close)
              (let [params (split-type-tokens (subs s 1 close))]
                (and (every? valid-type-token? params)
                     ;; V names void, which is a return type only.
                     (not-any? #(= 86 (int (.charAt ^String % 0))) params)))
              (valid-type-token? (subs s (inc close)))))))

(defn descriptor-param-count
  "How many parameters `descriptor` declares, or nil when it declares none
   that could be counted. A descriptor is the authority on a method's
   shape, so this is the authority on arity too."
  [^String s]
  (let [close (.indexOf s (int 41))]
    (when (pos? close)
      (count (split-type-tokens (subs s 1 close))))))