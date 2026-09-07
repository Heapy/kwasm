package io.heapy.kwasm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/** Excludes snapshot traversal from synchronous interpreter segments. */
internal class StoreExecutionGate {
    private val mutex = Mutex()

    fun tryAcquireCapture(): Boolean = mutex.tryLock()

    fun releaseCapture() {
        mutex.unlock()
    }

    /**
     * Run [block] under the gate, suspending until the current continuation
     * segment (if any) parks and releases it. On return the gate is free
     * again, so a follow-up [tryAcquireCapture] succeeds unless a new segment
     * resumed in between.
     */
    suspend fun <T> withParkedExecution(block: () -> T): T =
        mutex.withLock { block() }

    fun <T> resumeSegment(
        continuation: Continuation<T>,
        result: Result<T>,
        delegate: ContinuationInterceptor?,
    ) {
        if (mutex.tryLock()) {
            resumeLocked(continuation, result)
            return
        }

        /*
         * Do not park the dispatcher thread when snapshot traversal owns the
         * store. The waiter suspends on the mutex and resumes the raw
         * continuation on the same dispatcher/interceptor it originally used.
         */
        val waiterContext =
            continuation.context.minusKey(ContinuationInterceptor) +
                NonCancellable +
                (delegate ?: Dispatchers.Default)
        CoroutineScope(waiterContext).launch {
            mutex.lock()
            resumeLocked(continuation, result)
        }
    }

    private fun <T> resumeLocked(
        continuation: Continuation<T>,
        result: Result<T>,
    ) {
        try {
            continuation.resumeWith(result)
        } finally {
            mutex.unlock()
        }
    }
}

/**
 * Delegates scheduling to the invocation's original interceptor, but inserts
 * the store gate immediately around each synchronous continuation segment.
 */
internal class StoreExecutionInterceptor(
    private val gate: StoreExecutionGate,
    private val delegate: ContinuationInterceptor?,
) : ContinuationInterceptor {
    override val key: CoroutineContext.Key<*>
        get() = ContinuationInterceptor

    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> {
        val gated = StoreExecutionContinuation(continuation, gate, delegate)
        return delegate?.interceptContinuation(gated) ?: gated
    }

    override fun releaseInterceptedContinuation(continuation: Continuation<*>) {
        delegate?.releaseInterceptedContinuation(continuation)
    }
}

internal class StoreExecutionContinuation<T>(
    private val continuation: Continuation<T>,
    private val gate: StoreExecutionGate,
    private val delegate: ContinuationInterceptor?,
) : Continuation<T> {
    override val context: CoroutineContext
        get() = continuation.context

    override fun resumeWith(result: Result<T>) {
        gate.resumeSegment(continuation, result, delegate)
    }
}
