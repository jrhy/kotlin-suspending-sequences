# Kotlin Suspending Sequences
Initial release: March 21, 2019

by Jeff Rhyason

# What is this?

`kotlin-suspending-sequences` is modeled after regular Kotlin sequences, except the
thing that yields things for the sequence can *itself* suspend.  It's a multiplatform
project, tested with:

* Kotlin-JVM
* Kotlin-native (macOS)
* Kotlin-JS

Other Kotlin-native platforms, including Linux and iOS, should also work.

# Why would I want it?

A regular Kotlin sequence can't suspend to get items, so if you want to 
generate a sequence from a suspending network call, this provides **satisfaction**.

# What are alternatives?

It might be possible to inline instead, at the expense of descriptive stack traces.

# How do I use it?

```
gradle assemble
```
produces JARs for platforms:
* build/libs/kotlin-suspending-sequences-jvm-0.0.1.jar
* build/libs/kotlin-suspending-sequences-js-0.0.1.jar

Or you can use Maven:
```
gradle publishToMavenLocal
```
produces:
* ~/.m2/repository/com/rhyason/kotlin-suspending-sequences
* ~/.m2/repository/com/rhyason/kotlin-suspending-sequences-js
* ~/.m2/repository/com/rhyason/kotlin-suspending-sequences-jvm
* ~/.m2/repository/com/rhyason/kotlin-suspending-sequences-macos
* ~/.m2/repository/com/rhyason/kotlin-suspending-sequences-metadata

and can be used by adding this to `build.gradle`:
```
repositories {
    mavenLocal()
}

kotlin {
    targets { ... }
    sourceSets {
        commonMain {
            implementation "com.rhyason:kotlin-suspending-sequences:0.0.1"
        }
    }
}
```

# Where could this go?

* genericize to transducers
* check if inline is a viable alternative

