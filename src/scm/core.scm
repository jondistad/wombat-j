(define (load-file file)
  (#:wombat.compiler/load-file file))

(define (eval form)
  (#:wombat.compiler/eval* form))

(define (list . elems) elems)

(define (car lis)
  (#:wombat.datatypes/car lis))

(define (cdr lis)
  (#:wombat.datatypes/cdr lis))

(define (cons a d)
  (#:wombat.datatypes/cons a d))

;; This is NOT recursive!
;; (if (null? ...) ...) is a special case in the compiler!
(define (null? o)
  (if (null? o)
    #t
    #f))

(define (not o)
  (if o
    #f
    #t))

(define (foldl f init vals)
  (if (null? vals)
    init
    (foldl f (f (car vals) init) (cdr vals))))

(define (foldr f init vals)
  (if (null? vals)
    init
    (f (car vals) (foldr f init (cdr vals)))))

(define (map f lis)
  (foldr (lambda (x xs)
           (cons (f x) xs)) '() lis))

(define (cat* l1 l2)
  (foldr cons l2 l1))

(define (concat . ls)
  (foldr cat* '() ls))

(define (eqv? a b)
  (if (null? a)
    (null? b)
    (#:jvm (#:emit a)
           (#:emit b)
           (invokeVirtual Object (boolean "equals" Object))
           (box boolean))))

(define (list? x)
  (#:wombat.datatypes/list? x))

(define (pair? x)
  (#:wombat.datatypes/pair? x))

(define (print o)
  (#:print o))

(define (expand f)
  (#:wombat.compiler/expand f))

(define (obj->str o)
  (#:wombat.printer/write-str o))

(define (reverse lis)
  (foldl cons '() lis))

(define (list* . ls)
  (if (null? ls)
    '()
    (let ((rlis (reverse ls)))
      (if (not (list? (car rlis)))
        (#:jvm (throwException IllegalArgumentException "list* expects list as final arg")))
      (foldl cons (car rlis) (cdr rlis)))))

(define (apply f args)
  (#:jvm (#:emit f)
         (checkCast wombat.ILambda)
         (#:invoke #:object-array args)
         (checkCast (Object))
         (invokeStatic wombat.Global (Object "invokeLambda" wombat.ILambda (Object)))))

(define (quasiquote-pair* c l ls)
  (if (null? c)
    (list 'apply 'concat (cons 'list (reverse (cons (cons 'list (reverse l)) ls))))
    (if (pair? (car c))
      (if (eqv? (car (car c)) 'unquote)
        (quasiquote-pair* (cdr c)
                          (cons (car (cdr (car c))) l)
                          ls)
        (if (eqv? (car (car c)) 'unquote-splicing)
          (quasiquote-pair* (cdr c)
                            '()
                            (list* (car (cdr (car c))) (cons 'list (reverse l)) ls))
          (quasiquote-pair* (cdr c)
                            (cons (quasiquote-pair* (car c) '() '()) l)
                            ls)))
      (quasiquote-pair* (cdr c) (cons (list 'quote (car c)) l) ls))))

(define-macro (quasiquote x)
  (let ((res (if (pair? x)
               (if (eqv? (car x) 'unquote)
                 (car (cdr x))
                 (if (eqv? (car x) 'unquote-splicing)
                   (#:jvm (throwException RuntimeException "unquote-splicing outside of list!"))
                   (quasiquote-pair* x '() '())))
               (list 'quote x))))
    res))

(define-macro (fail msg)
  `(#:jvm (throwException RuntimeException ,msg)))

(define-macro (cond . conds)
  (let ((c (lambda (c rest)
             (list 'if (car c)
                   (car (cdr c))
                   rest))))
    (foldr c '() conds)))

;; (define-macro let*
;;   (lambda (binds #!rest body)
;;     (let ((let-binds (foldr (lambda (b rest)
;;                               (list 'let (list b)
;;                                     rest))
;;                             '() binds)))
;;       (list* let-binds body))))

(define (last lis)
  (if (null? lis)
    '()
    (if (null? (cdr lis))
      (car lis)
      (last (cdr lis)))))

(define (instance? cls o)
  (#:jvm (#:emit cls)
         (checkCast Class)
         (#:emit o)
         (invokeVirtual Class (boolean "isInstance" Object))
         (box boolean)))

(define (string? o)
  (instance? String o))

;; (define-macro new
;;   (lambda (c)
;;     `(#:jvm (newInstance ,c)
;;             (dup)
;;             (invokeConstructor ,c (void "<init>")))))

;; obj format: (Type object)
;; meth format: (ReturnType "methodName" Arg1Type Arg2type ...)
;; (define-macro call
;;   (lambda (obj meth #!rest args)
;;     (let ((type (car obj))
;;           (obj (car (cdr obj))))
;;       `(#:jvm (#:emit ,obj)
;;               (checkCast ,type)
;;               (invokeVirtual ,type ,meth)))))

;; (define str
;;   (lambda vals
;;     (let ((sb (new StringBuilder)))
;;       (foldl (lambda (v sb)
;;                (if (string? v)
;;                  (call (StringBuilder sb) (StringBuilder "append" String))
;;                  (call (StringBuilder sb) (StringBuilder "append"))))))))

(define (namespace named)
  (#:jvm (#:emit named)
         (checkCast clojure.lang.Named)
         (invokeInterface clojure.lang.Named (String "getNamespace"))))

(define (name named)
  (#:jvm (#:emit named)
         (checkCast clojure.lang.Named)
         (invokeInterface clojure.lang.Named (String "getName"))))

(define (get-var sym)
  (#:jvm (#:invoke namespace sym)
         (checkCast String)
         (#:invoke name sym)
         (checkCast String)
         (invokeStatic clojure.lang.RT (clojure.lang.Var "var" String String))))

(define (class obj)
  (if (null? obj)
    '()
    (#:jvm (#:emit obj)
           (invokeVirtual Object (Class "getClass")))))

(define (add1 n)
  (#:jvm (#:emit n)
         (checkCast Number)
         (invokeVirtual Number (long "longValue"))
         (push long 1)
         (add long)
         (box long)))

(define (sub1 n)
  (#:jvm (#:emit n)
         (checkCast Number)
         (invokeVirtual Number (long "longValue"))
         (push long 1)
         (sub long)
         (box long)))

(define (+ x y)
  (#:jvm (#:emit x)
         (checkCast Number)
         (invokeVirtual Number (long "longValue"))
         (#:emit y)
         (checkCast Number)
         (invokeVirtual Number (long "longValue"))
         (add long)
         (box long)))

(define (- x y)
  (#:jvm (#:emit x)
         (checkCast Number)
         (invokeVirtual Number (long "longValue"))
         (#:emit y)
         (checkCast Number)
         (invokeVirtual Number (long "longValue"))
         (sub long)
         (box long)))

(define (* x y)
  (#:jvm (#:emit x)
         (checkCast Number)
         (invokeVirtual Number (long "longValue"))
         (#:emit y)
         (checkCast Number)
         (invokeVirtual Number (long "longValue"))
         (mul long)
         (box long)))

(define (< x y)
  (#:jvm (#:emit x)
         (checkCast Number)
         (invokeVirtual Number (long "longValue"))
         (#:emit y)
         (checkCast Number)
         (invokeVirtual Number (long "longValue"))
         (ifCmp long < #:is-less)
         (#:emit #f)
         (goto #:end)
         (label #:is-less)
         (#:emit #t)
         (label #:end)))

(define (> x y)
  (#:jvm (#:emit x)
         (checkCast Number)
         (invokeVirtual Number (long "longValue"))
         (#:emit y)
         (checkCast Number)
         (invokeVirtual Number (long "longValue"))
         (ifCmp long > #:is-more)
         (#:emit #f)
         (goto #:end)
         (label #:is-more)
         (#:emit #t)
         (label #:end)))

(define (= x y)
  (#:jvm (#:emit x)
         (checkCast Number)
         (invokeVirtual Number (long "longValue"))
         (#:emit y)
         (checkCast Number)
         (invokeVirtual Number (long "longValue"))
         (ifCmp long = #:are-equal)
         (#:emit #f)
         (goto #:end)
         (label #:are-equal)
         (#:emit #t)
         (label #:end)))

(define (fac n)
  (if (< n 2)
    1
    (* (fac (sub1 n)) n)))

(define (sum-to n)
  (if (> n 0)
    (+ (sum-to (sub1 n)) n)
    0))

(define (fib n)
  (if (< n 2)
    1
    (+ (fib (sub1 n)) (fib (- n 2)))))

(define (fib* n m x y)
  (if (< n m)
    (fib* (add1 n) m y (+ x y))
    y))

(define (string->symbol str)
  (#:jvm (#:emit str)
         (checkCast String)
         (invokeStatic clojure.lang.Symbol (clojure.lang.Symbol "intern" String))))

(define (str val)
  (#:jvm (#:str val)))

(define (conc s1 s2)
  (#:jvm (#:str s1)
         (#:str s2)
         (invokeVirtual String (String "concat" String))))

;; (define define-record
;;   (lambda (rname fields)
;;     (#:wombat.compiler/set-global!
;;      (string->symbol (conc "make-" rname))
;;      (eval (list 'lambda fields
;;                  (let ((gs (gensym)))
;;                    (list 'lambda (list gs)
;;                          (cons 'list fields) ;; capture in closure
;;                          (foldr (lambda (f coll)
;;                                   (cons 'if (cons (list 'eqv? (list 'quote f) gs)
;;                                                   (list f coll))))
;;                                 '() fields))))))
;;     '()))


(define doit2)
(define (doit n)
  (if (> n 0)
    (doit2 (sub1 n))
    (print "Done!\n")))
(define (doit2 n)
  (doit n))


(define m4)
(define m3)
(define m2)
(define (m1 n)
  (if (> n 0)
    (m2 (sub1 n))
    (print "done!\n")))
(define (m2 n)
  (m3 n))
(define (m3 n)
  (m4 n))
(define (m4 n)
  (m1 n))

(define (time thunk)
  (let ((start (#:jvm (invokeStatic System (long "nanoTime")) (box long)))
        (ret (thunk))
        (end (#:jvm (invokeStatic System (long "nanoTime")) (box long))))
    (#:printf "time: %.3f ms\n" (#:- (#:/ (#:double end) 1000000) (#:/ (#:double start) 1000000)))
    ret))

(define (gensym . s)
  (if (null? s)
    (#:wombat.compiler/sanitize-name "G_")
    (#:wombat.compiler/sanitize-name (str s))))

(define-macro (dotimes n . body)
  (let ((gs (gensym "n")))
    `(let loop ((,gs ,n))
       (if (> ,gs 0)
         (begin
           ,@body
           (loop (sub1 ,gs)))))))
