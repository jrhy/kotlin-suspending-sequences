# What is this?

`kotlin-suspending-sequences` is modeled after regular Kotlin sequences, except the
thing that yields things for the sequence, can itself suspend.  It's a multiplatform
project, tested with:

* Kotlin-JVM
* Kotlin-native (macOS)
* Kotlin-JS

# Why would I want it?

A regular Kotlin sequence can't suspend to get items, so if you want to 
generate a sequence from a suspending network call.  

# What are alternatives?

It might be possible to inline instead, at the expense of descriptive stack traces.

# How do I use it?

TBD

# Where could this go?

* genericize to transducers
* check if inline is a viable alternative

