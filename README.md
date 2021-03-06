# Wombat-J

A Scheme-like LISP, implemented in Clojure, which compiles at runtime to JVM bytecode.

Upon close inspection one might notice an extraordinary similarity between the Wombat-J
reader and compiler and the ones in Clojure. Truly a bizarre coincidence. If anything,
it's evidence that both implementations were divinely inspired.

Wombat-J requires at least Java 1.7, but runs considerably faster on 1.8. There is a
100-200x performance boost between 1.7 and 1.8 when using MethodHandles, at least
according to my rudimentary and totally undocumented benchmarks, performed nearly a year
ago and about which I have only the vaguest recollection. I also (probably) recall that in
1.7, old-style reflection was actually faster than MethodHandles. So use 1.8. Besides,
it's been out for like 2 years at least. Get with the times, man!


## Usage

- Install Java 1.8 and [Leiningen](http://leiningen.org)
- `lein run`
- I like to set inferior-lisp-program to "lein run" and run the REPL in emacs. There's no
  readline support, so it's pretty tedious otherwise. I suppose rlwrap would work, though
  I haven't tried it.


## Interesting Implementation Details

My original focus was on learning the uses and limitations of invokeDynamic. I quickly
discovered that it wasn't going to work very well with function objects, unless I had
pre-defined interfaces for every arity. If I did that, then I may as well just use
invokeInterface, and where's the fun in that? It seems to me that invokeDynamic is better
suited to dynamic languages where a static type hierarchy does exist, but just isn't known
till runtime. Duck typing, not so much. However, I did manage to use invokeDynamic, in
conjunction with a VolatileCallSite, to create Wombat's version of Vars.

Instead of invokeDynamic, the generated function classes have a static attribute
containing a MethodHandle to each invoke method, one per arity. Invoking a function first
calls getHandle with the desired arity, which returns the associated MethodHandle (or
throws an arity exception) and then invokes the MethodHandle with the arguments on the
stack. Apply is handled by unrolling the list of arguments onto the stack first. Variadic
arities are handled by converting the MethodHandle for the variadic invoke method into a
varargs collector.


## Syntax and special forms

Coming soon!


## License

Copyright © 2014-2015 Jon Distad

Distributed under the Eclipse Public License either version 1.0 or (at your option) any
later version.
