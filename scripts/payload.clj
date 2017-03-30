(ns unrepl.print
  (:require [clojure.string :as str]))

(def atomic? (some-fn nil? true? false? char? string? symbol? keyword? #(and (number? %) (not (ratio? %)))))

(defn- as-str
  "Like pr-str but escapes all ASCII conytrol chars."
  [x]
  ;hacky
  (cond
    (string? x) (str/replace (pr-str x) #"\p{Cntrl}"
                  #(format "\\u%04x" (int (.charAt ^String % 0))))
    (char? x) (str/replace (pr-str x) #"\p{Cntrl}"
                #(format "u%04x" (int (.charAt ^String % 0))))
    :else (pr-str x)))

(defn- insert-class [classes ^Class class]
  (let [ancestor-or-self? #(.isAssignableFrom ^Class % class)]
    (-> []
     (into (remove ancestor-or-self?) classes)
     (conj class)
     (into (filter ancestor-or-self?) classes))))

(def ^:dynamic *attach* nil)

(defmacro ^:private latent-fn [& fn-body]
  `(let [d# (delay (binding [*ns* (find-ns '~(ns-name *ns*))] (eval '(fn ~@fn-body))))]
     (fn
       ([] (@d#))
       ([x#] (@d# x#))
       ([x# & xs#] (apply @d# x# xs#)))))

(def ^:dynamic *object-representations*
  "map of classes to functions returning their representation component (3rd item in #unrepl/object [class id rep])"
  {clojure.lang.IDeref
   (fn [x]
     (let [pending? (and (instance? clojure.lang.IPending x) ; borrowed from https://github.com/brandonbloom/fipp/blob/8df75707e355c1a8eae5511b7d73c1b782f57293/src/fipp/ednize.clj#L37-L51
                      (not (.isRealized ^clojure.lang.IPending x)))
           [ex val] (when-not pending?
                      (try [false @x]
                        (catch Throwable e
                          [true e])))
           failed? (or ex (and (instance? clojure.lang.Agent x)
                            (agent-error x)))
           status (cond
                    failed? :failed
                    pending? :pending
                    :else :ready)]
       {:unrepl.ref/status status :unrepl.ref/val val}))

   java.io.File (fn [^java.io.File f]
                  (into {:path (.getPath f)}
                    (when (and *attach* (.isFile f))
                      {:attachment (tagged-literal 'unrepl/mime
                                     (into {:content-type "application/octet-stream"
                                           :content-length (.length f)}
                                       (*attach* #(java.io.FileInputStream. f))))})))

   java.awt.Image (latent-fn [^java.awt.Image img]
                    (let [w (.getWidth img nil)
                          h (.getHeight img nil)]
                      (into {:width w, :height h}
                       (when *attach*
                         {:attachment
                          (tagged-literal 'unrepl/mime
                            (into {:content-type "image/png"}
                              (*attach* #(let [bos (java.io.ByteArrayOutputStream.)]
                                               (when (javax.imageio.ImageIO/write
                                                       (doto (java.awt.image.BufferedImage. w h java.awt.image.BufferedImage/TYPE_INT_ARGB)
                                                         (-> .getGraphics (.drawImage img 0 0 nil)))
                                                       "png" bos)
                                                 (java.io.ByteArrayInputStream. (.toByteArray bos)))))))}))))

   Object (fn [x]
            (if (-> x class .isArray)
              (seq x)
              (str x)))})

(defn- object-representation [x]
  (reduce-kv (fn [_ class f]
               (when (instance? class x) (reduced (f x)))) nil *object-representations*)) ; todo : cache

(def ^:dynamic *ednize-fns*
  "map of classes to function converting to a shallow edn-safe representation"
  {clojure.lang.TaggedLiteral identity

   clojure.lang.Ratio (fn [^clojure.lang.Ratio x] (tagged-literal 'unrepl/ratio [(.numerator x) (.denominator x)]))

   Throwable #(tagged-literal 'error (Throwable->map %))

   Class
   (letfn [(class-form [^Class x]
             (if (.isArray x) [(-> x .getComponentType class-form)] (symbol (.getName x))))]
     (fn [^Class x] (tagged-literal 'unrepl.java/class (class-form x))))

   clojure.lang.Namespace #(tagged-literal 'unrepl/ns (ns-name %))

   Object
   (fn [x]
     (tagged-literal 'unrepl/object
                     [(class x) (format "0x%x" (System/identityHashCode x)) (object-representation x)]))})

(def ^:dynamic *elide* (constantly nil))

(defn- elide-vs [vs print-length]
  (if-some [more-vs (when print-length (seq (drop print-length vs)))]
    (concat (take print-length vs) [(tagged-literal 'unrepl/... (*elide* more-vs))])
    vs))

(defn- elide-kvs [kvs print-length]
  (if-some [more-kvs (when print-length (seq (drop print-length kvs)))]
    (concat (take print-length kvs) [[(tagged-literal 'unrepl/... (*elide* more-kvs)) (tagged-literal 'unrepl/... nil)]])
    kvs))

(defn ednize "Shallow conversion to edn safe subset."
  ([x] (ednize x *print-length* *print-meta*))
  ([x print-length] (ednize x print-length *print-meta*))
  ([x print-length print-meta]
   (cond
     (atomic? x) x
     (and print-meta (meta x)) (tagged-literal 'unrepl/meta [(meta x) (ednize x print-length false)])
     (map? x) (into (empty x) (elide-kvs x print-length))
     (instance? clojure.lang.MapEntry x) x
     (vector? x) (into (empty x) (elide-vs x print-length))
     (seq? x) (elide-vs x print-length)
     (set? x) (into (empty x) (elide-vs x print-length))
     :else (let [x' (reduce-kv (fn [_ class f]
                                 (when (instance? class x) (reduced (f x)))) nil *ednize-fns*)]
             (if (= x x')
               x
               (recur x'  print-length print-meta)))))) ; todo : cache

(declare print-on)

(defn- print-vs
  ([write vs rem-depth]
   (print-vs write vs rem-depth print-on " "))
  ([write vs rem-depth print-v sep]
   (when-some [[v & vs] (seq vs)]
     (print-v write v rem-depth)
     (doseq [v vs]
       (write sep)
       (print-v write v rem-depth)))))

(defn- print-kv [write [k v] rem-depth]
  (print-on write k rem-depth)
  (write " ")
  (print-on write v rem-depth))

(defn- print-kvs [write kvs rem-depth]
    (print-vs write kvs rem-depth print-kv ", "))

(defn- print-on [write x rem-depth]
  (let [rem-depth (dec rem-depth)
        x (ednize x (if (neg? rem-depth) 0 *print-length*))]
    (cond
      (tagged-literal? x)
      (do (write (str "#" (:tag x) " "))
          (case (:tag x)
            unrepl/... (binding ; don't elide the elision
                           [*print-length* Long/MAX_VALUE]
                         (print-on write (:form x) Long/MAX_VALUE))
            (recur write (:form x) rem-depth)))
      (map? x) (do (write "{") (print-kvs write x rem-depth) (write "}"))
      (vector? x) (do (write "[") (print-vs write x rem-depth) (write "]"))
      (seq? x) (do (write "(") (print-vs write x rem-depth) (write ")"))
      (set? x) (do (write "#{") (print-vs write x rem-depth) (write "}"))
      (atomic? x) (write (as-str x))
      :else (throw (ex-info "Can't print value." {:value x})))))

(defn edn-str [x]
  (let [out (java.io.StringWriter.)
        write (fn [^String s] (.write out s))]
    (binding [*print-readably* true
              *print-length* (or *print-length* 10)]
      (print-on write x (or *print-level* 6))
      (str out))))

(defn full-edn-str [x]
  (binding [*print-length* Long/MAX_VALUE
            *print-level* Long/MAX_VALUE]
    (edn-str x)))
(ns unrepl.repl
  (:require [clojure.main :as m]
    [unrepl.print :as p]))

(defn tagging-writer
  ([write]
   (proxy [java.io.Writer] []
     (close []) ; do not cascade
     (flush []) ; atomic always flush
     (write
       ([x]
        (write (cond
                 (string? x) x
                 (integer? x) (str (char x))
                 :else (String. ^chars x))))
       ([string-or-chars off len]
        (when (pos? len)
          (write (subs (if (string? string-or-chars) string-or-chars (String. ^chars string-or-chars))
                       off (+ off len))))))))
  ([tag write]
   (tagging-writer (fn [s] (write [tag s]))))
  ([tag group-id write]
   (tagging-writer (fn [s] (write [tag s group-id])))))

(defn atomic-write [^java.io.Writer w]
  (fn [x]
    (let [s (p/edn-str x)] ; was pr-str, must occur outside of the locking form to avoid deadlocks
      (locking w
        (.write w s)
        (.write w "\n")
        (.flush w)))))

(defn pre-reader [^java.io.Reader r before-read]
  (proxy [java.io.FilterReader] [r]
    (read
      ([] (before-read) (.read r))
      ([cbuf] (before-read) (.read r cbuf))
      ([cbuf off len] (before-read) (.read r cbuf off len)))))

(def commands {'set-file-line-col (let [field (when-some [^java.lang.reflect.Field field
                                                          (->> clojure.lang.LineNumberingPushbackReader
                                                               .getDeclaredFields
                                                               (some #(when (= "_columnNumber" (.getName ^java.lang.reflect.Field %)) %)))]
                                                (.setAccessible field true))] ; sigh
                                    (fn [file line col]
                                      (set! *file* file)
                                      (set! *source-path* file)
                                      (.setLineNumber *in* line)
                                      (some-> field (.set *in* col))))})

(defn weak-store [sym not-found]
  (let [ids-to-weakrefs (atom {})
        weakrefs-to-ids (atom {})
        refq (java.lang.ref.ReferenceQueue.)]
    (.start (Thread. (fn []
                       (let [wref (.remove refq)]
                         (let [id (@weakrefs-to-ids wref)]
                           (swap! weakrefs-to-ids dissoc wref)
                           (swap! ids-to-weakrefs dissoc id)))
                           (recur))))
    {:put (fn [xs]
            (let [x (if (nil? xs) () xs)
                  id (gensym)
                  wref (java.lang.ref.WeakReference. xs refq)]
              (swap! weakrefs-to-ids assoc wref id)
              (swap! ids-to-weakrefs assoc id wref)
              {:get (tagged-literal 'unrepl/raw (str "\u0010" (p/full-edn-str (list sym id))))}))
     :get (fn [id]
            (or (some-> @ids-to-weakrefs ^java.lang.ref.WeakReference (get id) .get)
              not-found))}))

(defn- base64-str [^java.io.InputStream in]
  (let [table "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        sb (StringBuilder.)]
    (loop [shift 4 buf 0]
      (let [got (.read in)]
        (if (neg? got)
          (do
            (when-not (= shift 4)
              (let [n (bit-and (bit-shift-right buf 6) 63)]
                (.append sb (.charAt table n))))
            (cond
              (= shift 2) (.append sb "==")
              (= shift 0) (.append sb \=))
            (str sb))
          (let [buf (bit-or buf (bit-shift-left got shift))
                n (bit-and (bit-shift-right buf 6) 63)]
            (.append sb (.charAt table n))
            (let [shift (- shift 2)]
              (if (neg? shift)
                (do
                  (.append sb (.charAt table (bit-and buf 63)))
                  (recur 4 0))
                (recur shift (bit-shift-left buf 6))))))))))

(defn start []
                                        ; TODO: tighten by removing the dep on m/repl
  (with-local-vars [in-eval false
                    command-mode false
                    unrepl false
                    eval-id 0
                    prompt-vars #{#'*ns* #'*warn-on-reflection*}]
    (let [current-eval-thread+promise (atom nil)
          elision-store (weak-store '... (tagged-literal 'unrepl/... nil))
          attachment-store (weak-store 'file (constantly nil))
          commands (assoc commands
                          '... (:get elision-store)
                          'file (comp (fn [inf]
                                        (if-some [in (inf)]
                                          (with-open [^java.io.InputStream in in]
                                            (base64-str in))
                                          (tagged-literal 'unrepl/... nil)))
                                      (:get attachment-store)))
          CTRL-C \u0003
          CTRL-D \u0004
          CTRL-P \u0010
          raw-out *out*
          write (atomic-write raw-out)
          edn-out (tagging-writer :out write)
          ensure-unrepl (fn []
                          (var-set command-mode false)
                          (when-not @unrepl
                            (var-set unrepl true)
                            (flush)
                            (set! *out* edn-out)
                            (binding [*print-length* Long/MAX_VALUE
                                      *print-level* Long/MAX_VALUE]
                              (write [:unrepl/hello {:commands {:interrupt (tagged-literal 'unrepl/raw CTRL-C)
                                                                :exit (tagged-literal 'unrepl/raw CTRL-D)
                                                                :set-source
                                                                (tagged-literal 'unrepl/raw
                                                                                [CTRL-P
                                                                                 (tagged-literal 'unrepl/edn
                                                                                                 (list 'set-file-line-col
                                                                                                       (tagged-literal 'unrepl/param :unrepl/sourcename)
                                                                                                       (tagged-literal 'unrepl/param :unrepl/line)
                                                                                                       (tagged-literal 'unrepl/param :unrepl/col)))])}}]))))
          ensure-raw-repl (fn []
                            (when (and @in-eval @unrepl) ; reading from eval!
                              (var-set unrepl false)
                              (write [:bye nil])
                              (flush)
                              (set! *out* raw-out)))
          eval-executor (java.util.concurrent.Executors/newSingleThreadExecutor)
          interruption (ex-info "Interrupted" {})
          interrupt! (fn []
                       (when-some [[^Thread t p] @current-eval-thread+promise]
                         (reset! current-eval-thread+promise nil)
                         (deliver p {:interrupted true})
                         (when (:interrupted @p)
                           (.stop t)
                           #_(.join t)))) ; seems to block forever, to investigate
          interruptible-eval
          (fn [form]
            (let [bindings (get-thread-bindings)
                  p (promise)]
              (.execute eval-executor
                        (fn []
                          (reset! current-eval-thread+promise [(Thread/currentThread) p])
                          (with-bindings bindings
                            (deliver p
                                     (try
                                       (let [v (with-bindings {in-eval true} (eval form))]
                                         {:value v :bindings (get-thread-bindings)})
                                       (catch Throwable e
                                         {:caught e :bindings (get-thread-bindings)}))))))
              (loop []
                (or (deref p 40 nil)
                    (let [c (.read *in*)]
                      (cond
                        (or (Character/isWhitespace c) (= \, c)) (recur)
                        (= (int CTRL-C) c) (interrupt!)
                        :else (.unread *in* c)))))
              (let [{:keys [bindings caught value interrupted]} @p]
                (reset! current-eval-thread+promise nil)
                (doseq [[v val] bindings]
                  (var-set v val))
                (cond
                  interrupted (throw interruption)
                  caught (throw caught)
                  :else value))))]
      (binding [*out* raw-out
                *err* (tagging-writer :err write)
                *in* (-> *in* (pre-reader ensure-raw-repl) clojure.lang.LineNumberingPushbackReader.)
                *file* "unrepl-session"
                *source-path* "unrepl-session"
                p/*elide* (:put elision-store)
                p/*attach* (:put attachment-store)]
        (m/repl
         :prompt (fn []
                   (ensure-unrepl)
                   (write [:prompt (into {:cmd @command-mode}
                                         (map (fn [v]
                                                (let [m (meta v)]
                                                  [(symbol (name (ns-name (:ns m))) (name (:name m))) @v])))
                                         @prompt-vars)]))
         :read (fn [request-prompt request-exit]
                 (ensure-unrepl)
                 (loop []
                   (let [n (.read *in*)]
                     (if (neg? n)
                       request-exit
                       (let [c (char n)]
                         (cond
                           (or (Character/isWhitespace c) (= \, c)) (recur)
                           (= CTRL-D c) request-exit
                           (= CTRL-P c) (do (var-set command-mode true) (recur))
                           :else (do
                                   (.unread *in* n)
                                   (m/repl-read request-prompt request-exit))))))))
         :eval (fn [form]
                 (let [id (var-set eval-id (inc @eval-id))]
                   (binding [*err* (tagging-writer :err id write)
                             *out* (tagging-writer :out id write)]
                     (if @command-mode
                       (let [command (get commands (first form))]
                         (throw (ex-info "Command" {::command (apply command (rest form))})))
                       (interruptible-eval form)))))
         :print (fn [x]
                  (ensure-unrepl)
                  (write [:eval x @eval-id]))
         :caught (fn [e]
                   (ensure-unrepl)
                   (cond
                     (identical? e interruption)
                     (write [:interrupted nil @eval-id])
                     (::command (ex-data e))
                     (write [:command (::command (ex-data e)) @eval-id])
                     :else (write [:exception e @eval-id]))))))))
