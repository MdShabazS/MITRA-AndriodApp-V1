package com.unique.visionmate.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MailboxTest {

    @Test
    fun offer_emptyMailbox_returnsNullDisplaced() {
        val m = Mailbox<String>()
        assertNull(m.offer("a"))
    }

    @Test
    fun offer_secondItem_displacesAndReturnsFirst() {
        val m = Mailbox<String>()
        m.offer("a")
        val displaced = m.offer("b")
        assertEquals("a", displaced)
    }

    @Test
    fun offer_thirdItem_displacesSecond() {
        val m = Mailbox<String>()
        m.offer("a")
        m.offer("b")
        val displaced = m.offer("c")
        assertEquals("b", displaced)
    }

    @Test
    fun take_returnsLatestOffered() = runTest {
        val m = Mailbox<String>()
        m.offer("a")
        val taken = withTimeout(1_000) { m.take() }
        assertEquals("a", taken)
    }

    @Test
    fun take_afterDisplacement_returnsLatest() = runTest {
        val m = Mailbox<String>()
        m.offer("a")
        m.offer("b")
        val taken = withTimeout(1_000) { m.take() }
        assertEquals("b", taken)
    }

    @Test
    fun take_emptyThenOffer_resumes() = runTest {
        val m = Mailbox<String>()
        val deferred = async { m.take() }
        delay(10)
        m.offer("x")
        assertEquals("x", deferred.await())
    }

    @Test
    fun close_unblocksPendingTake_withNull() = runTest(StandardTestDispatcher()) {
        val m = Mailbox<String>()
        var result: String? = "not-set"
        val job = launch { result = m.take() }
        advanceUntilIdle()
        m.close()
        advanceUntilIdle()
        job.join()
        assertNull(result)
    }

    @Test
    fun take_drainsAllItemsInOrderOfArrival() = runTest {
        val m = Mailbox<String>()
        m.offer("a")
        assertEquals("a", m.take())
        m.offer("b")
        assertEquals("b", m.take())
        // Empty after drain — next take suspends; verified by take_emptyThenOffer_resumes
    }

    @Test
    fun offer_nonNullCheck_returnsDisplacedReference() {
        val m = Mailbox<String>()
        m.offer("a")
        assertNotNull(m.offer("b"))
    }
}
