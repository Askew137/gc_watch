package com.askew137.gcwatch.mobile.update

import android.content.Context
import com.askew137.gcwatch.core.communication.MessagePaths
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Asks the watch which version of the app it runs, so a mismatch with the phone can be shown
 * (a changed message format would otherwise just make things silently stop working).
 */
object WatchVersion {

    sealed interface State {
        /** Not asked yet, or no watch is connected. */
        data object Unknown : State
        /** A watch is connected but did not answer (e.g. an older app without this feature). */
        data object NoAnswer : State
        data class Known(val code: Long, val name: String, val deviceName: String = "") : State
    }

    private val _state = MutableStateFlow<State>(State.Unknown)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    /**
     * Name the watch reports itself (Wear OS 5 on some watches hands the phone a mangled node name),
     * kept across refreshes so the card does not flicker back to the node name.
     */
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    @Volatile
    private var pending: CompletableDeferred<State.Known>? = null

    /** Called by MobileMessageService with the watch's `"<code>|<name>[|<device name>]"` answer. */
    fun onResult(payload: ByteArray) {
        val parts = String(payload, Charsets.UTF_8).split('|', limit = 3)
        val code = parts.getOrNull(0)?.toLongOrNull() ?: return
        val known = State.Known(code, parts.getOrElse(1) { "?" }, parts.getOrElse(2) { "" })
        known.deviceName.takeIf { it.isNotBlank() }?.let { _deviceName.value = it }
        // Also applies to a late answer after refresh() already gave up waiting.
        _state.value = known
        pending?.complete(known)
    }

    suspend fun refresh(context: Context) {
        try {
            val node = Wearable.getNodeClient(context).connectedNodes.await().firstOrNull()
            if (node == null) {
                _state.value = State.Unknown
                return
            }
            _state.value = State.Unknown
            val answer = CompletableDeferred<State.Known>().also { pending = it }
            Wearable.getMessageClient(context)
                .sendMessage(node.id, MessagePaths.VERSION_REQUEST, ByteArray(0)).await()
            // Generous: a cold start of the watch app over Bluetooth can take a few seconds.
            if (withTimeoutOrNull(15_000) { answer.await() } == null && _state.value !is State.Known) {
                _state.value = State.NoAnswer
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not ask the watch for its version")
            _state.value = State.Unknown
        } finally {
            pending = null
        }
    }
}
