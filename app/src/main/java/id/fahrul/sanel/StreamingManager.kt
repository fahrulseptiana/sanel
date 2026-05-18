package id.fahrul.sanel

import okhttp3.Call
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages active HTTP streaming calls to enable graceful cancellation
 * when app goes to background or on configuration changes.
 */
object StreamingManager {
    private val activeCall: AtomicReference<Call?> = AtomicReference(null)
    private var onStreamCancelled: (() -> Unit)? = null

    /**
     * Track the currently active streaming call
     */
    fun setActiveCall(call: Call?) {
        activeCall.set(call)
    }

    /**
     * Get the currently active streaming call
     */
    fun getActiveCall(): Call? = activeCall.get()

    /**
     * Cancel the active stream with a reason (e.g., "Background", "Config changed")
     */
    fun cancelActiveStream(reason: String = "Background") {
        activeCall.getAndSet(null)?.cancel()
        onStreamCancelled?.invoke()
    }

    /**
     * Register callback to be invoked when stream is cancelled
     */
    fun setOnStreamCancelled(callback: (() -> Unit)?) {
        onStreamCancelled = callback
    }

    /**
     * Check if a stream is currently active
     */
    fun isStreamActive(): Boolean = activeCall.get() != null
}
