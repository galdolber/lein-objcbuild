# objcbuild

A Leiningen plugin to make clojure-objc development easy.

## Usage

Put `[objcbuild "0.1.0"]` into the `:plugins` vector of your project.clj.

    $ lein compile # clojure-objc generates all the required sources
    $ lein objcbuild # convert the sources to objc, copy all headers and build a static library

## License

Copyright © 2014 Gal Dolber
Distributed under the Eclipse Public License either version 1.0.
