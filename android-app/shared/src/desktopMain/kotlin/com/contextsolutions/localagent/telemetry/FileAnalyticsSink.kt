package com.contextsolutions.localagent.telemetry

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Desktop [AnalyticsSink] (docs/DESKTOP_PORT_PLAN.md, Phase 7) — the file/OTel
 * counter sink. Firebase Analytics is Android-only (#23); on desktop, aggregate
 * telemetry events are appended as JSON lines to a file in the app-data dir
 * (`telemetry/events.jsonl`). One line per event: `{name, params, ts}` where
 * `ts` is stamped by the caller-provided clock.
 *
 * **Counter-only contract (#27, PRD §4.4):** the events carry only the fixed
 * counter/latency names + numeric values produced by [TelemetryPayloadBuilder]
 * — never user text. This sink is the egress chokepoint; an OTel/HTTP exporter
 * can later replace the file writer behind the same [AnalyticsSink] interface.
 *
 * Errors are absorbed silently per the interface contract — failed telemetry
 * never surfaces to the user. Writes are synchronized + append-only so the file
 * survives concurrent flushes; a coroutine [TelemetryUploader] drives the cadence.
 */
class FileAnalyticsSink(
    private val file: File,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val logger: (String) -> Unit = {},
) : AnalyticsSink {

    private val json = Json { encodeDefaults = true }
    private val lock = Any()

    override fun send(event: AnalyticsSink.AnalyticsEvent) {
        try {
            val obj: JsonObject = buildJsonObject {
                put("name", event.name)
                put("ts", JsonPrimitive(nowEpochMs()))
                put(
                    "params",
                    buildJsonObject {
                        for ((k, v) in event.params) put(k, JsonPrimitive(v))
                    },
                )
            }
            val line = json.encodeToString(JsonObject.serializer(), obj) + "\n"
            synchronized(lock) {
                file.parentFile?.mkdirs()
                file.appendText(line)
            }
        } catch (t: Throwable) {
            // Telemetry must never surface to the user (interface contract).
            logger("drop event ${event.name}: ${t.message}")
        }
    }
}
