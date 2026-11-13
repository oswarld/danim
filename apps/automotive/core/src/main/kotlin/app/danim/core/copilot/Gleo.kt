package app.danim.core.copilot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The Gleo Interface bridge (Pleos app-integration guide): the car's own voice assistant calls one of
 * DANIM's three functions declared in `assets/schema.json`, and DANIM answers with a callback object
 * `{functionName, status, message, mode, data}`. Pure and unit-tested ([GleoTest]); the receiver in the
 * app only reads the context, calls [handle] and broadcasts the result.
 *
 * Messages are short English sentences, as the guide requires (Gleo words the reply in the driver's
 * language). A reply that comes from the bundled fixed record says so, and a missing reading is reported
 * as missing in both the sentence and the data.
 */
object Gleo {
    const val BRIEFING = "GET_VEHICLE_BRIEFING"
    const val QUESTION = "ANSWER_RECORD_QUESTION"
    const val CHARGERS = "SHOW_NEARBY_CHARGERS"
    val functions = listOf(BRIEFING, QUESTION, CHARGERS)

    /** `topic` values of [QUESTION], as declared in the schema. */
    val topics: Map<String, Intent> = mapOf(
        "battery" to Intent.BATTERY, "day_plan" to Intent.DAY_PLAN, "week_driving" to Intent.WEEK,
        "charging_cost" to Intent.COST, "maintenance" to Intent.SERVICE, "charge_timing" to Intent.CHARGE_WHEN,
        "last_trip" to Intent.LAST_TRIP,
    )

    data class Reply(val functionName: String, val status: String, val message: String, val data: String, val showChargers: Boolean = false) {
        /** The callback object Gleo expects in the `response` extra. */
        fun json(): String = buildJsonObject {
            put("functionName", functionName); put("status", status); put("message", message); put("mode", "voice"); put("data", data)
        }.toString()
    }

    private fun data(c: CopilotContext): String = buildJsonArray {
        add(buildJsonObject {
            put("source", if (c.now.origin == Origin.LIVE) "live_vehicle_reading" else "fixed_demo_record")
            put("batteryPercent", c.now.socPercent?.let(::JsonPrimitive) ?: JsonPrimitive("unknown"))
            put("rangeKm", c.now.rangeKm?.let(::JsonPrimitive) ?: JsonPrimitive("unknown"))
        })
    }.toString()

    private fun sourced(c: CopilotContext, text: Say): String =
        if (c.now.origin == Origin.LIVE) text.en else "From DANIM's fixed demo record, not this car: ${text.en}"

    /** Strips the package prefix Gleo may add to the action (`app.danim.automotive.GET_VEHICLE_BRIEFING`). */
    fun functionOf(action: String?): String? = action?.substringAfterLast('.')?.takeIf { it in functions }

    fun handle(function: String, actionParam: String?, c: CopilotContext): Reply = when (function) {
        BRIEFING -> Reply(function, "success", sourced(c, Copilot.briefing(c)), data(c))
        QUESTION -> {
            val topic = try {
                (Json.parseToJsonElement(actionParam ?: "{}") as? JsonObject)?.get("topic")?.jsonPrimitive?.content
            } catch (_: Exception) { null }
            val intent = topics[topic]
            if (intent == null) Reply(function, "asking", "Which record do you want: ${topics.keys.joinToString(", ")}?", "[]")
            else Reply(function, "success", sourced(c, Copilot.answer(intent, c).text), data(c))
        }
        CHARGERS -> Reply(function, "success", "Nearby chargers are shown in navigation.", "[]", showChargers = true)
        else -> Reply(function, "fail", "DANIM does not provide this function.", "[]")
    }
}
