(ns unsplice.core
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.java.io :refer [file]]
            [unsplice.filename :refer [wrap-filename encode-filename multi-line-filename decode-filename content-disposition-filename]])
  (:import [java.util Arrays]
           [java.io File ByteArrayInputStream OutputStream IOException]
           [java.security MessageDigest DigestOutputStream]
           [jakarta.mail.internet MimeUtility]
           [dev.baecher.io BoundaryInputStream]
           [org.apache.commons.codec.binary Base64InputStream])
  (:gen-class))

(def version "0.9.0")

(def cr-lf (.getBytes "\r\n" "ASCII"))
(def dash-dash (.getBytes "--" "ASCII"))
(def cr-lf-cr-lf (.getBytes "\r\n\r\n" "ASCII"))
(def cr-lf-from-space (.getBytes "\r\nFrom " "ASCII"))
(def header-split-pattern #"\r\n(?!\s)")
(def boundary-pattern #"\b[bB]oundary=(\"([^\"]+)\"|([^\s;]+))")
(def content-type-name-pattern #"\bname\*?=(\"([^\"]+)\"|([^\s;]+))")

(def inline-content-types
  #{"application/pgp-signature"
    "application/ics"
    "message/rfc822"
    "text/calendar"
    "text/plain"})

(def ^:dynamic *attachment-output-path* ".")

(defn format-content-disposition [filename]
  (let [parts (wrap-filename (encode-filename filename) 62)]
    (str "Content-Disposition: attachment;\r\n "
         (if (< 1 (count parts))
           (string/join
             ";\r\n "
             (for [[index part] (map-indexed vector parts)]
               (format "filename*%d*=%s" index part)))
           (str "filename*=" (first parts))))))

(defn parse-headers [s]
  (if (empty? s)
    {}
    (let [headers (string/split s header-split-pattern)]
      (into {} (for [header headers
                     :let [parts (string/split header #": ?" 2)
                           _ (assert (= (count parts) 2) (str "headers " s))]]
                 [(string/lower-case (first parts)) (second parts)])))))

(defn parse-mail-headers [s]
  (let [headers (string/split s header-split-pattern)]
    (assert (string/starts-with? (first headers) "From ") (str "headers " (subs s 0 200)))
    (into {} (for [header (rest headers)
                   :let [parts (string/split header #": ?" 2)
                         _ (assert (= (count parts) 2) (str "bad parse: " parts))]]
               [(string/lower-case (first parts)) (second parts)]))))

(defn content-type-boundary [s]
  (when s
    (when-let [match (re-find boundary-pattern s)]
      (or (nth match 2)
          (last match)))))

(defn content-type-name [s]
  (when s
    (when-let [match (re-find content-type-name-pattern s)]
      (or (nth match 2)
          (last match)))))

(defn part-filename [headers]
  (decode-filename
    (or (content-disposition-filename (headers "content-disposition"))
        (content-type-name (headers "content-type")))))

(defn content-type [s]
  (when s
    (string/lower-case (first (string/split s #";" 2)))))

(defn transfer-encoding [headers]
  (string/lower-case (headers "content-transfer-encoding" "")))

(defn attachment? [headers]
  (and
    (not (inline-content-types (content-type (headers "content-type" ""))))
    (or (string/starts-with? (string/lower-case (headers "content-disposition" "")) "attachment;")
        (when (transfer-encoding headers)
          (string/starts-with? (headers "content-disposition" "") "inline;")))))

(defn bytes->hex [array]
  (apply str (for [b array] (format "%02x" b))))

(defn attachment-substitute [original-filename sha-1-sum]
  (let [sub-filename (format "%s (%s).txt" original-filename sha-1-sum)]
    (.getBytes
      (str
        "Content-Type: text/plain;\r\n"
        (format-content-disposition sub-filename) "\"\r\n"
        "\r\n"
        sha-1-sum "\r\n")
      "ASCII")))

(defn process-attachment [in out marker-stack headers]
  (let [filename (part-filename headers)
        encoding (transfer-encoding headers)]
    (assert filename (str "no filename in " (headers "content-disposition")))
    (assert (#{"base64" "7bit" "8bit" "quoted-printable" ""} encoding) (str "unsupported encoding " encoding))
    (.clearBoundary in)
    (.skip in (count cr-lf-cr-lf))
    (.setBoundary in (first marker-stack))
    (let [output-file (File/createTempFile "unsplice-mbox-" "")
          digest (MessageDigest/getInstance "sha-1")
          attachment-in (case encoding
                          "base64" (Base64InputStream. in)
                          "quoted-printable" (MimeUtility/decode in "quoted-printable")
                          in)]
      (with-open [attachment-out (DigestOutputStream. (io/output-stream output-file) digest)]
        (.transferTo attachment-in attachment-out))
      (let [sum (bytes->hex (.digest digest))]
        (.renameTo output-file (io/file (str *attachment-output-path* \/ sum)))
        (.write out (attachment-substitute filename sum))
        marker-stack))))

(defn process-normal-part [in out marker-stack maybe-cr-lf raw-headers content-type]
  (let [new-stack (if-let [nested-boundary (content-type-boundary content-type)]
                    (conj marker-stack (.getBytes (str "--" nested-boundary) "ASCII"))
                    marker-stack)]
    (.write out maybe-cr-lf)
    (.write out raw-headers)
    (.clearBoundary in)
    (.write out (.readNBytes in (count cr-lf-cr-lf)))
    (.setBoundary in (first new-stack))
    (.transferTo in out)
    new-stack))

(defn process-part [in out marker-stack]
  (let [marker (first marker-stack)
        maybe-cr-lf (.readNBytes in 2)]
    (if (Arrays/equals maybe-cr-lf cr-lf)
      (do
        (.write out maybe-cr-lf)
        (.setBoundary in marker)
        (.transferTo in out)
        marker-stack)
      (do
        (.setBoundary in cr-lf-cr-lf)
        (let [raw-part-headers (.readAllBytes in)
              part-headers (parse-headers (str (String. maybe-cr-lf "ASCII")
                                               (String. raw-part-headers "ASCII")))]
          (if (attachment? part-headers)
            (process-attachment in out marker-stack part-headers)
            (process-normal-part
              in out marker-stack maybe-cr-lf raw-part-headers (part-headers "content-type"))))))))

(defn process-multipart-body [in out boundary]
  (loop [marker-stack (list (.getBytes (str "--" boundary) "ASCII"))]
    (let [marker (first marker-stack)]
      (.setBoundary in marker)
      (.transferTo in out)
      (.clearBoundary in)
      (.write out (.readNBytes in (count marker)))
      (let [post-marker (.readNBytes in 2)]
        (.write out post-marker)
        (if (Arrays/equals post-marker cr-lf)
          (recur (process-part in out marker-stack))
          (let [more-parts? (< 1 (count marker-stack))]
            (.write out (.readNBytes in (count dash-dash)))
            (when more-parts?
              (recur (pop marker-stack)))))))))

(defn process-mail [in out]
  (.setBoundary in cr-lf-cr-lf)
  (let [raw-headers (.readAllBytes in)]
    (assert (= (String. raw-headers 0 5 "ASCII") "From "))
    (let [headers (parse-mail-headers (String. raw-headers "ASCII"))
          boundary (content-type-boundary (headers "content-type"))]
      (.write out raw-headers)
      (.clearBoundary in)
      (.write out (.readNBytes in (count cr-lf-cr-lf)))
      (when boundary
        (process-multipart-body in out boundary))
      (.clearBoundary in)
      (.transferTo in out))))

(defn process-mbox [input-file output-file]
  (with-open [in (.build (BoundaryInputStream/builder (io/input-stream input-file)))
              out (io/output-stream output-file)]
    (time
      (loop [mail-number 1]
        (.setBoundary in cr-lf-from-space)
        (when (zero? (mod mail-number 1000))
          (println "working on mail #" mail-number))
        (process-mail (.build (BoundaryInputStream/builder in)) out)
        (.clearBoundary in)
        (let [maybe-cr-lf (.readNBytes in (count cr-lf))]
          (if (and #_(< mail-number 2000) (pos? (count maybe-cr-lf)))
            (do
              (.write out maybe-cr-lf)
              (recur (inc mail-number)))
            (println "finished" mail-number "mails")))))))

(defn -main [& args]
  (println "unsplice-mbox " version)
  (when-not (= 2 (count args))
    (println "arguments required: INPUT-FILE OUTPUT-FILE")
    (System/exit -1))
  (let [[in-file out-file] args
        out-dir (str out-file ".d")]
    (.mkdirs (file out-dir))
    (binding [*attachment-output-path* out-dir]
      (process-mbox in-file out-file))))
