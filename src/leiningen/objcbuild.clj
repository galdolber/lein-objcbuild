(ns leiningen.objcbuild
  (:import [java.io File])
  (:require [leiningen.core.classpath :as classpath]
            [clojure.string :as st])
  (:use [clojure.java.shell :only [sh with-sh-dir]]
        [clojure.java.io :only [delete-file file]]
        [clojure.pprint :only [pprint]]))

(defn walk [^File dir]
  (let [children (.listFiles dir)
        subdirs (filter #(.isDirectory %) children)
        files (filter #(.isFile %) children)]
    (concat files (mapcat walk subdirs))))

(defn find-files [folder extension]
  (filter #(.endsWith (.getName %) (str "." extension)) (walk (file folder))))

(defn delete-file-recursively
    [f & [silently]]
    (let [f (file f)]
      (if (.isDirectory f)
        (doseq [child (.listFiles f)]
          (delete-file-recursively child silently)))
      (delete-file f silently)))

(defn arch->sdk [arch]
  (if (#{:i386 :x86_64} arch) :iphonesimulator-sdk :iphoneos-sdk))

(def default {;:j2objc "/path/to/j2objc/dist"
              ;:clojure-objc "path/to/clojure-objc/dist"
              :objc-path "objc"
              :headers-path "include"
              :iphoneos-sdk "/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS7.0.sdk"
              :iphonesimulator-sdk "/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator7.0.sdk"
              :frameworks [:UIKit :Foundation]
              :includes []
              :iphone-version-min 5.0
              :archs [:armv7 :armv7s :arm64 :i386 :x86_64]
              :clang-params "-fmessage-length=0 -fmacro-backtrace-limit=0 -std=gnu99 -fpascal-strings -fstrict-aliasing"
              :clang-extra "-O0 -DDEBUG=1"})

(defn makeoname [objc f]
  (str (st/replace (subs (subs f 0 (dec (count f))) (inc (count objc))) #"/" "_") "o"))

(defn fsh [& o]
  (apply sh (filter identity (flatten o))))

(defn build [project sdk archs]
  (let [target (:target-path project)
        conf (:objcbuild project)
        objcdir (str target "/" (:objc-path conf))]
    (println "Compiling" sdk "for archs:" archs "...")
    (let [ds (str target "/" (name sdk))
          d (file ds)]
      (when (.exists d)
        (delete-file-recursively d))
      (.mkdirs d)
      (with-sh-dir d
        (doseq [m (find-files objcdir "m")]
          (println "clang" (.getName m) "to" (str ds "/" (makeoname objcdir (.getCanonicalPath m))))
          (fsh "clang" "-x" "objective-c" (map #(vector "-arch" (name %)) archs)
               (st/split (:clang-params conf) #" ") (st/split (:clang-extra conf) #" ")
               (str "-miphoneos-version-min=" (:iphone-version-min conf))
               "-isysroot" (conf sdk) (str "-I" (:clojure-objc conf) "/include")
               (str "-I" (:j2objc conf) "/include") (str "-I" objcdir)
               (map  #(str "-I" %) (:includes conf)) "-c" (.getCanonicalPath m) "-o"
               (str ds "/" (makeoname objcdir (.getCanonicalPath m))))))
      (let [filelist (str ds "/" (name sdk) ".LinkFileList")
            libpath (str ds "/" (:libname conf))]
        (spit filelist (reduce str (interpose "\n" (find-files d "o"))))
        (fsh "libtool" "-static" "-syslibroot" (conf sdk) "-filelist" filelist
             (map #(vector "-framework" (name %)) (:frameworks conf)) "-o" libpath)
        libpath))))

(defn objcbuild [project & args]
  (let [conf (merge default (:objcbuild project) {:libname (str "lib" (:group project) ".a")})
        project (assoc project :objcbuild conf)
        target (:target-path project)]
    (if (some nil? [(:clojure-objc conf) (:j2objc conf)])
      (println "Missing paths to j2objc dist or clojure-objc dist in your project.clj. :objcbuildn "
               "{:j2objc \"/path/to/j2objc/dist\" :clojure-objc \"/path/to/clojure-objc/dist\"...")
      (do
        (let [objcdir (file (str target "/" (:objc-path conf)))]
          (when (.exists objcdir)
            (delete-file-recursively objcdir))
          (.mkdirs objcdir)
          (fsh "zip" "-r" (str target "/objc.jar") (str target "/gen") (:java-source-paths project))

          (sh "j2objc" "-d" (str target "/" (:objc-path conf))
              "-classpath" (reduce str (interpose ":" (classpath/get-classpath project))) (str target "/objc.jar"))

          (let [headers (file (str target "/" (:headers-path conf)))]
            (when-not (.exists headers)
              (.mkdirs headers))
            (with-sh-dir objcdir
              (sh "rsync" "-avm" "--delete" "--include='*.h'" "-f" "'hide,! */'" "." (.getCanonicalPath headers)))))

        (let [libs (for [[sdk archs] (group-by arch->sdk (:archs conf))]
                     (build project sdk archs))
              libfile (file (str target "/" (:libname conf)))]
          (when (.exists libfile)
            (delete-file libfile))
          (when-not (empty? libs)
            (fsh "lipo" "-create" "-output" (.getCanonicalPath libfile) libs)))))))


