(ns unsplice.io.bis-test
  (:require [clojure.test :refer [is deftest]])
  (:import [dev.baecher.io BoundaryInputStream]
           [java.io ByteArrayInputStream]))

(defn bs [a] (ByteArrayInputStream. (byte-array a)))

(defn t [{:keys [data boundary buffer-size read-lengths]}]
  (let [s (-> (BoundaryInputStream/builder (bs data))
              (.boundary (byte-array boundary))
              (.bufferSize buffer-size)
              (.build))]
    (vec
      (if read-lengths
        (apply concat (for [l read-lengths] (.readNBytes s l)))
        (.readAllBytes s)))))

(defn kth-input [n k]
  (lazy-seq
    (cons
      (mod k 3)
      (when (< 1 n)
        (kth-input (dec n) (quot k 3))))))

(defn inputs [length]
  (if (zero? length)
    []
    (let [n (apply * (repeat length 3))]
      (map (partial kth-input length) (range n)))))

(defn read-lengths
  ([max-len]
   (read-lengths max-len max-len []))
  ([max-len remaining-len lens]
   (if (zero? remaining-len)
     (cons lens nil)
     (apply
       concat
       (for [l (range 1 (inc remaining-len))
             :let [new-lens (conj lens l)]]
         (lazy-seq
           (read-lengths max-len (- remaining-len l) new-lens)))))))

(defn index-of [haystack needle]
  (first
    (for [index (range (- (count haystack) (count needle) -1))
          :when (= (take (count needle) (drop index haystack)) needle)]
      index)))

(deftest many
  (doseq [input-len (range 4)
          input (inputs input-len)
          boundary [[1] [1 2]]
          buffer-size (range (count boundary) (+ 2 input-len))
          reads (read-lengths input-len)]
    (let [setup {:data input
                 :boundary boundary
                 :buffer-size buffer-size
                 :read-lengths reads}
          actual (t setup)
          expected (take (or (index-of input boundary) (count input)) input)]
      (is (= actual expected) setup))))

(deftest manual
  (is (= [0 0] (t {:data [0 0 1 0]
                   :boundary [1]
                   :buffer-size 1})))

  (is (= [0 0] (t {:data [0 0]
                   :boundary [1]
                   :buffer-size 1})))

  (is (= [0 0] (t {:data [0 0]
                   :boundary [1 2]
                   :buffer-size 2})))

  (is (= [0] (t {:data [0 1 2]
                 :boundary [1 2]
                 :buffer-size 2})))

  (is (= [] (t {:data [1 2]
                :boundary [1 2]
                :buffer-size 2})))

  (is (= [0 0 0] (t {:data [0 0 0 1 2]
                     :boundary [1 2]
                     :buffer-size 2})))

  (is (= [0 0 0] (t {:data [0 0 0 1 2]
                     :boundary [1 2]
                     :buffer-size 4})))

  (is (= [0 0 0 0 1] (t {:data [0 0 0 0 1]
                         :boundary [1 2]
                         :buffer-size 4})))

  (is (= [0 2 0 0 1] (t {:data [0 2 0 0 1]
                         :boundary [1 2]
                         :buffer-size 4})))

  (is (= [1 0 0] (t {:data [1 0 0]
                     :boundary [1 2]
                     :buffer-size 10})))

  (is (= [1] (t {:data [1 0 0]
                 :boundary [1 2]
                 :buffer-size 10
                 :read-lengths [1]})))

  (is (= [0 1] (t {:data [0 1 0]
                   :boundary [1 2]
                   :buffer-size 2
                   :read-lengths [2]})))

  (is (= [0 1 0] (t {:data [0 1 0]
                     :boundary [1 2]
                     :buffer-size 2
                     :read-lengths [2 1 1 1]})))

  (is (= [0 1] (t {:data [0 1]
                   :boundary [1 2]
                   :buffer-size 2
                   :read-lengths [2 1]})))

  (is (= [] (t {:data [1 2 0]
                :boundary [1 2]
                :buffer-size 10
                :read-lengths [1]}))))
