(ns leiningen.objcbuild
  (:import [java.io File]
           [clojure.lang ExceptionInfo])
  (:require [leiningen.core.classpath :as classpath]
            [clojure.string :as st]
            [clojure.java.io :as io]
            [leiningen.core.main :as main]
            [me.raynes.fs.compression :as comp])
  (:use [clojure.java.shell :only [sh with-sh-dir]]
        [clojure.java.io :only [delete-file file]]
        [clojure.pprint :only [pprint]]))

(def ansi-codes
  {:reset   "\u001b[0m"
   :black   "\u001b[30m" :gray           "\u001b[1m\u001b[30m"
   :red     "\u001b[31m" :bright-red     "\u001b[1m\u001b[31m"
   :green   "\u001b[32m" :bright-green   "\u001b[1m\u001b[32m"
   :yellow  "\u001b[33m" :bright-yellow  "\u001b[1m\u001b[33m"
   :blue    "\u001b[34m" :bright-blue    "\u001b[1m\u001b[34m"
   :magenta "\u001b[35m" :bright-magenta "\u001b[1m\u001b[35m"
   :cyan    "\u001b[36m" :bright-cyan    "\u001b[1m\u001b[36m"
   :white   "\u001b[37m" :bright-white   "\u001b[1m\u001b[37m"
   :default "\u001b[39m"})

(defn log [& strs]
  (let [text (st/join " " strs)]    
    (println (str (ansi-codes :bright-white) text (ansi-codes :reset)))))

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
              :main nil
              :headers-path "include"
              :iphoneos-sdk "/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk"
              :iphonesimulator-sdk "/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk"
              :frameworks [:UIKit :Foundation]
              :includes []
              :auto-pattern #"\.(clj|cljc|java)$"
              :wait-time 50
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
      (log)
      (log (vec cmd))
      (log r))))

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
        sdk (or sdk :all)
        conf (:objcbuild project)
        objcdir (str target "/" (:objc-path conf))
        print-agent (agent nil)]
    (log "Compiling" sdk "for archs:" archs "...")
    (let [ds (str target "/" (name sdk))
          d (file ds)]
      (.mkdirs d)
      (with-sh-dir d
        (doall
         (pmap (fn [m]
                 (send print-agent
                       (fn [_]
                         (log "clang" (.getName m))))
                 (fsh "clang" "-x" "objective-c"
                      (map #(vector "-arch" (name %)) archs)
                      (st/split (:clang-params conf) #" ") (st/split (:clang-extra conf) #" ")
                      (when-not (= :all sdk)
                        [(str "-miphoneos-version-min=" (:iphone-version-min conf)) "-isysroot" (conf sdk)])
                      (str "-I" (:clojure-objc conf) "/include")
                      (str "-I" (:j2objc conf) "/include") (str "-I" objcdir)
                      (map  #(str "-I" %) (:includes conf)) "-c" (.getCanonicalPath m) "-o"
                      (str ds "/" (makeoname objcdir (.getCanonicalPath m)))))
               changes)))
      (let [filelist (str ds "/" (name sdk) ".LinkFileList")
            libpath (str ds "/" (:libname conf))]
        (spit filelist (reduce str (interpose "\n" (find-files d "o"))))
        (fsh "libtool" "-static" (when-not (= :all sdk)
                                   ["-syslibroot" (conf sdk)])
             "-filelist" filelist
             (map #(vector "-framework" (name %)) (:frameworks conf)) "-o" libpath)
        libpath))))

; https://github.com/weavejester/lein-auto

(defn project-files [project]
  (file-seq (io/file (:root project))))

(defn modified-since [^File file timestamp]
  (> (.lastModified file) timestamp))

(defn modified-files [project timestamp]
  (->> (project-files project)
       (remove #(.isDirectory ^File %))
       (filter #(modified-since % timestamp))))

(defn grep [re coll]
  (filter #(re-find re (str %)) coll))

(defn run-task [project task]
  (binding [main/*exit-process?* false]
    (main/resolve-and-apply project (list task))))

(defn add-ending-separator [^String path]
  (if (.endsWith path File/separator)
    path
    (str path File/separator)))

(defn remove-prefix [^String s ^String prefix]
  (if (.startsWith s prefix)
    (subs s (.length prefix))
    s))

(defn show-modified [project files]
  (let [root  (add-ending-separator (:root project))
        paths (map #(remove-prefix (str %) root) files)]
    (st/join ", " paths)))

(defn objcbuild-auto [project]
  (let [config (merge default (:objcbuild project))]
    (loop [time 0]
      (Thread/sleep (:wait-time config))
      (if-let [files (->> (modified-files project time)
                          (grep (:auto-pattern config))
                          (seq))]
        (do (log "Recompiling...")
            (try
              (let [t (System/currentTimeMillis)]              
                (run-task project "objcbuild")
                (log (str "Completed in " (- (System/currentTimeMillis) t) "ms")))
              (catch ExceptionInfo _
                (log "Failed.")))
            (recur (System/currentTimeMillis)))
        (recur time)))))

(def home (System/getProperty "user.home"))
(def separator (System/getProperty "file.separator"))

(defn check-lib [project]
  (if-let [lib (get-in project [:objcbuild :clojure-objc])]
    lib
    (let [ver (second (first (filter (fn [[dep ver]]
                                       (= dep 'galdolber/clojure-objc))
                                     (:dependencies project))))
          root (str home separator ".clojure-objc")
          file (str root separator ver)
          z (str file ".zip")]
      (if (.exists (java.io.File. file))
        (do
          (.exec (Runtime/getRuntime) (str "chmod 755 " file separator "j2objc"))
          file)
        (do
          (.mkdirs (java.io.File. root))
          (println (str "Downloading clojure-objc " ver ". Please wait..."))
          (with-open [in (io/input-stream (str "https://github.com/galdolber/clojure-objc/releases/download/clojure-objc-" ver "/clojure-objc-" ver ".zip"))
                      out (io/output-stream z)]
            (io/copy in out))
          (.mkdirs (java.io.File. file))
          (comp/unzip z file)
          (.delete (java.io.File. z))
          (println "Saved into" file)
          (.exec (Runtime/getRuntime) (str "chmod 755 " file separator "j2objc"))
          file)))))

(defn create-main [main]
  (let [ns (-> (namespace main)
               (clojure.string/replace #"\." "/")
               (clojure.string/replace #"-" "_"))
        f (clojure.string/replace (name main) #"-" "_")
        upperns (-> (namespace main)
                    (clojure.string/replace #"\." "")
                    clojure.string/capitalize)]
    (str "
#import <Foundation/Foundation.h>
#import \"clojure/lang/RT.h\"
#import \"clojure/lang/ObjC.h\"
#import \"clojure/lang/Var.h\"
#import \"clojure/lang/PersistentVector.h\"
#import \"clojure/lang/AFn.h\"
#import \"" ns "_" f ".h\"

int main(int argc, char * argv[]) {
  [ClojureLangObjC setObjC];
  [ClojureLangRT load__WithNSString:@\"clojure/core\"];
  [ClojureLangRT load__WithNSString:@\"" ns "\"];
  id args = ClojureLangPersistentVector_get_EMPTY_();
  for (int n = 0; n < argc; n++) {
    args = [ClojureLangRT conjWithClojureLangIPersistentCollection:args withId:[NSString stringWithUTF8String:argv[n]]];
  }
  [" upperns "_" f "_get_VAR_() invokeWithId: args];
}")))

(defn objcbuild [project & args]
  (let [project (assoc-in project [:objcbuild :clojure-objc]
                          (check-lib project))]
    (if (= "auto" (first args))
      (objcbuild-auto project)
      (let [conf (merge default (:objcbuild project) {:libname (str "lib" (:group project) ".a")})
            conf (if (:j2objc conf) conf (assoc conf :j2objc (:clojure-objc conf)))
            project (assoc project :objcbuild conf)
            target (:target-path project)]
        (run-task project "compile")
        (when-not (:main conf)
          (when-not (.exists (file (:iphoneos-sdk conf)))
            (log "SDK not found:" (:iphoneos-sdk conf) ". Set it with :iphoneos-sdk")
            (leiningen.core.main/exit))
          (when-not (.exists (file (:iphonesimulator-sdk conf)))
            (log "SDK not found:" (:iphonesimulator-sdk conf) ". Set it with :iphonesimulator-sdk")
            (leiningen.core.main/exit)))
        
        (let [objcdir (file (str target "/" (:objc-path conf)))]
          (.mkdirs objcdir)

          (doseq [p (:objc-source-paths project)]
            (fsh "cp" "-R" (str p "/.") (.getCanonicalPath objcdir)))

          (let [changes (find-changes (str target "/gen") (str target "/files") "java")
                changes (if-let [java-files (flatten (map #(walkr % ".java") (:java-source-paths project)))]
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
          (let [changes (find-changes objcdir (str target "/objc") "m")]
            (if-let [m (:main conf)]
              (let [lib (build project nil (:archs conf) changes)]
                (spit (str target "/main.m") (create-main m))
                (println "Building executable")
                (fsh "clang"
                     (map #(vector "-framework" (name %)) (:frameworks conf))
                     ;(map #(vector "-arch" (name %)) (:archs conf))
                     (st/split (:clang-params conf) #" ") (st/split (:clang-extra conf) #" ")
                     (str "-I" (:clojure-objc conf) "/include")
                     (str "-I" target "/include")
                     (map #(str "-I" %) (:includes conf))
                     "-g" "target/main.m"
                     lib
                     (str (:clojure-objc conf) "/libclojure-objc.a")
                     (str (:clojure-objc conf) "/libjre_emul.a")
                     "-ObjC" "-lobjc" "-lz" "-licucore" "-lffi"
                     "-o" (str "target/" (:name project))))
              (let [libs (for [[sdk archs] (group-by arch->sdk (:archs conf))]
                           (build project sdk archs changes))
                    libfile (file (str target "/" (:libname conf)))]
                (when (.exists libfile)
                  (delete-file libfile))
                (when-not (empty? libs)
                  (fsh "lipo" "-create" "-output" (.getCanonicalPath libfile) libs))))))))))
