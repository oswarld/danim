package app.danim.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val SCHEMA_VERSION = 1
const val MAX_EVENTS_PER_REQUEST = 200

data class TelemetryAck(val acceptedEventIds: Set<String>, val receivedAt: String?)

/** JSON for `POST /telemetry` and its response (docs/api-contract.md). */
object TelemetryWire {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(deviceId: String, events: List<Observation>): String {
        require(events.size in 1..MAX_EVENTS_PER_REQUEST) { "1..$MAX_EVENTS_PER_REQUEST events per request" }
        val body = JsonObject(
            mapOf(
                "schemaVersion" to JsonPrimitive(SCHEMA_VERSION),
                "deviceId" to JsonPrimitive(deviceId),
                "events" to JsonArray(events.map(::event)),
            ),
        )
        return json.encodeToString(JsonObject.serializer(), body)
    }

    fun event(o: Observation): JsonObject = JsonObject(
        mapOf(
            "eventId" to JsonPrimitive(o.eventId),
            "observedAt" to JsonPrimitive(Iso.format(o.observedAtMs)),
            "signal" to JsonPrimitive(o.signal.key),
            "status" to JsonPrimitive(o.availability.wire),
            "source" to JsonPrimitive(o.source.wire),
            "value" to when (val v = o.value) {
                null -> JsonNull
                is Value.Num -> JsonPrimitive(v.v)
                is Value.Text -> JsonPrimitive(v.v)
                is Value.Bool -> JsonPrimitive(v.v)
            },
        ),
    )

    /** Returns null when the body is not a valid acknowledgement; callers must treat that as no ack. */
    fun parseAck(body: String): TelemetryAck? = runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        val ids = obj.getValue("acceptedEventIds").jsonArray.map { it.jsonPrimitive.content }.toSet()
        TelemetryAck(ids, obj["receivedAt"]?.jsonPrimitive?.contentOrNull)
    }.getOrNull()

    /** Reads `{ "error": { "code", "message" } }`; the message is for diagnostics only. */
    fun parseErrorCode(body: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject.getValue("error").jsonObject.getValue("code")
            .jsonPrimitive.contentOrNull
    }.getOrNull()

    fun parseObject(body: String): JsonObject? = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()

    fun encodeObject(fields: Map<String, String>): String =
        json.encodeToString(JsonObject.serializer(), JsonObject(fields.mapValues { JsonPrimitive(it.value) }))
}
