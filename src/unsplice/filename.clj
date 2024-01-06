(ns unsplice.filename
  (:require [clojure.string :as string])
  (:import [org.apache.commons.codec.binary Base64]))

(def filename-pattern #"\b[fF]ile[nN]ame\*?=\s*(\"([^\"]+)\"|([^\s;]+))")
(def multi-line-filename-pattern #"\bfilename\*\d+\*?=(\"([^\"]+)\"|([^\s;]+))")

(defn generic-decode [s escape-char]
  (let [pattern (re-pattern (str "(?:[^" escape-char "]|" escape-char "[0-9a-fA-F]{2})"))]
    (byte-array
      (for [m (re-seq pattern s)
            :let [first-char (first m)]]
        (if (= escape-char first-char)
          (Integer/parseInt (subs m 1) 16)
          (int first-char))))))

(defn decode-rfc-2184 [s]
  (let [[_ charset payload] (re-find #"^(?<charset>[^']+)'[^']*'(?<payload>.*)" s)]
    (String. (generic-decode payload \%) charset)))

(defn decode-rfc-1342 [s]
  (let [[_ charset payload-encoding payload :as all] (re-find #"=\?(?<charset>[^?]+)\?(?<payloadencoding>[qQbB])\?(?<payload>[^?]+)\?=" s)]
    (case (first (string/lower-case payload-encoding))
      \q (String. (generic-decode payload \=) charset)
      \b (String. (Base64/decodeBase64 payload) charset)
      (assert "bad encoding char" payload-encoding))))

(defn decode-filename [s]
  (condp #(string/starts-with? %2 %1) (string/lower-case s)
    "utf-8'" (decode-rfc-2184 s)
    "iso-8859-1'" (decode-rfc-2184 s)
    "iso-8859-8'" (decode-rfc-2184 s)
    "=?utf-8?q?" (decode-rfc-1342 s)
    "=?utf-8?b?" (decode-rfc-1342 s)
    "=?iso-8859-1?q?" (decode-rfc-1342 s)
    "=?iso-8859-8?q?" (decode-rfc-1342 s)
    s))

(defn print-safe? [c]
  (or (<= (int \0) c (int \9))
      (<= (int \a) c (int \z))
      (<= (int \A) c (int \Z))
      (#{(int \.) (int \-) (int \_)} c)))

(defn encode-filename [s]
  (let [utf-8-encoded (.getBytes s "utf-8")]
    (str "utf-8''"
         (apply str (for [b utf-8-encoded]
                      (if (print-safe? b) (char b) (format "%%%02X" b)))))))

(defn wrap-filename [s max-length]
  (loop [remaining s
         lines []]
    (if (<= (count remaining) max-length)
      (conj lines remaining)
      (let [end-index (cond
                          (= \% (nth remaining (dec max-length))) (dec max-length)
                          (= \% (nth remaining (- max-length 2))) (- max-length 2)
                          :else max-length)]
        (recur (subs remaining end-index) (conj lines (subs remaining 0 end-index)))))))

(defn multi-line-filename [s]
  (when-let [parts (re-seq multi-line-filename-pattern s)]
    (apply str (for [part parts]
                 (or (nth part 2)
                     (last part))))))

(defn content-disposition-filename [s]
  (when s
    (or (when-let [match (re-find filename-pattern s)]
          (or (nth match 2)
              (last match)))
        (multi-line-filename s))))
