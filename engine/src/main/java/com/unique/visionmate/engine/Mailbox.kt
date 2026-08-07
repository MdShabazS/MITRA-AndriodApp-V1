package com.unique.visionmate.engine

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-slot mailbox: holds at most one item. A second offer displaces and returns the previous one
 * so the caller can emit telemetry. take() suspends until an item is available or the mailbox closes.
 */
internal class Mailbox<T : Any> {

    private val slot = AtomicReference<T?>(null)
    private val signal = Channel<Unit>(Channel.CONFLATED)

    fun offer(item: T): T? {
        val displaced = slot.getAndSet(item)
        signal.trySend(Unit)
        return displaced
    }

    suspend fun take(): T? {
        while (true) {
            slot.getAndSet(null)?.let { return it }
            try {
                signal.receive()
            } catch (_: ClosedReceiveChannelException) {
                return null
            }
        }
    }

    fun close(): T? {
        val dropped = slot.getAndSet(null)
        signal.close()
        return dropped
    }
}
