import jrhy.sseq.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

expect fun runBlockingIfAvailable(function: suspend () -> Unit)

class SuspendingSequenceTest {

    @Test
    fun iterator() = runBlockingIfAvailable {
        var actual = mutableListOf<Int>()
        val iterator = suspendingSequence {
            yieldAll(1..5)
        }.iterator()
        assertTrue(iterator.hasNext())
        actual.add(iterator.next())
        actual.add(iterator.next())
        actual.add(iterator.next())
        actual.add(iterator.next())
        actual.add(iterator.next())
        assertFalse(iterator.hasNext())
        assertEquals(actual, listOf(1, 2, 3, 4, 5))
    }

    @Test
    fun for_iterator() = runBlockingIfAvailable {
        var actual = mutableListOf<Int>()
        val seq = suspendingSequence {
            yieldAll(1..5)
        }
        for (x in seq) {
            actual.add(x)
        }
        assertEquals(listOf(1, 2, 3, 4, 5), actual)
    }

    @Test
    fun toSet() = runBlockingIfAvailable {
        val seq = suspendingSequence {
            yieldAll(1..5)
        }
        assertEquals(setOf(1, 2, 3, 4, 5), seq.toSet())
    }

    @Test
    fun asSequence() = runBlockingIfAvailable {
        val seq = suspendingSequence {
            yieldAll(1..5)
        }
        val seq2 = seq.iterator().asSequence()
        assertEquals(seq.toList(), seq2.toList())
    }

    @Test
    fun asSuspendingSequence() = runBlockingIfAvailable {
        val list = (1..5).toList()
        val expected = list.asSequence()
        val actual = list.asSuspendingSequence()
        assertEquals(expected.toList(), actual.toList())
    }

    @Test
    fun filter() = runBlockingIfAvailable {
        val seq = suspendingSequence { yieldAll(1..5) }
        val actual = seq.filter { it % 2 == 0 }.toList()
        assertEquals(listOf(2, 4), actual)
    }

    @Test
    fun map() = runBlockingIfAvailable {
        val seq = suspendingSequence { yieldAll(1..5) }
        val actual = seq.map { it * 2 }.toList()
        assertEquals(listOf(2, 4, 6, 8, 10), actual)
    }

    @Test
    fun flatMap() = runBlockingIfAvailable {
        val seq = suspendingSequence { yieldAll(1..5) }
        sequenceOf(1,2).toList()
        val actual =
            seq.flatMap { x ->
                suspendingSequence {
                    yield(x)
                    yield(x * 2)
                }
            }.toList()
        assertEquals(listOf(1, 2, 2, 4, 3, 6, 4, 8, 5, 10), actual)
    }

    @Test
    fun take() = runBlockingIfAvailable {
        val seq = suspendingSequence { yieldAll(1..5) }
        val firstTwo = seq.take(2)
        assertEquals(listOf(1, 2), firstTwo.toList())
        // firstTwo aren't consumed from parent sequence
        assertEquals(listOf(1, 2, 3, 4, 5), seq.toList())
    }

    @Test
    fun drop() = runBlockingIfAvailable {
        val seq = suspendingSequence { yieldAll(1..5) }
        val lastThree = seq.drop(2)
        assertEquals(listOf(3, 4, 5), lastThree.toList())
        assertEquals(listOf(1, 2, 3, 4, 5), seq.toList())
    }

    @Test
    fun takeAndDrop() = runBlockingIfAvailable {
        val seq = suspendingSequence { yieldAll(1..5) }
        val lastThree = seq.drop(2).take(2)
        assertEquals(listOf(3, 4), lastThree.toList())
        assertEquals(listOf(1, 2, 3, 4, 5), seq.toList())
    }

}