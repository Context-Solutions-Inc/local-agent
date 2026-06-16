package com.contextsolutions.localagent.job

import com.contextsolutions.localagent.link.transport.LinkMethod
import com.contextsolutions.localagent.link.transport.LinkRequest
import com.contextsolutions.localagent.link.transport.LinkStreamEvent
import com.contextsolutions.localagent.link.transport.LinkTransportProvider
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Mobile binding of [InlineJobRunner] (PR #88): the phone can't spawn
 * subprocesses, so it asks the paired desktop to run the job over the relay and
 * return its output. Uses the `RUN_JOB_INLINE` **streaming** method (not unary)
 * so that (a) a long-running job tolerates the un-timed stream and (b) cancelling
 * the collector — when the user hits Cancel and the agent turn coroutine is
 * cancelled — sends a relay CANCEL frame, which cancels the desktop handler and
 * kills the subprocess (invariant #59).
 *
 * The desktop emits a single data frame `{"ok":bool,"output":…}` then ends;
 * an `End(404)` means the desktop doesn't know the job id, and a transport-level
 * `Error`/missing transport surfaces as a [InlineJobResult.Failure].
 */
class RelayInlineJobRunner(
    private val transports: LinkTransportProvider,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : InlineJobRunner {

    override suspend fun run(id: String, keywords: String): InlineJobResult {
        val transport = transports.current()
            ?: return InlineJobResult.Failure("Not connected to the desktop agent.")
        val request = LinkRequest(
            method = LinkMethod.RUN_JOB_INLINE,
            query = mapOf("id" to id, "keywords" to keywords),
        )
        var dataBody: String? = null
        var endStatus: Int? = null
        var errorMessage: String? = null
        transport.serverStream(request).collect { event ->
            when (event) {
                is LinkStreamEvent.Data -> dataBody = event.body
                is LinkStreamEvent.End -> endStatus = event.status
                is LinkStreamEvent.Error ->
                    errorMessage = event.message.ifBlank { "The desktop agent is unreachable." }
            }
        }

        errorMessage?.let { return InlineJobResult.Failure(it) }
        if (endStatus == 404) return InlineJobResult.Failure("The desktop no longer has that job.")
        val body = dataBody ?: return InlineJobResult.Failure("The job produced no response.")
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return InlineJobResult.Failure("Malformed response from the desktop agent.")
        val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        val output = obj["output"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return if (ok) {
            InlineJobResult.Output(output)
        } else {
            InlineJobResult.Failure(output.ifBlank { "The job failed." })
        }
    }
}
