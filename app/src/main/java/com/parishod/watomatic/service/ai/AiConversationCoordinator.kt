package com.parishod.watomatic.service.ai

import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates AI request preparation and in-flight execution per conversation key.
 *
 * Concurrent requests for the same identifiable conversation serialize in FIFO order so
 * policy checks, conversation history reads, and post-dispatch persistence observe consistent
 * state, while distinct identifiable senders execute without blocking one another.
 */
class AiConversationCoordinator {

    private class ConversationLane {
        var active: Boolean = false
        val queue = ArrayDeque<(Runnable) -> Unit>()
    }

    private val lock = Any()
    private val lanes = HashMap<String, ConversationLane>()
    private val destroyed = AtomicBoolean(false)

    fun interface PermitReleaser {
        fun release()
    }

    fun interface PermitTask {
        fun execute(releasePermit: Runnable)
    }

    fun interface ExecutorSupplier {
        fun get(): ExecutorService?
    }

    fun submit(
        conversationKey: String,
        executorSupplier: ExecutorSupplier,
        onCancelledBeforeStart: Runnable?,
        task: PermitTask
    ): Boolean {
        return submit(
            conversationKey = conversationKey,
            executorProvider = { executorSupplier.get() },
            onCancelledBeforeStart = onCancelledBeforeStart?.let { { it.run() } },
            task = { releasePermit -> task.execute(releasePermit) }
        )
    }

    /**
     * Submits [task] to execute under the per-[conversationKey] lane on [executorProvider].
     * The task receives an idempotent [Runnable] permit releaser that MUST be invoked when
     * the request completes, aborts, falls back, or encounters an error.
     */
    fun submit(
        conversationKey: String,
        executorProvider: () -> ExecutorService?,
        onCancelledBeforeStart: (() -> Unit)? = null,
        task: (Runnable) -> Unit
    ): Boolean {
        if (destroyed.get()) {
            onCancelledBeforeStart?.invoke()
            return false
        }

        val runner: (Runnable) -> Unit = { releasePermit ->
            task(releasePermit)
        }

        val dispatchNow: Boolean
        synchronized(lock) {
            if (destroyed.get()) {
                onCancelledBeforeStart?.invoke()
                return false
            }
            val lane = lanes.getOrPut(conversationKey) { ConversationLane() }
            if (!lane.active) {
                lane.active = true
                dispatchNow = true
            } else {
                lane.queue.addLast(runner)
                dispatchNow = false
            }
        }

        if (dispatchNow) {
            dispatchTask(conversationKey, executorProvider, runner, onCancelledBeforeStart)
        }
        return true
    }

    private fun dispatchTask(
        conversationKey: String,
        executorProvider: () -> ExecutorService?,
        runner: (Runnable) -> Unit,
        onCancelledBeforeStart: (() -> Unit)?
    ) {
        val released = AtomicBoolean(false)
        val releasePermit = Runnable {
            if (released.compareAndSet(false, true)) {
                releaseAndPumpNext(conversationKey, executorProvider)
            }
        }

        val exec = if (destroyed.get()) null else executorProvider()
        if (exec == null || exec.isShutdown || exec.isTerminated) {
            onCancelledBeforeStart?.invoke()
            releasePermit.run()
            return
        }

        try {
            exec.execute {
                var handoverToAsync = false
                try {
                    if (destroyed.get()) {
                        onCancelledBeforeStart?.invoke()
                        return@execute
                    }
                    runner(releasePermit)
                    handoverToAsync = true
                } catch (_: Throwable) {
                    if (!handoverToAsync) {
                        onCancelledBeforeStart?.invoke()
                    }
                } finally {
                    if (!handoverToAsync) {
                        releasePermit.run()
                    }
                }
            }
        } catch (_: Throwable) {
            onCancelledBeforeStart?.invoke()
            releasePermit.run()
        }
    }

    private fun releaseAndPumpNext(
        conversationKey: String,
        executorProvider: () -> ExecutorService?
    ) {
        val nextTask: ((Runnable) -> Unit)?
        synchronized(lock) {
            val lane = lanes[conversationKey] ?: return
            if (destroyed.get()) {
                lane.queue.clear()
                lane.active = false
                lanes.remove(conversationKey)
                return
            }
            nextTask = if (lane.queue.isNotEmpty()) lane.queue.removeFirst() else null
            if (nextTask == null) {
                lane.active = false
                lanes.remove(conversationKey)
            }
        }
        if (nextTask != null) {
            dispatchTask(conversationKey, executorProvider, nextTask, null)
        }
    }

    fun cancelAll() {
        destroyed.set(true)
        synchronized(lock) {
            lanes.values.forEach {
                it.queue.clear()
                it.active = false
            }
            lanes.clear()
        }
    }

    fun getActiveLaneCountForTesting(): Int {
        synchronized(lock) {
            return lanes.count { it.value.active }
        }
    }

    companion object {
        @JvmStatic
        fun buildConversationKey(
            packageName: String,
            senderKey: String,
            eventKey: String?
        ): String {
            return if (AiPromptAndPolicyHelper.isUnknownSender(senderKey)) {
                "unknown:${eventKey ?: UUID.randomUUID().toString()}"
            } else {
                "$packageName|$senderKey"
            }
        }
    }
}
