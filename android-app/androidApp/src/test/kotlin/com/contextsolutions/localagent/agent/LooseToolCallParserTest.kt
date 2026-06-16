package com.contextsolutions.localagent.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Drives `AgentLoop.parseLooseToolCallBody` through the public regexes by
 * replaying the exact failure body from the field report: Gemma emitted a
 * structured tool call but used `<|"|>` tokens around string values and
 * the engine's parser rejected the whole thing. The loose parser unescapes
 * those tokens, quotes bareword keys, and produces valid JSON args.
 */
class LooseToolCallParserTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Mirror of [AgentLoop.parseLooseToolCallBody] using the same exposed
     * regex / constants. Kept here so the test doesn't need reflection.
     */
    private fun parse(body: String): Pair<String, String>? {
        val match = AgentLoop.LOOSE_CALL_REGEX.matchEntire(body) ?: return null
        val name = match.groupValues[1]
        val rawArgs = match.groupValues[2]
        val unescaped = rawArgs.replace(AgentLoop.GEMMA_QUOTE_TOKEN, "\"")
        val withQuotedKeys = AgentLoop.BAREWORD_KEY_REGEX.replace(unescaped) { m ->
            "\"${m.groupValues[1]}\":"
        }
        val argsJson = "{$withQuotedKeys}"
        return try {
            Json.parseToJsonElement(argsJson)
            name to argsJson
        } catch (_: Throwable) {
            null
        }
    }

    @Test
    fun `parses the exact field-report body`() {
        // Body extracted from the production logcat after stripping the
        // outer <|tool_call> / <tool_call|> markers.
        val body = """call:set_alarm{days:[<|"|>mon<|"|>,<|"|>tue<|"|>,<|"|>wed<|"|>,<|"|>thu<|"|>,<|"|>fri<|"|>,<|"|>sun<|"|>],hour:11,minute:40,label:<|"|>weekday<|"|>}"""
        val result = parse(body)
        assertNotNull(result)
        val (name, argsJson) = result!!
        assertEquals("set_alarm", name)
        val obj = json.parseToJsonElement(argsJson).jsonObject
        val days = obj["days"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(listOf("mon", "tue", "wed", "thu", "fri", "sun"), days)
        assertEquals("11", obj["hour"]?.jsonPrimitive?.content)
        assertEquals("40", obj["minute"]?.jsonPrimitive?.content)
        assertEquals("weekday", obj["label"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parses scalar-only body the legacy parser also accepts`() {
        val body = """call:set_timer{minutes:5,label:"tea"}"""
        val result = parse(body)
        assertNotNull(result)
        val (name, argsJson) = result!!
        assertEquals("set_timer", name)
        val obj = json.parseToJsonElement(argsJson).jsonObject
        assertEquals("5", obj["minutes"]?.jsonPrimitive?.content)
        assertEquals("tea", obj["label"]?.jsonPrimitive?.content)
    }

    @Test
    fun `returns null on garbage`() {
        // No `call:NAME{...}` envelope at all.
        assertEquals(null, parse("this is just text"))
    }

    /**
     * Mirror of [AgentLoop.parseHeuristicToolCallBody] using the same
     * exposed regex set. Kept here so we can verify the per-field
     * extractor without instantiating AgentLoop.
     */
    private fun parseHeuristic(body: String): Pair<String, String>? {
        val match = AgentLoop.LOOSE_CALL_REGEX.matchEntire(body) ?: return null
        val name = match.groupValues[1]
        val rawArgs = match.groupValues[2]
        val args = kotlinx.serialization.json.buildJsonObject {
            AgentLoop.HOUR_REGEX.find(rawArgs)?.let {
                put("hour", kotlinx.serialization.json.JsonPrimitive(it.groupValues[1].toInt()))
            }
            AgentLoop.MINUTE_REGEX.find(rawArgs)?.let {
                put("minute", kotlinx.serialization.json.JsonPrimitive(it.groupValues[1].toInt()))
            }
            AgentLoop.DAYS_BLOCK_REGEX.find(rawArgs)?.let { m ->
                val days = AgentLoop.DAY_TOKEN_REGEX.findAll(m.groupValues[1])
                    .map { it.groupValues[1].lowercase() }
                    .filter { it in AgentLoop.VALID_DAY_TOKENS }
                    .toSet()
                if (days.isNotEmpty()) {
                    put("days", kotlinx.serialization.json.buildJsonArray {
                        for (d in days) add(kotlinx.serialization.json.JsonPrimitive(d))
                    })
                }
            }
            AgentLoop.LABEL_WRAPPED_REGEX.find(rawArgs)?.let {
                put("label", kotlinx.serialization.json.JsonPrimitive(it.groupValues[1].trim()))
            }
        }
        if (args.isEmpty()) return null
        return name to Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), args)
    }

    @Test
    fun `heuristic parser recovers days from Gemma noise body`() {
        // Exact pattern from the field report: stray `<|"|>,<|"|>` between
        // `tue` and `wed`, plus a double `<|"|><|"|>` after `wed`. After
        // unescape that's "tue","","wed"" — invalid JSON — but the
        // heuristic only looks for `<|"|>(\w+)<|"|>` patterns, so the
        // noise is skipped.
        val body = """call:set_alarm{days:[<|"|>mon<|"|>,<|"|>tue<|"|>,<|"|>,<|"|>wed<|"|><|"|>,<|"|>thu<|"|>,<|"|>fri<|"|>],hour:11,minute:45}"""
        val result = parseHeuristic(body)
        assertNotNull(result)
        val (name, argsJson) = result!!
        assertEquals("set_alarm", name)
        val obj = json.parseToJsonElement(argsJson).jsonObject
        assertEquals("11", obj["hour"]?.jsonPrimitive?.content)
        assertEquals("45", obj["minute"]?.jsonPrimitive?.content)
        val days = obj["days"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
        assertEquals(setOf("mon", "tue", "wed", "thu", "fri"), days)
    }
}
