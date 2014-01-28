# lein-objcbuild

A Leiningen plugin to make clojure-objc development easy.

## Usage

Download j2objc and the last clojure-objc dist from: https://github.com/galdolber/clojure-objc

Add j2objc to your PATH

Add to your project.clj:
    `:aot :all`
    `:objcbuild {:j2objc "path/to/j2objc" :clojure-objc "path/to/clojure-objc"}`

Add `[galdolber/clojure-objc "1.5.1"]` to your `:dependencies` of your project.clj.

Put `[lein-objcbuild "0.1.2"]` into the `:plugins` vector of your project.clj.

    $ lein compile # clojure-objc generates all the required sources
    $ lein objcbuild # translates the sources into objc, copies all headers and builds a fat static library

## XCode Setup

Every clojure-objc project generates an include folder with all headers and a static library lib{project name}.a in the target folder.

    Build Settings-> Other Linker Flags-> Add "-ObjC -lz -ljre_emul -lclojure-objc -l{project name}"
    Build Settings-> Header Search Path-> Add "path/to/j2objc/include" "path/to/clojure-objc/include" "path/to/your/project/target/include"
    Build Settings-> Library Search Path->  Add "path/to/j2objc/lib" "path/to/clojure-objc/" "path/to/your/project/target/"
    
## Calling clojure functions

    #import "clojure/lang/RT.h"
    #import "clojure/lang/Var.h"
    #import "clojure/lang/ObjC.h"
    @implementation AppDelegate
    - (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)launchOptions
    {
        [ClojureLangObjC setObjC]; // required!
        [ClojureLangRT load__WithNSString:@"clojure/core"]; // load clojure core
        
        [ClojureLangRT load__WithNSString:@"clojure_objc_sample/core"]; // loads your entry point (IMPORTANT: replace - with _ for loading)
        [[ClojureLangRT varWithNSString:@"clojure-objc-sample.core" withNSString:@"say-hi"] invokeWithId:@"Xcode"]; // call function
        ...

## Development cycle

    * Make changes
    * lein compile; lein objcbuild
    * Run XCode project
    
## Sample project

https://github.com/galdolber/clojure-objc-sample

## Future work

    * Incremental compiles

## Build options

    (defproject ...
        :objc-source-paths ["path/to/objc/sources"]
        {:j2objc "/path/to/j2objc/dist"
        :clojure-objc "path/to/clojure-objc/dist"
        :objc-path "objc"
        :headers-path "include"
        :iphoneos-sdk "/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS7.0.sdk"
        :iphonesimulator-sdk "/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhone Simulator7.0.sdk"
        :frameworks [:UIKit :Foundation]
        :includes []
        :iphone-version-min 5.0
        :archs [:armv7 :armv7s :arm64 :i386 :x86_64]
        :clang-params "-fmessage-length=0 -fmacro-backtrace-limit=0 -std=gnu99 -fpascal-strings -fstrict-aliasing"
        :clang-extra "-O0 -DDEBUG=1"}`

## License

Copyright © 2014 Gal Dolber
Distributed under the Eclipse Public License either version 1.0.
