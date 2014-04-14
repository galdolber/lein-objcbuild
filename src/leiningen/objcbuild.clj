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

(defn walkr [folder ext]
  (doall (filter #(.endsWith (.getName %) ext)
                 (file-seq (file folder)))))

(defn find-files [folder extension]
  (filter #(.endsWith (.getName %) (str "." extension)) (walk (file folder))))

(defn delete-file-recursively
    [f & [silently]]
    (let [f (file f)]
      (if (.isDirectory f)
        (doseq [child (.listFiles f)]
          (delete-file-recursively child silently)))
      (delete-file f silently)))

(defn files-map [folder ext]
  (reduce conj {}
          (for [f (walkr folder ext)]
            [(.getCanonicalPath f)
             (.lastModified f)])))

(defn arch->sdk [arch]
  (if (#{:i386 :x86_64} arch) :iphonesimulator-sdk :iphoneos-sdk))

(def default {;:j2objc "/path/to/j2objc/dist"
              ;:clojure-objc "path/to/clojure-objc/dist"
              :objc-path "objc"
              :headers-path "include"
              :iphoneos-sdk "/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS7.1.sdk"
              :iphonesimulator-sdk "/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator7.1.sdk"
              :frameworks [:UIKit :Foundation]
              :includes []
              :iphone-version-min 5.0
              :archs [:armv7 :armv7s :arm64 :i386 :x86_64]
              :clang-params "-fmessage-length=0 -fmacro-backtrace-limit=0 -std=gnu99 -fpascal-strings -fstrict-aliasing"
              :clang-extra "-O0 -g -DDEBUG=1"})

(defn makeoname [objc f]
  (str (st/replace (subs (subs f 0 (dec (count f))) (inc (count objc))) #"/" ".") "o"))

(defn fsh [& o]
  (let [cmd (filter identity (flatten o))
        r (apply sh cmd)]
    (when-not (zero? (:exit r))
      (println )
      (println cmd)
      (println r))))

(defn find-changes [gen old-map-file type]
  (let [old-map-file (file (str old-map-file "." type "map"))
        curr-map (files-map gen (str "." type))
        old-map (if (.exists old-map-file)
                  (read-string (slurp old-map-file))
                  {})
        changes (filter (fn [[file t]]
                          (let [o (get old-map file)]
                            (or (nil? o) (not (= o t))))) curr-map)]
    (spit old-map-file (pr-str curr-map))
    (map (comp file first) changes)))

(defn build [project sdk archs changes]
  (let [target (:target-path project)
        conf (:objcbuild project)
        objcdir (str target "/" (:objc-path conf))
        print-agent (agent nil)]
    (println "Compiling" sdk "for archs:" archs "...")
    (let [ds (str target "/" (name sdk))
          d (file ds)]
      (.mkdirs d)
      (with-sh-dir d
        (doall
         (pmap (fn [m]
                 (send print-agent
                       (fn [_]
                         (println "clang" (.getName m))))
                 (fsh "clang" "-x" "objective-c" (map #(vector "-arch" (name %)) archs)
                      (st/split (:clang-params conf) #" ") (st/split (:clang-extra conf) #" ")
                      (str "-miphoneos-version-min=" (:iphone-version-min conf))
                      "-isysroot" (conf sdk) (str "-I" (:clojure-objc conf) "/include")
                      (str "-I" (:j2objc conf) "/include") (str "-I" objcdir)
                      (map  #(str "-I" %) (:includes conf)) "-c" (.getCanonicalPath m) "-o"
                      (str ds "/" (makeoname objcdir (.getCanonicalPath m)))))
               changes)))
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
        (when-not (.exists (file (:iphoneos-sdk conf)))
          (println "SDK not found:" (:iphoneos-sdk conf) ". Set it with :iphoneos-sdk")
          (leiningen.core.main/exit))
        (when-not (.exists (file (:iphonesimulator-sdk conf)))
          (println "SDK not found:" (:iphonesimulator-sdk conf) ". Set it with :iphonesimulator-sdk")
          (leiningen.core.main/exit))
        
        (let [objcdir (file (str target "/" (:objc-path conf)))]
          #_(when (.exists objcdir)
            (delete-file-recursively objcdir))
          (.mkdirs objcdir)
          (doseq [p (:objc-source-paths project)]
            (fsh "cp" "-R" (str p "/.") (.getCanonicalPath objcdir)))

          
          (let [changes (find-changes (str target "/gen") (str target "/files") "java")
                changes (if-let [java-files (flatten (map walkr (:java-source-paths project)))]
                          (into changes java-files)
                          changes)
                changes (map #(.getCanonicalPath %) changes)]
            (when-not (empty? changes)
              (fsh "zip" (str target "/objc.jar") changes)
              (fsh (str (:j2objc conf) "/j2objc") "-d" (str target "/" (:objc-path conf))
                   "-classpath" (reduce str (interpose ":" (classpath/get-classpath project))) (str target "/objc.jar"))
              (.delete (file (str target "/objc.jar")))))

          (let [headers (file (str target "/" (:headers-path conf)))]
            (when-not (.exists headers)
              (.mkdirs headers))
            (with-sh-dir objcdir
              (fsh "rsync" "-avm" "--delete" "--include" "*.h" "--exclude" "*.m" "." (.getCanonicalPath headers))))

          (let [changes (find-changes objcdir (str target "/objc") "m")
                libs (for [[sdk archs] (group-by arch->sdk (:archs conf))]
                       (build project sdk archs changes))
                libfile (file (str target "/" (:libname conf)))]
            (when (.exists libfile)
              (delete-file libfile))
            (when-not (empty? libs)
              (fsh "lipo" "-create" "-output" (.getCanonicalPath libfile) libs))))))))


