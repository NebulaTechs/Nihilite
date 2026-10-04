(ns nihilite.test.descriptor-grammar-test
  "install! refuses a :descriptor that is not a syntactically valid JVM
   method descriptor.

   The descriptor is not decoration. It becomes the spec's :method-key
   and its :method-descriptor, and both decide which loaded method a
   hook matches: ByteBuddy matches on the descriptor, and
   nilhotite.registry.index keys its buckets by it. A descriptor the
   grammar rejects is one ByteBuddy cannot match either, so without this
   check the hook would register successfully and never fire -- the
   'registers but never fires' mode docs/hook-limits.md documents,
   reached by a route a reader would not expect.

   The grammar itself is checked here rather than trusted: a validator
   that accepts a malformed descriptor is the same failure it was added
   to prevent."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.test.fixtures :as fx]))

(use-fixtures :each fx/reg-cleanup)

(def ^:private REAL-DESCRIPTORS
  "Descriptors javap reports for real java.util.BitSet methods, so the
   valid half of the matrix is anchored to the JVM and not to my reading
   of the grammar."
  ["()I"                    ; int size()
   "()[I"                   ; int[] clone()
   "()Ljava/lang/String;"    ; String toString()
   "(I)Z"                    ; boolean get(int)
   "([BII)I"                 ; int read([BII)
   "(Ljava/lang/Object;)Ljava/util/List;"  ; List valueOf-like shape
   "()V"                    ; void clear()
   "(J)Ljava/lang/Object;"  ; Object-returning with a long arg
   "()[[Ljava/lang/String;"  ; String[][] return
   "([[B)[I"                 ; int ([B)[ -> int[]
   "(Ljava/lang/String;)I"]) ; confirmed by javap on a real class])

(def ^:private MALFORMED-DESCRIPTORS
  [["()Q"                   "Q is not a type code"]
   ["()I "                  "a trailing space"]
   ["(I"                    "no closing paren"]
   ["I)V"                   "no opening paren"]
   [")V"                    "no opening paren at all"]
   ["()"                    "no return type"]
   ["V"                     "no parens"]
   ["()Ljava.lang.String;"  "dotted name; a descriptor uses slashes"]
   ["()L;"                  "empty class name"]
   ["()Ljava/lang/String"   "unterminated L"]
   ["()Vextra"              "trailing junk after the return type"]
   ["()Ljava/lang/String;extra" "junk after the semicolon"]
   ["()[L"                  "array of nothing"]
   ["()[1"                  "not a type code after the array prefix"]
   ["()Ljava/lang//String;" "empty path segment"]
   ["()L1Bad;"              "a segment may not start with a digit"]
   ["(V)V"                  "V names void, which is a return type only"]])

(defn- spec-with [id descriptor]
  {:id id
   :target-internal "java/util/BitSet"
   :method-name "size"
   :descriptor descriptor
   :position :return
   :action :observe
   :bridge (fn [_] nil)})

(defn- kind-of [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (:nihilite/kind (ex-data e)))))

(deftest valid-descriptors-are-accepted
  (doseq [d REAL-DESCRIPTORS]
    (is (nil? (kind-of #(reg/install! (spec-with (str "ok-" d) d))))
        (str "accepts " (pr-str d)))))

(deftest malformed-descriptors-are-rejected
  (doseq [[d why] MALFORMED-DESCRIPTORS]
    (is (= :nihilite/bad-descriptor
           (kind-of #(reg/install! (spec-with (str "bad-" d) d))))
        (str "rejects " (pr-str d) " -- " why))))

(deftest a-rejected-descriptor-registers-nothing
  (reg/install! (spec-with "good" "()I"))
  (kind-of #(reg/install! (spec-with "bad" "()Q")))
  (is (some? (reg/lookup "good")) "the earlier good spec is untouched")
  (is (nil? (reg/lookup "bad")) "the rejected spec never reached the registry"))

(deftest the-rejection-names-the-spec-and-echoes-the-descriptor
  (let [d (kind-of #(reg/install! (spec-with "echo" "()I ")))
        e (try (reg/install! (spec-with "echo" "()I "))
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :nihilite/bad-descriptor d))
    (is (= "echo" (:nihilite/id (ex-data e))))
    (is (= "()I " (:nihilite/descriptor (ex-data e))))
    (is (= "java/util/BitSet" (:nihilite/target (ex-data e))))
    (is (re-find #"not a valid JVM method descriptor"
                 (str (.getMessage ^Throwable e))))))

(deftest an-arity-that-contradicts-the-descriptor-is-rejected
  ;; The descriptor already states how many parameters the method takes,
  ;; and lookup-spec-for-call matches a call by counting the arguments it
  ;; received. An :arity that disagrees with the descriptor therefore
  ;; describes a call that can never happen: the spec registered, counted
  ;; as woven, and matched nothing -- the same silence a bad descriptor
  ;; produced.
  (doseq [[arity desc] [[0 "(I)Z"] [2 "()I"] [1 "()I"] [1 "([BII)I"]]]
    (is (= :nihilite/arity-descriptor-mismatch
           (kind-of #(reg/install! (assoc (spec-with "mismatch" desc)
                                          :arity arity))))
        (str "rejects :arity " arity " against " (pr-str desc)))))

(deftest an-arity-that-agrees-with-the-descriptor-is-accepted
  (doseq [[arity desc] [[0 "()I"] [1 "(I)Z"] [1 "([J)Ljava/util/BitSet;"]
                           [3 "([BII)I"] [2 "(Ljava/lang/String;J)V"]]]
    (is (nil? (kind-of #(reg/install! (assoc (spec-with "agree" desc)
                                             :arity arity))))
        (str "accepts :arity " arity " with " (pr-str desc)))))

(deftest an-omitted-arity-is-read-from-the-descriptor
  ;; nil :arity used to make lookup-spec-for-call skip its count check
  ;; entirely, so a spec written without one matched whatever it could.
  ;; The descriptor states the count, so there is nothing to leave
  ;; unknown.
  (reg/install! (spec-with "derived" "(I)Z"))
  (is (= 1 (:arity (reg/lookup "derived")))
      "one parameter declared, one recorded"))

(deftest the-derived-fields-are-named-for-what-they-hold
  ;; :internal-class and :method-descriptor are computed by install! from
  ;; :target-internal and :descriptor, not read from the spec. They were
  ;; called :source-class and :source-descriptor, which read as if a hook
  ;; could name a method other than the one it targets. Passing the old
  ;; names in is silently overwritten, so a caller who did that believed
  ;; they were setting something.
  (reg/install! (spec-with "names" "()I"))
  (let [stored (reg/lookup "names")]
    (is (= "java.util.BitSet" (:internal-class stored))
        "the dotted form of :target-internal")
    (is (= "()I" (:method-descriptor stored)) "the same string as :descriptor")
    (is (nil? (:source-class stored)) "the old name is gone")
    (is (nil? (:source-descriptor stored)) "the old name is gone")))
