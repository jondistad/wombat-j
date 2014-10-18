(ns wombat.compiler
  (:require [clojure.core.match :refer [match]]
            wombat.empty
            [wombat.reader :refer [read]])
  (:import [org.objectweb.asm ClassWriter ClassVisitor Opcodes Type Handle]
           [org.objectweb.asm.commons GeneratorAdapter Method]
           [clojure.lang DynamicClassLoader Compiler RT LineNumberingPushbackReader Keyword Symbol]
           [java.lang.invoke MethodType MethodHandle MethodHandles MethodHandles$Lookup
            CallSite VolatileCallSite]
           [java.io FileReader]
           [wombat ILambda AsmUtil Global])
  (:refer-clojure :exclude [compile load-file eval read]))

(def ^:dynamic *class-loader* (DynamicClassLoader. (RT/baseLoader)))

(def clean-resolve (partial ns-resolve 'wombat.empty))

(defn maybe-class
  [cname]
  (when-let [cls (clean-resolve cname)]
    (when (class? cls) cls)))

(def object-array-class (class (object-array 0)))

(def ^:dynamic *print-debug* true)
(defn debug [& vals]
  (when *print-debug*
    (apply println vals)))

(def global-bindings (atom {}))
(def ^:dynamic *current-define* nil)

(def seqable?
  "True if the given value implements clojure.lang.Seqable. Note that
  that does NOT include all types which can be seq'd. Only internal
  Clojure collection types."
  (partial instance? clojure.lang.Seqable))

(defonce -id- (atom -1))
(defn next-id []
  (swap! -id- inc'))

(def rest-token (symbol "#!rest"))
(defn special-token?
  [sym]
  (.startsWith (name sym) "#!"))

(def specials
  #{'lambda
    'let
    'if
    'define
    'define-class*
    'quote
    'begin
    rest-token})

(defn sanitize-name
  [sym]
  (symbol (str sym "__#" (next-id))))

(defn extend-env
  [env syms]
  (reduce #(assoc %1 %2 (sanitize-name %2))
          env (remove special-token? syms)))

(defn substitute
  "Renames a plain symbol to its sanitized name in the given
  environment. IllegalArgumentException is thrown for symbols which
  are not in the given environment. Specials and class names which are
  not in the environment map pass through unmodified.

  E.g. (substitute '{foo foo__#0} 'foo) ;=> foo__#0
       (substitute '{foo foo__#0} 'lambda) ;=> lambda
       (substitute '{foo foo__#0} 'java.lang.Object ;=> java.lang.Object"
  [env sym]
  (if (symbol? sym)
    (or (get env sym)
        (get specials sym)
        (and (contains? @global-bindings sym) sym)
        (and (= *current-define* sym) sym)
        (and (maybe-class sym) sym)
        (throw (IllegalArgumentException. (str "symbol " sym " is not defined"))))
    sym))

(declare sanitize)

(defn sanitize-lambda
  [env [_ params & body :as form]]
  (let [params (if (symbol? params) (list rest-token params) params)
        lambda-env (extend-env env params)]
    (list* 'lambda (map (partial substitute lambda-env) params) (map (partial sanitize lambda-env) body))))

(defn sanitize-let
  [env [_ bindings & body :as form]]
  (let [sani-binds (map (partial sanitize env) (map second bindings))
                                        ; Scheme-style let does not extend env with new
                                        ; names until after bindings are evaluated
        [let-env bind-syms] (loop [e env, sani-bs [], bs (map first bindings)]
                              (if (seq bs)
                                (let [sani (sanitize-name (first bs))]
                                  (recur (assoc env (first bs) sani)
                                         (conj sani-bs sani)
                                         (rest bs)))
                                [e sani-bs]))]
    (list* 'let (map list bind-syms sani-binds) (map (partial sanitize let-env) body))))

(declare sanitize-jvm)

(defn sanitize-seq
  [env form]
  (match (seq form)
    nil form
    
    (['quote val] :seq) (list 'quote val)

    (['lambda params & body] :seq)
    (sanitize-lambda env form)

    (['let bindings & body] :seq)
    (sanitize-let env form)

    (['define name & val] :seq)
    (list* 'define name (map (partial sanitize (assoc env name name)) val))

    (['define-class* & rest] :seq)
    form

    ([:jvm & insns] :seq)
    (list* :jvm (map (partial sanitize-jvm env) insns))

    :else
    (map (partial sanitize env) form)))

(defn sanitize
  "Renames all bound symbols within the given form to guarantee
  uniqueness."
  [env form]
  (cond
   (seqable? form)
   (sanitize-seq env form)

   (symbol? form)
   (substitute env form)

   :else
   form))

(defn free-vars
  "Returns a sorted-set of free symbols in the given form. Sorting is
  arbitrary, but consistent."
  [form]
  (cond
   (seqable? form)
   (match (seq form)
     nil (sorted-set)

     (['quote val] :seq) (sorted-set)

     (['lambda params & body] :seq)
     (apply disj (free-vars body) (remove special-token? params))

     (['let bindings & body] :seq)
     (apply disj
            (reduce into (sorted-set) (map #(free-vars (second %)) bindings))
            (map first bindings))

     (['define name & val] :seq)
     (disj (free-vars val) name)

     ;; define-class* is not a closure, leave free vars uncollected
     ;; so they will throw during compilation
     (['define-class* & rest] :seq)
     (sorted-set)

     ([:jvm & forms] :seq)
     (into (sorted-set)
           (mapcat #(when (keyword? (first %))
                      (free-vars (rest %)))
                   forms))

     :else
     (reduce into (sorted-set) (map free-vars form)))

   (and (symbol? form)
        (not (contains? specials form))
        (not (contains? @global-bindings form))
        (not= *current-define* form)
        (not (maybe-class form)))
   (sorted-set form)

   :else
   (sorted-set)))

(declare emit
         emit-seq
         emit-value
         emit-dup
         emit-symbol
         emit-string
         eval*
         eval)

(defn asmtype ^Type [^Class cls] (Type/getType cls))
(def object-type (asmtype Object))
(def boolean-object-type (asmtype Boolean))
(def ilambda-type (asmtype ILambda))
(def void-ctor (Method/getMethod "void <init>()"))

(defn dotmunge
  [str]
  (.replace (munge str) "." "_DOT_"))

(defn close-name
  [fsym]
  (str "close_" (dotmunge (name fsym))))

(defn local-name
  [lsym]
  (str "local_" (dotmunge (name lsym))))

(defn handle-name
  [arity restarg]
  (str "handle_" arity (when restarg "_REST")))

(defn gen-closure-fields
  [^ClassVisitor cv closed-overs]
  (doseq [c closed-overs]
    (. cv visitField Opcodes/ACC_FINAL (close-name c) (.getDescriptor object-type) nil nil)))

(defn gen-ctor
  [cw fqname fv]
  (let [ctor (Method. "<init>" Type/VOID_TYPE (into-array Type (repeat (count fv) object-type)))
        gen (GeneratorAdapter. Opcodes/ACC_PUBLIC ctor nil nil cw)]
    (. gen visitCode)
    (. gen loadThis)
    (. gen invokeConstructor object-type void-ctor)
    (dotimes [n (count fv)]
      (. gen loadThis)
      (. gen loadArg n)
      (. gen putField (Type/getObjectType fqname) (close-name (nth fv n)) object-type))
    (. gen returnValue)
    (. gen endMethod)))

(defn emit-body
  [env context gen exprs]
  (doseq [stmt (butlast exprs)]
    (emit env :context/statement gen stmt))
  (emit env context gen (last exprs)))

(defn gen-body
  [cw {:keys [params restarg] :as env} body]
  (debug "gen-body" body)
  (debug "params:" params)
  (debug "restarg:" restarg)
  (let [sig (repeat (count params) object-type)
        sig (if restarg
              (conj (vec sig) (asmtype object-array-class))
              sig)
        _ (debug "sig:" (seq sig))
        m (Method. "invoke" object-type (into-array Type sig))
        _ (debug "method:" m)
        gen (GeneratorAdapter. Opcodes/ACC_PUBLIC m nil nil cw)
        rest-id (when restarg (. gen newLocal object-type))]
    (. gen visitCode)
    (when rest-id
      (. gen loadArg (count params))
      (. gen invokeStatic (asmtype java.util.Arrays) (Method/getMethod "java.util.List asList(Object[])"))
      (. gen invokeStatic (asmtype clojure.lang.PersistentList) (Method/getMethod "clojure.lang.IPersistentList create(java.util.List)"))
      (. gen storeLocal rest-id))
    (emit-body (assoc-in env [:locals restarg] rest-id) :context/return gen body)
    (. gen returnValue)
    (. gen endMethod)))

(defn emit-array
  [gen asm-type contents]
  (AsmUtil/pushInt gen (count contents))
  (. gen newArray asm-type)
  (dotimes [n (count contents)]
    (. gen dup)
    (AsmUtil/pushInt gen n)
    (if (fn? (nth contents n))
      ((nth contents n))
      (. gen push (nth contents n)))
    (. gen arrayStore asm-type)))

(defn method-type
  [gen arity restarg]
  (. gen push object-type)
  (let [positional (repeat arity object-type)
        sig (if restarg
              (conj (vec positional) (asmtype object-array-class))
              positional)]
    (emit-array gen (asmtype Class) sig))
  (. gen invokeStatic (asmtype MethodType) (Method/getMethod "java.lang.invoke.MethodType methodType(Class,Class[])")))

(defn gen-handle
  [cw {:keys [params restarg thistype] :as env}]
  (let [^ClassVisitor cv cw
        arity (count params)
        handle (handle-name arity restarg)]
    (. cv visitField (+ Opcodes/ACC_STATIC Opcodes/ACC_FINAL) handle
       (.getDescriptor (asmtype MethodHandle)) nil nil)

    (let [clinitgen (GeneratorAdapter. (+ Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC)
                                       (Method/getMethod "void <clinit>()")
                                       nil nil cw)
          mt-local (. clinitgen newLocal (asmtype MethodType))]
      (. clinitgen visitCode)
      (method-type clinitgen arity restarg)
      (. clinitgen storeLocal mt-local)

      (. clinitgen invokeStatic (asmtype MethodHandles) (Method/getMethod "java.lang.invoke.MethodHandles$Lookup lookup()"))
      (. clinitgen push thistype)
      (. clinitgen push "invoke")
      (. clinitgen loadLocal mt-local)
      (. clinitgen invokeVirtual (asmtype MethodHandles$Lookup)
         (Method/getMethod "java.lang.invoke.MethodHandle findVirtual(Class,String,java.lang.invoke.MethodType)"))

      (. clinitgen loadLocal mt-local)
      (AsmUtil/pushInt clinitgen 0)
      (emit-array clinitgen (asmtype Class) [ilambda-type])
      (. clinitgen invokeVirtual (asmtype MethodType)
         (Method/getMethod "java.lang.invoke.MethodType insertParameterTypes(int,Class[])"))

      (. clinitgen invokeVirtual (asmtype MethodHandle)
         (Method/getMethod "java.lang.invoke.MethodHandle asType(java.lang.invoke.MethodType)"))
      (when restarg
        (. clinitgen push (asmtype object-array-class))
        (. clinitgen invokeVirtual (asmtype MethodHandle)
           (Method/getMethod "java.lang.invoke.MethodHandle asVarargsCollector(Class)")))
      (. clinitgen putStatic thistype handle (asmtype MethodHandle))

      (. clinitgen returnValue)
      (. clinitgen endMethod))

    (let [gen (GeneratorAdapter. Opcodes/ACC_PUBLIC
                                 (Method/getMethod "java.lang.invoke.MethodHandle getHandle(int)")
                                 nil nil cw)
          false-label (. gen newLabel)
          end-label (. gen newLabel)]
      (. gen visitCode)
      (. gen loadArg 0)
      (AsmUtil/pushInt gen arity)
      (if restarg
        (. gen ifCmp Type/INT_TYPE GeneratorAdapter/LT false-label)
        (. gen ifCmp Type/INT_TYPE GeneratorAdapter/NE false-label))

      (. gen getStatic thistype handle (asmtype MethodHandle))
      (. gen goTo end-label)
      
      (. gen mark false-label)
      (. gen newInstance (asmtype IllegalArgumentException))
      (. gen dup)
      (. gen newInstance (asmtype StringBuilder))
      (. gen dup)
      (. gen invokeConstructor (asmtype StringBuilder) void-ctor)
      (. gen push "Wrong number of args (")
      (. gen invokeVirtual (asmtype StringBuilder) (Method/getMethod "StringBuilder append(String)"))
      (. gen loadArg 0)
      (. gen invokeVirtual (asmtype StringBuilder) (Method/getMethod "StringBuilder append(int)"))
      (. gen push (str " for " arity ")"))
      (. gen invokeVirtual (asmtype StringBuilder) (Method/getMethod "StringBuilder append(String)"))
      (. gen invokeVirtual (asmtype StringBuilder) (Method/getMethod "String toString()"))
      (. gen invokeConstructor (asmtype IllegalArgumentException) (Method/getMethod "void <init>(String)"))
      (. gen throwException)

      (. gen mark end-label)
      (. gen returnValue)
      (. gen endMethod))))

(defn validate-params!
  [params]
  (when (and (some #{rest-token} params)
             (not= (count (drop-while (complement #{rest-token}) params)) 2))
    (throw (IllegalArgumentException. "#!rest may only be followed by one symbol!"))))

(def ^:dynamic *compiled-lambdas*)

(defmulti compile
  "Compiles a given lambda or define-class*. Lambda input must be
  sanitized, or the results are undefined!

  Returns a vector of [<class name> <bytecode> <closed-overs>?]

  Note that because define-class* is not a closure, there will be no
  <closed-overs>"

  first)

(defmethod compile 'lambda
  [[_ params & body :as lambda]]
  (when-not (get @*compiled-lambdas* lambda)
    (validate-params! params)
    (debug "Compiling:" lambda)
    (let [fv (vec (free-vars lambda))
          cw (ClassWriter. ClassWriter/COMPUTE_FRAMES)
          lname (str "lambda_" (next-id))
          fqname (str "wombat/" lname)
          _ (debug "fqname:" fqname)
          dotname (.replace fqname "/" ".")
          ifaces (into-array String ["wombat/ILambda"])
          before-rest (complement #{rest-token})
          restarg (second (drop-while before-rest params))
          env {:params (take-while before-rest params)
               :closed-overs fv
               :locals {}
               :thistype (Type/getObjectType fqname)
               :restarg restarg}]
      (. cw visit Opcodes/V1_7 Opcodes/ACC_PUBLIC fqname nil "java/lang/Object" ifaces)
      (gen-closure-fields cw fv)
      (gen-ctor cw fqname fv)
      (gen-body cw env body)
      (gen-handle cw env)
      (. cw visitEnd)
      (swap! *compiled-lambdas* conj lambda)
      [dotname (.toByteArray cw) fv])))

(defn emit
  [env context gen form]
  (cond
   (and (seqable? form) (seq form))
   (emit-seq env context gen form)

   (seqable? form) ; empty
   (emit-value context gen form)

   (symbol? form)
   (emit-symbol env context gen form)

   :else
   (emit-value context gen form)))

(def global-bootstrap
  (Handle. Opcodes/H_INVOKESTATIC
           "wombat/Global"
           "bootstrap"
           (.toMethodDescriptorString
            (MethodType/methodType CallSite (into-array Class [MethodHandles$Lookup String MethodType String])))))

(defn emit-global
  [gen sym]
  (. gen invokeDynamic "getGlobal"
     (.toMethodDescriptorString (MethodType/methodType Object (make-array Class 0)))
     global-bootstrap
     (object-array [(name sym)])))

(defn emit-symbol
  [{:keys [params closed-overs locals thistype] :as env} context gen sym]
  (cond
   ((set params) sym)
   (. gen loadArg (.indexOf params sym))

   ((set closed-overs) sym)
   (do
     (. gen loadThis)
     (. gen getField thistype (close-name sym) object-type))

   (contains? locals sym)
   (. gen loadLocal (get locals sym))

   (or (contains? @global-bindings sym)
       (= *current-define* sym))
   (emit-global gen sym)
   
   (maybe-class sym)
   (. gen push (asmtype (maybe-class sym)))

   :else
   (throw (IllegalStateException. (str "Symbol " sym " is not defined"))))
  (when (= context :context/statement)
    (. gen pop)))

(defn emit-value
  [context gen const]
  (debug "emit-value" context const)
  (cond
   (nil? const)
   (. gen visitInsn Opcodes/ACONST_NULL)

   (= true const)
   (. gen getStatic boolean-object-type "TRUE" boolean-object-type)

   (= false const)
   (. gen getStatic boolean-object-type "FALSE" boolean-object-type)

   :else
   (emit-dup gen const))
  (when (= context :context/statement)
    (. gen pop)))

(defmulti emit-dup
  (fn [_ n] (class n)))

(defmethod emit-dup :default
  [_ obj]
  (throw (IllegalArgumentException. (str "cannot emit-dup of " obj))))

(defmethod emit-dup Class
  [gen ^Class class]
  (debug "emit-dup class" class)
  (. gen push (asmtype class)))

(defmethod emit-dup Integer
  [gen ^Integer int]
  (debug "emit-dup int" int)
  (AsmUtil/pushInt gen int)
  (. gen invokeStatic (asmtype Integer) (Method/getMethod "Integer valueOf(int)")))

(defmethod emit-dup Long
  [gen ^Long long]
  (debug "emit-dup long" long)
  (AsmUtil/pushLong gen long)
  (. gen invokeStatic (asmtype Long) (Method/getMethod "Long valueOf(long)")))

(defmethod emit-dup Float
  [gen ^Float float]
                                        ; Cast up to double
  (debug "emit-dup float" float)
  (. gen push (.doubleValue float))
  (. gen invokeStatic (asmtype Double) (Method/getMethod "Double valueOf(double)")))

(defmethod emit-dup Double
  [gen ^Double double]
  (debug "emit-dup double" double)
  (. gen push (.doubleValue double))
  (. gen invokeStatic (asmtype Double) (Method/getMethod "Double valueOf(double)")))

(defmethod emit-dup Character
  [gen ^Character char]
  (debug "emit-dup char" char)
  (. gen push (.charValue char))
  (. gen invokeStatic (asmtype Character) (Method/getMethod "Character valueOf(char)")))

(defmethod emit-dup String
  [gen ^String string]
  (debug "emit-dup string" string)
  (. gen push string))

(defmethod emit-dup Symbol
  [gen ^Symbol sym]
  (debug "emit-dup symbol" sym)
  (emit-value :context/expression gen (namespace sym))
  (emit-value :context/expression gen (name sym))
  (. gen invokeStatic (asmtype Symbol) (Method/getMethod "clojure.lang.Symbol intern(String,String)")))

(defmethod emit-dup clojure.lang.Keyword
  [gen ^clojure.lang.Keyword kw]
  (debug "emit-dup keyword" kw)
  (emit-value :context/expression gen (namespace kw))
  (emit-value :context/expression gen (name kw))
  (. gen invokeStatic (asmtype clojure.lang.Keyword) (Method/getMethod "clojure.lang.Keyword intern(String,String)")))

(defmethod emit-dup clojure.lang.Seqable
  [gen ^clojure.lang.Seqable lis]
  (debug "emit-dup list" lis)
  (emit-array gen object-type (map #(fn [] (emit-value :context/expression gen %)) lis))
  (. gen invokeStatic (asmtype java.util.Arrays) (Method/getMethod "java.util.List asList(Object[])"))
  (. gen invokeStatic (asmtype clojure.lang.PersistentList) (Method/getMethod "clojure.lang.IPersistentList create(java.util.List)")))


(defonce -invoke- (Object.))
(defmulti emit-seq
  (fn [_ _ _ form] (first form))
  :default -invoke-)

(defmethod emit-seq 'lambda
  [env context gen [_ params & body :as lambda]]
  (when-not (= context :context/statement)
    (let [[dotname bytecode closed-overs] (compile lambda)]
      (. *class-loader* defineClass dotname bytecode lambda)
      (let [slashname (.replace dotname "." "/")]
        (. gen newInstance (Type/getObjectType slashname))
        (. gen dup)
        (dotimes [n (count closed-overs)]
          (emit-symbol env :context/expression gen (nth closed-overs n)))
        (. gen invokeConstructor (Type/getObjectType slashname)
           (Method. "<init>" Type/VOID_TYPE (into-array Type (repeat (count closed-overs) object-type))))))))

;; (defmethod emit-seq 'define-class*
;;   [env context gen [_ fields :as defcls]])

(defmethod emit-seq 'let
  [env context gen [_ bindings & body :as the-let]]
  (let [start-label (. gen newLabel)
        end-label (. gen newLabel)
        names (mapv #(vector (first %) (. gen newLocal object-type)) bindings)
        vals (mapv second bindings)]
    (dotimes [n (count names)]
      (let [[sym local-id] (nth names n)
            val-expr (nth vals n)]
        (emit env :context/expression gen val-expr)
        (. gen storeLocal local-id)))
    (let [let-env (update-in env [:locals] merge (into {} names))]
      (emit-body let-env context gen body))))

(defmethod emit-seq 'quote
  [env context gen [_ quoted :as form]]
  (when (> (count form) 2)
    (throw (IllegalArgumentException. "quote takes exactly one argument")))
  (emit-value context gen quoted))

(defmethod emit-seq 'begin
  [env context gen [_ & exprs :as form]]
  (emit-body env context gen exprs))

(defn set-global!
  [sym value]
  (cast Symbol sym)
  (let [handle (MethodHandles/constant Object value)]
    (if (contains? @global-bindings sym)
      (.setTarget ^CallSite (@global-bindings sym) handle)
      (swap! global-bindings assoc sym (VolatileCallSite. handle))))
  value)

(defmethod emit-seq 'define
  [env context gen [_ name val :as form]]
  (when (> (count form) 3)
    (throw (IllegalArgumentException. "define only takes one value")))
  (set-global! name (eval* val))
  (emit-global gen name))

(defmethod emit-seq 'if
  [env context gen [_ condition then else :as the-if]]
  (debug "emit-seq if" the-if context)
  (when-not (<= 3 (count the-if) 4)
    (throw (IllegalArgumentException. "if takes 2 or 3 forms")))
  (let [null-label (. gen newLabel)
        false-label (. gen newLabel)
        end-label (. gen newLabel)]
    (emit env :context/expression gen condition)
    (. gen dup)
    (. gen ifNull null-label)
    (. gen getStatic boolean-object-type "FALSE" boolean-object-type)
    (. gen ifCmp boolean-object-type GeneratorAdapter/EQ false-label)
    (emit env context gen then)
    (. gen goTo end-label)
    (. gen mark null-label)
    (. gen pop) ; pop dup of condition created for false check
    (. gen mark false-label)
    (emit env context gen else)
    (. gen mark end-label)))

(defn asm-println
  "Generates bytecode to print the toString of whatever is on top of
  the stack. Leaves stack unchaged."
  [gen]
  (. gen dup)
  (. gen invokeVirtual object-type (Method/getMethod "String toString()"))
  (. gen push "DEBUG: ")
  (. gen swap)
  (. gen invokeVirtual (asmtype String) (Method/getMethod "String concat(String)"))
  (. gen push "clojure.core")
  (. gen push "*out*")
  (. gen invokeStatic (asmtype RT) (Method/getMethod "clojure.lang.Var var(String,String)"))
  (. gen invokeVirtual (asmtype clojure.lang.Var) (Method/getMethod "Object deref()"))
  (. gen checkCast (asmtype java.io.PrintWriter))
  (. gen swap)
  (. gen invokeVirtual (asmtype java.io.PrintWriter) (Method/getMethod "void println(String)")))

(defn emit-var
  [gen sym]
  (. gen push (namespace sym))
  (. gen push (name sym))
  (. gen invokeStatic (asmtype RT) (Method/getMethod "clojure.lang.Var var(String,String)")))

(declare emit-jvm)
(defmethod emit-seq :jvm
  [env context gen [_ & insns]]
  (doseq [i insns]
    (emit-jvm env context gen i)))

(defmethod emit-seq -invoke-
  [env context gen [fun & args :as call]]
  (when (nil? fun)
    (throw (IllegalArgumentException. "Can't invoke nil")))
  (debug "emitting invoke:" call)
  (debug "context:" context)

  (if (keyword? fun)
    (let [fn-sym (symbol (or (namespace fun) "clojure.core") (name fun))]
      (debug "keyword invoke: " fun)
      (when-not (clean-resolve fn-sym)
        (throw (IllegalArgumentException. (str "Unable to resolve clojure symbol " fn-sym))))

      (emit-var gen fn-sym)
      (doseq [a args]
        (emit env :context/expression gen a))
      (. gen invokeVirtual (asmtype clojure.lang.Var) (Method. "invoke" object-type (into-array Type (repeat (count args) object-type)))))
    (do
      (emit env :context/expression gen fun)
      (. gen dup)
      (. gen checkCast ilambda-type)
      (AsmUtil/pushInt gen (count args))
      (. gen invokeInterface ilambda-type (Method/getMethod "java.lang.invoke.MethodHandle getHandle(int)"))
      (. gen swap)

      (doseq [a args]
        (emit env :context/expression gen a))
      (. gen invokeVirtual (asmtype MethodHandle)
         (Method. "invoke" object-type (into-array Type (cons ilambda-type (repeat (count args) object-type)))))))

  (when (= context :context/statement)
    (. gen pop)))

(defn compile-and-load
  ^Class [form]
  (debug "COMPILE-AND-LOAD: " form)
  (let [[name bytecode] (compile form)]
    (.defineClass *class-loader* name bytecode form)
    (Class/forName name true *class-loader*)))

(defn eval*
  "Low-level eval call. Requires a pre-sanitized input."
  [form]
  (debug "EVAL*: " form)
  (cond
   (and (seqable? form) (= (first form) 'define))
   (binding [*current-define* (second form)]
     (set-global! (second form) (eval* (first (nnext form)))))

   (and (seqable? form) (= (first form) 'define-class*))
   (set-global! (second form) (compile-and-load form))

   :else
   (.. (compile-and-load (list 'lambda () form)) newInstance invoke)))

(defn eval
  "Top-level eval call. Initializes env and sanitizes input."
  [form]
  (binding [*compiled-lambdas* (atom #{})]
    (eval* (sanitize {} form))))

(defn load-file
  [file]
  (binding [*print-debug* false
            *class-loader* (DynamicClassLoader. *class-loader*)]
    (let [file-reader (FileReader. file)
          pb-reader (LineNumberingPushbackReader. file-reader)
          eof (Object.)]
      (loop []
        (let [f (read pb-reader false eof)]
          (when-not (identical? f eof)
            (eval f)
            (recur)))))))

(load "compiler_defclass")
