package com.parishod.watomatic.service.reply

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.app.RemoteInput
import com.parishod.watomatic.NotificationWear
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Centralized dispatch boundary for executing Android notification replies.
 *
 * Responsible only for validating prerequisites, constructing `RemoteInput` payloads,
 * coordinating minimal in-flight concurrency, invoking `PendingIntent.send()`, and
 * returning an accurate [ReplyExecutionResult]. Does not manage databases.
 */
class ReplyExecutionManager @JvmOverloads constructor(
    context: Context,
    private val pendingIntentSender: PendingIntentSender = DefaultPendingIntentSender(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val preDispatchValidator: PreDispatchValidator? = null
) {
    fun interface PendingIntentSender {
        @Throws(PendingIntent.CanceledException::class)
        fun send(context: Context, pendingIntent: PendingIntent, fillInIntent: Intent)
    }

    fun interface PreDispatchValidator {
        @Throws(SecurityException::class)
        fun validate(context: Context, pendingIntent: PendingIntent, fillInIntent: Intent)
    }

    fun interface StatusTransitionListener {
        fun onTransition(attempt: ReplyAttempt)
    }

    /**
     * Explicit [SecurityException] indicating a security check failed deterministically
     * BEFORE Android `PendingIntent.send()` was invoked.
     */
    class PreDispatchSecurityException @JvmOverloads constructor(
        message: String,
        cause: Throwable? = null
    ) : SecurityException(message, cause)

    private class DefaultPendingIntentSender : PendingIntentSender {
        override fun send(context: Context, pendingIntent: PendingIntent, fillInIntent: Intent) {
            pendingIntent.send(context, 0, fillInIntent)
        }
    }

    private val appContext: Context = context.applicationContext
    private val inFlightKeys = ConcurrentHashMap<String, String>()
    private val shutdownLock = Any()
    private val shutDown = AtomicBoolean(false)

    @JvmOverloads
    fun executeReply(
        attempt: ReplyAttempt,
        notificationWear: NotificationWear?,
        transitionListener: StatusTransitionListener? = null
    ): ReplyExecutionResult {
        if (attempt.status != ReplyExecutionStatus.GENERATED) {
            val failureCategory = when (attempt.status) {
                ReplyExecutionStatus.UNKNOWN -> FAILURE_UNKNOWN_OUTCOME_LOCKED
                ReplyExecutionStatus.DISPATCHING -> FAILURE_CONCURRENT_IN_FLIGHT
                ReplyExecutionStatus.DISPATCHED,
                ReplyExecutionStatus.FAILED,
                ReplyExecutionStatus.CANCELLED,
                ReplyExecutionStatus.GENERATED -> FAILURE_INVALID_ATTEMPT_STATE
            }
            return ReplyExecutionResult(
                executionId = attempt.attemptId,
                status = ReplyExecutionStatus.FAILED,
                dispatchAttempted = false,
                dispatchedNormally = false,
                failureCategory = failureCategory,
                diagnosticMessage = "Cannot execute attempt in ${attempt.status} state",
                timestampMs = clock()
            )
        }

        var currentAttempt = attempt
        notifyTransition(transitionListener, currentAttempt)

        if (isShutDown()) {
            return cancelForShutdown(currentAttempt, transitionListener)
        }

        if (currentAttempt.replyText.isNullOrBlank()) {
            currentAttempt = currentAttempt.transitionTo(ReplyExecutionStatus.FAILED, clock())
            notifyTransition(transitionListener, currentAttempt)
            return ReplyExecutionResult(
                executionId = currentAttempt.attemptId,
                status = ReplyExecutionStatus.FAILED,
                dispatchAttempted = false,
                dispatchedNormally = false,
                failureCategory = FAILURE_INVALID_REPLY_TEXT,
                diagnosticMessage = "Reply text is null or blank",
                timestampMs = currentAttempt.updatedAtMs
            )
        }

        if (notificationWear == null) {
            currentAttempt = currentAttempt.transitionTo(ReplyExecutionStatus.FAILED, clock())
            notifyTransition(transitionListener, currentAttempt)
            return ReplyExecutionResult(
                executionId = currentAttempt.attemptId,
                status = ReplyExecutionStatus.FAILED,
                dispatchAttempted = false,
                dispatchedNormally = false,
                failureCategory = FAILURE_MISSING_WEAR_DATA,
                diagnosticMessage = "NotificationWear payload is null",
                timestampMs = currentAttempt.updatedAtMs
            )
        }

        val remoteInputs = notificationWear.remoteInputs
        val freeFormRemoteInput: RemoteInput? = remoteInputs?.firstOrNull { it.allowFreeFormInput }
        if (freeFormRemoteInput == null) {
            currentAttempt = currentAttempt.transitionTo(ReplyExecutionStatus.FAILED, clock())
            notifyTransition(transitionListener, currentAttempt)
            return ReplyExecutionResult(
                executionId = currentAttempt.attemptId,
                status = ReplyExecutionStatus.FAILED,
                dispatchAttempted = false,
                dispatchedNormally = false,
                failureCategory = FAILURE_MISSING_REMOTE_INPUT,
                diagnosticMessage = "No free-form RemoteInput available on notification",
                timestampMs = currentAttempt.updatedAtMs
            )
        }

        val pendingIntent = notificationWear.pendingIntent
        if (pendingIntent == null) {
            currentAttempt = currentAttempt.transitionTo(ReplyExecutionStatus.FAILED, clock())
            notifyTransition(transitionListener, currentAttempt)
            return ReplyExecutionResult(
                executionId = currentAttempt.attemptId,
                status = ReplyExecutionStatus.FAILED,
                dispatchAttempted = false,
                dispatchedNormally = false,
                failureCategory = FAILURE_MISSING_PENDING_INTENT,
                diagnosticMessage = "Notification action PendingIntent is null",
                timestampMs = currentAttempt.updatedAtMs
            )
        }

        val lockKey = resolveCoordinationKey(currentAttempt, notificationWear)
        if (lockKey == null) {
            currentAttempt = currentAttempt.transitionTo(ReplyExecutionStatus.FAILED, clock())
            notifyTransition(transitionListener, currentAttempt)
            return ReplyExecutionResult(
                executionId = currentAttempt.attemptId,
                status = ReplyExecutionStatus.FAILED,
                dispatchAttempted = false,
                dispatchedNormally = false,
                failureCategory = FAILURE_MISSING_WEAR_DATA,
                diagnosticMessage = "Missing stable notification coordination key",
                timestampMs = currentAttempt.updatedAtMs
            )
        }
        val ownerToken = currentAttempt.attemptId
        var shutDownBeforeLock = false
        var lockAcquired = false
        synchronized(shutdownLock) {
            if (shutDown.get()) {
                shutDownBeforeLock = true
            } else {
                lockAcquired = inFlightKeys.putIfAbsent(lockKey, ownerToken) == null
            }
        }
        if (shutDownBeforeLock) {
            return cancelForShutdown(currentAttempt, transitionListener)
        }
        if (!lockAcquired) {
            currentAttempt = currentAttempt.transitionTo(ReplyExecutionStatus.CANCELLED, clock())
            notifyTransition(transitionListener, currentAttempt)
            return ReplyExecutionResult(
                executionId = currentAttempt.attemptId,
                status = ReplyExecutionStatus.CANCELLED,
                dispatchAttempted = false,
                dispatchedNormally = false,
                failureCategory = FAILURE_CONCURRENT_IN_FLIGHT,
                diagnosticMessage = "Concurrent dispatch already in progress for target",
                timestampMs = currentAttempt.updatedAtMs
            )
        }

        try {
            if (isShutDown()) {
                return cancelForShutdown(currentAttempt, transitionListener)
            }

            val localIntent = Intent().apply {
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            val localBundle = Bundle().apply {
                putCharSequence(freeFormRemoteInput.resultKey, currentAttempt.replyText)
            }
            RemoteInput.addResultsToIntent(arrayOf(freeFormRemoteInput), localIntent, localBundle)

            if (preDispatchValidator != null) {
                try {
                    preDispatchValidator.validate(appContext, pendingIntent, localIntent)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Pre-dispatch security check failed (${e.javaClass.simpleName})")
                    currentAttempt = currentAttempt.transitionTo(ReplyExecutionStatus.FAILED, clock())
                    notifyTransition(transitionListener, currentAttempt)
                    return ReplyExecutionResult(
                        executionId = currentAttempt.attemptId,
                        status = ReplyExecutionStatus.FAILED,
                        dispatchAttempted = false,
                        dispatchedNormally = false,
                        failureCategory = FAILURE_SECURITY_EXCEPTION,
                        diagnosticMessage = "Pre-dispatch security check failed (${e.javaClass.simpleName})",
                        timestampMs = currentAttempt.updatedAtMs
                    )
                }
            }

            // Perform the final shutdown gate before emitting DISPATCHING so a pre-dispatch
            // shutdown cancellation never leaves a persisted DISPATCHING / dispatch_attempted
            // transition in storage or history. No lock is held across listeners or IPC.
            if (isShutDown()) {
                return cancelForShutdown(currentAttempt, transitionListener)
            }

            currentAttempt = currentAttempt.transitionTo(ReplyExecutionStatus.DISPATCHING, clock())
            notifyTransition(transitionListener, currentAttempt)

            // Intentionally catch Exception only (not Error/Throwable) so fatal JVM errors
            // such as OutOfMemoryError or StackOverflowError are never concealed; the outer
            // finally block still guarantees inFlightKeys release.
            val dispatchError: Exception? = try {
                pendingIntentSender.send(appContext, pendingIntent, localIntent)
                null
            } catch (e: Exception) {
                e
            }

            return when (dispatchError) {
                null -> {
                    currentAttempt = currentAttempt.transitionTo(ReplyExecutionStatus.DISPATCHED, clock())
                    notifyTransition(transitionListener, currentAttempt)
                    ReplyExecutionResult(
                        executionId = currentAttempt.attemptId,
                        status = ReplyExecutionStatus.DISPATCHED,
                        dispatchAttempted = true,
                        dispatchedNormally = true,
                        failureCategory = null,
                        diagnosticMessage = "PendingIntent dispatch accepted",
                        timestampMs = currentAttempt.updatedAtMs
                    )
                }
                is PendingIntent.CanceledException -> {
                    Log.e(TAG, "PendingIntent dispatch cancelled (${dispatchError.javaClass.simpleName})")
                    currentAttempt = currentAttempt.transitionTo(ReplyExecutionStatus.CANCELLED, clock())
                    notifyTransition(transitionListener, currentAttempt)
                    ReplyExecutionResult(
                        executionId = currentAttempt.attemptId,
                        status = ReplyExecutionStatus.CANCELLED,
                        dispatchAttempted = true,
                        dispatchedNormally = false,
                        failureCategory = FAILURE_PENDING_INTENT_CANCELED,
                        diagnosticMessage = "PendingIntent cancelled (${dispatchError.javaClass.simpleName})",
                        timestampMs = currentAttempt.updatedAtMs
                    )
                }
                is PreDispatchSecurityException -> {
                    Log.e(TAG, "PendingIntent dispatch rejected before send (${dispatchError.javaClass.simpleName})")
                    currentAttempt = currentAttempt.transitionTo(ReplyExecutionStatus.FAILED, clock())
                    notifyTransition(transitionListener, currentAttempt)
                    ReplyExecutionResult(
                        executionId = currentAttempt.attemptId,
                        status = ReplyExecutionStatus.FAILED,
                        dispatchAttempted = false,
                        dispatchedNormally = false,
                        failureCategory = FAILURE_SECURITY_EXCEPTION,
                        diagnosticMessage = "Dispatch rejected before send (${dispatchError.javaClass.simpleName})",
                        timestampMs = currentAttempt.updatedAtMs
                    )
                }
                is SecurityException -> {
                    // PendingIntent.send() crosses an opaque Binder IPC boundary; a generic
                    // SecurityException does not prove dispatch never happened.
                    Log.e(TAG, "PendingIntent dispatch security outcome ambiguous (${dispatchError.javaClass.simpleName})")
                    currentAttempt = currentAttempt.transitionTo(ReplyExecutionStatus.UNKNOWN, clock())
                    notifyTransition(transitionListener, currentAttempt)
                    ReplyExecutionResult(
                        executionId = currentAttempt.attemptId,
                        status = ReplyExecutionStatus.UNKNOWN,
                        dispatchAttempted = true,
                        dispatchedNormally = false,
                        failureCategory = FAILURE_AMBIGUOUS_SECURITY_EXCEPTION,
                        diagnosticMessage = "Ambiguous security outcome (${dispatchError.javaClass.simpleName})",
                        timestampMs = currentAttempt.updatedAtMs
                    )
                }
                else -> {
                    // Outcome after calling PendingIntent.send() cannot be known reliably; do not retry or claim success.
                    Log.e(TAG, "PendingIntent dispatch outcome ambiguous (${dispatchError.javaClass.simpleName})")
                    currentAttempt = currentAttempt.transitionTo(ReplyExecutionStatus.UNKNOWN, clock())
                    notifyTransition(transitionListener, currentAttempt)
                    ReplyExecutionResult(
                        executionId = currentAttempt.attemptId,
                        status = ReplyExecutionStatus.UNKNOWN,
                        dispatchAttempted = true,
                        dispatchedNormally = false,
                        failureCategory = FAILURE_AMBIGUOUS_SEND_EXCEPTION,
                        diagnosticMessage = "Ambiguous dispatch outcome (${dispatchError.javaClass.simpleName})",
                        timestampMs = currentAttempt.updatedAtMs
                    )
                }
            }
        } finally {
            inFlightKeys.remove(lockKey, ownerToken)
        }
    }

    fun shutdown() {
        synchronized(shutdownLock) {
            shutDown.set(true)
        }
    }

    fun isShutDown(): Boolean = synchronized(shutdownLock) { shutDown.get() }

    private fun cancelForShutdown(
        attempt: ReplyAttempt,
        transitionListener: StatusTransitionListener?
    ): ReplyExecutionResult {
        val cancelled = attempt.transitionTo(ReplyExecutionStatus.CANCELLED, clock())
        notifyTransition(transitionListener, cancelled)
        return ReplyExecutionResult(
            executionId = cancelled.attemptId,
            status = ReplyExecutionStatus.CANCELLED,
            dispatchAttempted = false,
            dispatchedNormally = false,
            failureCategory = FAILURE_SERVICE_SHUTDOWN,
            diagnosticMessage = "Execution cancelled before dispatch: manager shut down",
            timestampMs = cancelled.updatedAtMs
        )
    }

    internal fun activeInFlightCount(): Int = inFlightKeys.size

    private fun resolveCoordinationKey(
        attempt: ReplyAttempt,
        notificationWear: NotificationWear
    ): String? {
        val notifKey = attempt.notificationKey?.trim()
        if (!notifKey.isNullOrEmpty()) {
            return "notif:$notifKey"
        }
        val eventKey = attempt.eventKey?.trim()
        if (!eventKey.isNullOrEmpty()) {
            return "event:$eventKey"
        }
        val wearId = notificationWear.id.trim()
        if (wearId.isNotEmpty()) {
            return "wear:$wearId"
        }
        return null
    }

    private fun notifyTransition(
        listener: StatusTransitionListener?,
        attempt: ReplyAttempt
    ) {
        if (listener == null) return
        try {
            listener.onTransition(attempt)
        } catch (e: Exception) {
            Log.w(TAG, "Transition listener failed (${e.javaClass.simpleName}) at status=${attempt.status}")
        }
    }

    companion object {
        private const val TAG = "ReplyExecutionManager"
        const val FAILURE_SERVICE_SHUTDOWN = "SERVICE_SHUTDOWN"
        const val FAILURE_INVALID_REPLY_TEXT = "INVALID_REPLY_TEXT"
        const val FAILURE_MISSING_WEAR_DATA = "MISSING_NOTIFICATION_WEAR"
        const val FAILURE_MISSING_REMOTE_INPUT = "MISSING_REMOTE_INPUT"
        const val FAILURE_MISSING_PENDING_INTENT = "MISSING_PENDING_INTENT"
        const val FAILURE_CONCURRENT_IN_FLIGHT = "CONCURRENT_IN_FLIGHT"
        const val FAILURE_PENDING_INTENT_CANCELED = "PENDING_INTENT_CANCELED"
        const val FAILURE_SECURITY_EXCEPTION = "SECURITY_EXCEPTION"
        const val FAILURE_AMBIGUOUS_SECURITY_EXCEPTION = "AMBIGUOUS_SECURITY_EXCEPTION"
        const val FAILURE_AMBIGUOUS_SEND_EXCEPTION = "AMBIGUOUS_SEND_EXCEPTION"
        const val FAILURE_DUPLICATE_EVENT = "DUPLICATE_EVENT"
        const val FAILURE_UNKNOWN_OUTCOME_LOCKED = "UNKNOWN_OUTCOME_LOCKED"
        const val FAILURE_GENERATED_INTERRUPTED = "GENERATED_INTERRUPTED"
        const val FAILURE_DISPATCHING_INTERRUPTED = "DISPATCHING_INTERRUPTED"
        const val FAILURE_CLAIM_PERSISTENCE_ERROR = "CLAIM_PERSISTENCE_ERROR"
        const val FAILURE_INVALID_ATTEMPT_STATE = "INVALID_ATTEMPT_STATE"
    }
}
