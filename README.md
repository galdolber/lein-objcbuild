# objcbuild

A Leiningen plugin to make clojure-objc development easy.

## Usage

Put `[lein-objcbuild "0.1.0"]` into the `:plugins` vector of your project.clj.

    $ lein compile # clojure-objc generates all the required sources
    $ lein objcbuild # translates the sources into objc, copies all headers and builds a static library

## License

Copyright © 2014 Gal Dolber
Distributed under the Eclipse Public License either version 1.0.
