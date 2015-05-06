# lein-objcbuild

A Leiningen plugin to make clojure-objc development easy.

## Sample project

https://github.com/galdolber/clojure-objc-sample

## Usage

Download j2objc and the last clojure-objc dist from: https://github.com/galdolber/clojure-objc

Add j2objc to your PATH

Add to your project.clj:
    `:aot :all`
    `:objcbuild {:j2objc "path/to/j2objc" :clojure-objc "path/to/clojure-objc"}`

Add `[galdolber/clojure-objc "1.7.0-beta2"]` to your `:dependencies` of your project.clj.

Add  ![Clojars Project](http://clojars.org/lein-objcbuild/latest-version.svg)  into the `:plugins` vector of your project.clj.

    $ lein objcbuild auto

## Why generate a static lib?

XCode makes it really hard to add all files from a folder and stay in sync. Without a static lib you need to manually copy into the project all the generated files after every compile. IOW, it's just for convenience.

## XCode Setup

Every clojure-objc project generates an include folder with all headers and a static library lib{project name}.a in the target folder.

    Build Settings-> Other Linker Flags-> Add "-ObjC -lz -ljre_emul -lclojure-objc -l{project name}"
    Build Settings-> Header Search Path-> Add "~/.clojure-objc/{version}/include" "path/to/your/project/target/include"
    Build Settings-> Library Search Path->  Add "~/.clojure-objc/{version}/" "path/to/your/project/target/"
    
## Calling clojure functions

    #import "clojure/lang/RT.h"
    #import "clojure/lang/Var.h"
    #import "clojure/lang/ObjC.h"
    @implementation AppDelegate
    - (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)launchOptions
    {
        [ClojureLangObjC setObjC]; // required!
        [ClojureLangRT load__WithNSString:@"clojure/core"]; // load clojure core
        
        [ClojureLangRT load__WithNSString:@"clojure_objc_sample/core"]; // loads your entry point
        [[ClojureLangRT varWithNSString:@"clojure-objc-sample.core" withNSString:@"say-hi"] invokeWithId:@"Xcode"]; // call function
        ...

## Minimal project sample

	(defproject sample "0.1.0-SNAPSHOT"
	  :plugins [[lein-objcbuild "0.1.5"]]
	  :objcbuild {:archs [:i386 :armv7 :armv7s]}
	  :aot :all
	  :dependencies
	  [[galdolber/clojure-objc "1.7.0-beta2"]])

## All build options

    (defproject ...
        :objc-source-paths ["path/to/objc/sources"]
        :objcbuild 
	{:objc-path "objc"
         :headers-path "include"
         :iphoneos-sdk "/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk"
         :iphonesimulator-sdk "/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhone Simulator.sdk"
         :frameworks [:UIKit :Foundation]
         :includes []
         :iphone-version-min 5.0
         :archs [:armv7 :armv7s :arm64 :i386 :x86_64]
         :clang-params "-fmessage-length=0 -fmacro-backtrace-limit=0 -std=gnu99 -fpascal-strings -fstrict-aliasing"
         :clang-extra "-O0 -DDEBUG=1"}

## License

Copyright © 2014 Gal Dolber
Distributed under the Eclipse Public License either version 1.0.
