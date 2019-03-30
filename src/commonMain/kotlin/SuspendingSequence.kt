package jrhy.sseq

// async sequences based on https://github.com/Kotlin/KEEP/blob/master/proposals/coroutines.md#asynchronous-sequences

import kotlin.coroutines.*
import kotlin.experimental.*

interface SuspendingSequenceScope<in T> {
    suspend fun yield(value: T)
    suspend fun yieldAll(iterable: Iterable<T>)
    suspend fun yieldAll(iterator: Iterator<T>)
    suspend fun yieldAll(iterator: SuspendingIterator<T>)
}

interface SuspendingSequence<out T> {
    operator fun iterator(): SuspendingIterator<T>
}

interface SuspendingIterator<out T> {
    suspend operator fun hasNext(): Boolean
    suspend operator fun next(): T
}

interface SuspendingIterable<out T> {
    suspend operator fun iterator(): SuspendingIterator<T>
}

suspend fun <T> SuspendingIterable<T>.asSequence(): SuspendingSequence<T> =
    iterator().asSequence()

fun <T> SuspendingIterator<T>.asSequence(): SuspendingSequence<T> =
    suspendingSequence { yieldAll(this@asSequence) }

@UseExperimental(ExperimentalTypeInference::class)
fun <T> suspendingSequence(
    context: CoroutineContext = EmptyCoroutineContext,
    @BuilderInference block: suspend SuspendingSequenceScope<T>.() -> Unit
): SuspendingSequence<T> = object : SuspendingSequence<T> {
    override fun iterator(): SuspendingIterator<T> = suspendingIterator(context, block)
}

fun <T> suspendingIterator(
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend SuspendingSequenceScope<T>.() -> Unit
): SuspendingIterator<T> =
    SuspendingIteratorCoroutine<T>(context).apply {
        nextStep = block.createCoroutine(receiver = this, completion = this)
    }

class SuspendingIteratorCoroutine<T>(
    override val context: CoroutineContext
) : SuspendingIterator<T>, SuspendingSequenceScope<T>, Continuation<Unit> {
    enum class State { INITIAL, COMPUTING_HAS_NEXT, COMPUTING_NEXT, COMPUTED, DONE }

    var state: State = State.INITIAL
    var nextValue: T? = null
    var nextStep: Continuation<Unit>? = null // null when sequence complete

    // if (state == COMPUTING_NEXT) computeContinuation is Continuation<T>
    // if (state == COMPUTING_HAS_NEXT) computeContinuation is Continuation<Boolean>
    var computeContinuation: Continuation<*>? = null

    suspend fun computeHasNext(): Boolean = suspendCoroutine { c ->
        state = State.COMPUTING_HAS_NEXT
        computeContinuation = c
        nextStep!!.resume(Unit)
    }

    suspend fun computeNext(): T = suspendCoroutine { c ->
        state = State.COMPUTING_NEXT
        computeContinuation = c
        nextStep!!.resume(Unit)
    }

    override suspend fun hasNext(): Boolean {
        when (state) {
            State.INITIAL -> return computeHasNext()
            State.COMPUTED -> return true
            State.DONE -> return false
            else -> throw IllegalStateException("Recursive dependency detected -- already computing next")
        }
    }

    override suspend fun next(): T {
        when (state) {
            State.INITIAL -> return computeNext()
            State.COMPUTED -> {
                state = State.INITIAL
                @Suppress("UNCHECKED_CAST")
                return nextValue as T
            }
            State.DONE -> throw NoSuchElementException()
            else -> throw IllegalStateException("Recursive dependency detected -- already computing next")
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun resumeIterator(hasNext: Boolean) {
        when (state) {
            State.COMPUTING_HAS_NEXT -> {
                state = State.COMPUTED
                (computeContinuation as Continuation<Boolean>).resume(hasNext)
            }
            State.COMPUTING_NEXT -> {
                state = State.INITIAL
                (computeContinuation as Continuation<T>).resume(nextValue as T)
            }
            else -> throw IllegalStateException("Was not supposed to be computing next value. Spurious yield?")
        }
    }

    // Completion continuation implementation
    override fun resumeWith(result: Result<Unit>) {
        nextStep = null
        result
            .onSuccess {
                resumeIterator(false)
            }
            .onFailure { exception ->
                state = State.DONE
                computeContinuation!!.resumeWithException(exception)
            }
    }

    // Generator implementation
    override suspend fun yield(value: T): Unit = suspendCoroutine { c ->
        nextValue = value
        nextStep = c
        resumeIterator(true)
    }

    override suspend fun yieldAll(iterable: Iterable<T>) {
        if (iterable is Collection && iterable.isEmpty()) return
        return yieldAll(iterable.iterator())
    }

    override suspend fun yieldAll(iterator: Iterator<T>) {
        while (iterator.hasNext())
            yield(iterator.next())
    }

    override suspend fun yieldAll(iterator: SuspendingIterator<T>) {
        while (iterator.hasNext())
            yield(iterator.next())
    }
}


fun <T> emptySuspendingSequence() = suspendingSequence<T> {}


/**
 * Returns a sequence containing only elements matching the given [predicate].
 *
 * The operation is _intermediate_ and _stateless_.
 */
fun <T> SuspendingSequence<T>.filter(predicate: suspend (T) -> Boolean): SuspendingSequence<T> {
    return FilteringSuspendingSequence(this, true, predicate)
}

/**
 * A sequence that returns the values from the underlying [sequence] that either match or do not match
 * the specified [predicate].
 *
 * @param sendWhen If `true`, values for which the predicate returns `true` are returned. Otherwise,
 * values for which the predicate returns `false` are returned
 */
internal class FilteringSuspendingSequence<T>(
    private val sequence: SuspendingSequence<T>,
    private val sendWhen: Boolean = true,
    private val predicate: suspend (T) -> Boolean
) : SuspendingSequence<T> {

    override fun iterator(): SuspendingIterator<T> = object : SuspendingIterator<T> {
        val iterator = sequence.iterator()
        var nextState: Int = -1 // -1 for unknown, 0 for done, 1 for continue
        var nextItem: T? = null

        private suspend fun calcNext() {
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (predicate(item) == sendWhen) {
                    nextItem = item
                    nextState = 1
                    return
                }
            }
            nextState = 0
        }

        override suspend fun next(): T {
            if (nextState == -1)
                calcNext()
            if (nextState == 0)
                throw NoSuchElementException()
            val result = nextItem
            nextItem = null
            nextState = -1
            @Suppress("UNCHECKED_CAST")
            return result as T
        }

        override suspend fun hasNext(): Boolean {
            if (nextState == -1)
                calcNext()
            return nextState == 1
        }
    }
}

/**
 * Returns a sequence containing the results of applying the given [transform] function
 * to each element in the original sequence.
 *
 * The operation is _intermediate_ and _stateless_.
 */
fun <T, R> SuspendingSequence<T>.map(transform: suspend (T) -> R): SuspendingSequence<R> {
    return TransformingSuspendingSequence(this, transform)
}



/**
 * A sequence which returns the results of applying the given [transformer] function to the values
 * in the underlying [sequence].
 */

internal class TransformingSuspendingSequence<T, R>
constructor(
    private val sequence: SuspendingSequence<T>,
    private val transformer: suspend (T) -> R
) : SuspendingSequence<R> {
    override fun iterator(): SuspendingIterator<R> = object : SuspendingIterator<R> {
        val iterator = sequence.iterator()
        override suspend fun next(): R {
            return transformer(iterator.next())
        }

        override suspend fun hasNext(): Boolean {
            return iterator.hasNext()
        }
    }

    internal fun <E> flatten(iterator: suspend (R) -> SuspendingIterator<E>): SuspendingSequence<E> {
        return FlatteningSuspendingSequence(sequence, transformer, iterator)
    }
}

internal class FlatteningSuspendingSequence<T, R, E>
constructor(
    private val sequence: SuspendingSequence<T>,
    private val transformer: suspend (T) -> R,
    private val iterator: suspend (R) -> SuspendingIterator<E>
) : SuspendingSequence<E> {
    override fun iterator(): SuspendingIterator<E> = object : SuspendingIterator<E> {
        val iterator = sequence.iterator()
        var itemIterator: SuspendingIterator<E>? = null

        override suspend fun next(): E {
            if (!ensureItemIterator())
                throw NoSuchElementException()
            return itemIterator!!.next()
        }

        override suspend fun hasNext(): Boolean {
            return ensureItemIterator()
        }

        private suspend fun ensureItemIterator(): Boolean {
            if (itemIterator?.hasNext() == false)
                itemIterator = null

            while (itemIterator == null) {
                if (!iterator.hasNext()) {
                    return false
                } else {
                    val element = iterator.next()
                    val nextItemIterator = iterator(transformer(element))
                    if (nextItemIterator.hasNext()) {
                        itemIterator = nextItemIterator
                        return true
                    }
                }
            }
            return true
        }
    }
}

/**
 * Accumulates value starting with [initial] value and applying [operation] from left to right to current accumulator value and each element.
 *
 * The operation is _terminal_.
 */
suspend inline fun <T, R> SuspendingSequence<T>.fold(
    initial: R,
    operation: (acc: R, T) -> R
): R {
    var accumulator = initial
    for (element in this) accumulator = operation(accumulator, element)
    return accumulator
}

/**
 * Returns a single sequence of all elements from results of [transform] function being invoked on each element of original sequence.
 *
 * The operation is _intermediate_ and _stateless_.
 */
fun <T, R> SuspendingSequence<T>.flatMap(transform: suspend (T) -> SuspendingSequence<R>): SuspendingSequence<R> {
    return FlatteningSuspendingSequence(this, transform, { it.iterator() })
}

fun <T> Iterator<T>.asSuspendingSequence(): SuspendingSequence<T> =
    suspendingSequence { yieldAll(this@asSuspendingSequence) }

fun <T> Iterable<T>.asSuspendingSequence(): SuspendingSequence<T> =
    suspendingSequence { yieldAll(iterator()) }

/**
 * Performs the given [action] on each element.
 *
 * The operation is _terminal_.
 */
inline suspend fun <T> SuspendingSequence<T>.forEach(action: (T) -> Unit) {
    for (element in this) action(element)
}


/**
 * Returns a [Set] of all elements.
 *
 * The returned set preserves the element iteration order of the original sequence.
 *
 * The operation is _terminal_.
 */
suspend fun <T> SuspendingSequence<T>.toSet(): Set<T> {
    return toCollection(LinkedHashSet()).optimizeReadOnlySet()
}

suspend fun <K, V> SuspendingSequence<Pair<K, V>>.toMap(): Map<K, V> {
    return toCollection(LinkedHashMap()).optimizeReadOnlyMap()
}

suspend fun <K, V> SuspendingSequence<Pair<K, V>>.toMultimap(): Map<K, Set<V>> {
    return toMultimap(LinkedHashMap()).optimizeReadOnlyMap()
}

internal fun <T> Set<T>.optimizeReadOnlySet() = when (size) {
    0 -> emptySet()
    1 -> setOf(iterator().next())
    else -> this
}

internal fun <K, V> Map<K, V>.optimizeReadOnlyMap() = when (size) {
    0 -> emptyMap()
    1 -> this.toMap()
    else -> this
}

/**
 * Returns a [List] containing all elements.
 *
 * The operation is _terminal_.
 */
suspend fun <T> SuspendingSequence<T>.toList(): List<T> {
    return this.toMutableList().optimizeReadOnlyList()
}

/**
 * Returns a [MutableList] filled with all elements of this sequence.
 *
 * The operation is _terminal_.
 */
suspend fun <T> SuspendingSequence<T>.toMutableList(): MutableList<T> {
    return toCollection(ArrayList())
}

internal fun <T> List<T>.optimizeReadOnlyList() = when (size) {
    0 -> emptyList()
    1 -> listOf(this[0])
    else -> this
}

/**
 * Appends all elements to the given [destination] collection.
 *
 * The operation is _terminal_.
 */
suspend fun <T, C : MutableCollection<in T>> SuspendingSequence<T>.toCollection(destination: C): C {
    for (item in this) {
        destination.add(item)
    }
    return destination
}


suspend fun <K, V, C : MutableMap<in K, in V>> SuspendingSequence<Pair<K, V>>.toCollection(destination: C): C {
    for (item in this) {
        destination.put(item.first, item.second)
    }
    return destination
}

suspend fun <K, V, C : MutableMap<in K, MutableSet<V>>> SuspendingSequence<Pair<K, V>>.toMultimap(destination: C): C {
    for ((key, value) in this) {
        destination.getOrPut(key) { mutableSetOf() } += value
    }
    return destination
}

inline suspend fun <T> SuspendingIterable<T>.forEach(action: (T) -> Unit): Unit {
    for (element in this) action(element)
}

suspend fun <T> SuspendingSequence<T>.first(): T {
    val iterator = iterator()
    if (!iterator.hasNext())
        throw NoSuchElementException("Sequence is empty.")
    return iterator.next()
}

suspend fun <T> SuspendingSequence<T>.firstOrNull(): T? {
    val iterator = iterator()
    if (!iterator.hasNext())
        return null
    return iterator.next()
}

interface SuspendingComparable<T> {
    suspend fun compareTo(other: T): Int
}

interface SuspendingComparator<T> {
    suspend fun compare(a: T, b: T): Int
}

fun <T> Comparator<T>.suspending() =
        SuspendingComparator<T> { a, b -> this.compare(a, b) }

fun <T> SuspendingComparator(comparison: suspend (T, T) -> Int) =
    object : SuspendingComparator<T> {
        override suspend fun compare(a: T, b: T) = comparison(a,b)
    }

/**
 * Returns a [Set] of all elements.
 *
 * The returned set preserves the element iteration order of the original collection.
 */
@Suppress("UNCHECKED_CAST")
suspend fun <T> SuspendingIterable<T>.toSet(): Set<T> {
    if (this is Collection<*>) {
        return when (size) {
            0 -> emptySet()
            1 -> setOf(if (this is List<*>) this[0] as T else (this as SuspendingIterable<T>).iterator().next())
            else -> toCollection(LinkedHashSet<T>(size))
        }
    }
    return toCollection(LinkedHashSet<T>()).optimizeReadOnlySet()
}

/**
 * Appends all elements to the given [destination] collection.
 */
suspend fun <T, C : MutableCollection<in T>> SuspendingIterable<T>.toCollection(destination: C): C {
    for (item in this) {
        destination.add(item)
    }
    return destination
}

suspend operator fun <T> SuspendingSequence<T>.plus(other: SuspendingSequence<T>) =
    suspendingSequenceOf(this, other).flatten()

suspend fun <T> suspendingSequenceOf(vararg ts: T): SuspendingSequence<T> =
    suspendingSequence {
        yieldAll(ts.iterator())
    }

suspend fun <T> SuspendingSequence<SuspendingSequence<T>>.flatten(): SuspendingSequence<T> =
    suspendingSequence {
        for (o in this@flatten)
            for (i in o)
                yield(i)
    }
