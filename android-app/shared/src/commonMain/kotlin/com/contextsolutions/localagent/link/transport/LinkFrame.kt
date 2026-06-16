package com.contextsolutions.localagent.link.transport

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The request/response + streaming framing tunneled over the Secure Gateway
 * relay's opaque `send(bytes)`/`onMessage(bytes)` byte-pipe (relay follow-up to
 * PR #57). One JSON envelope multiplexes many concurrent calls on the single pipe
 * via [id] correlation.
 *
 * The existing wire payloads ride **unchanged** in [body] (OpenAI chat JSON, the
 * post-`data:` SSE chunk strings, `SyncBundle` JSON), so the relay path reuses the
 * same parsing as LAN — only the transport swaps.
 *
 * Lifecycle of an [id]:
 *  - client → server: one [FrameKind.REQUEST] (with [method] + optional [body]/[query]).
 *  - unary method: server → client one [FrameKind.RESPONSE] ([status] + [body]).
 *  - streaming method: server → client zero+ [FrameKind.STREAM_DATA] then exactly
 *    one [FrameKind.STREAM_END] or [FrameKind.STREAM_ERROR].
 *  - client → server [FrameKind.CANCEL] when the collector leaves early.
 */
@Serializable
data class LinkFrame(
    val v: Int = 1,
    val id: Long,
    val kind: FrameKind,
    val method: LinkMethod? = null,
    val query: Map<String, String>? = null,
    val status: Int? = null,
    val body: String? = null,
)

enum class FrameKind { REQUEST, RESPONSE, STREAM_DATA, STREAM_END, STREAM_ERROR, CANCEL }

/** Encodes/decodes [LinkFrame]s to/from the bytes carried by the relay pipe. */
object LinkFrameCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun encode(frame: LinkFrame): ByteArray =
        json.encodeToString(LinkFrame.serializer(), frame).encodeToByteArray()

    fun decode(bytes: ByteArray): LinkFrame =
        json.decodeFromString(LinkFrame.serializer(), bytes.decodeToString())
}
