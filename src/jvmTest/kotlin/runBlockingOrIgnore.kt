import kotlinx.coroutines.runBlocking

actual fun runBlockingIfAvailable(function: suspend () -> Unit) =
    runBlocking { function() }
