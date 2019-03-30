
actual fun runBlockingIfAvailable(function: suspend () -> Unit) =
    println("ignoring test due to lack of runBlocking on wasm runtime")
