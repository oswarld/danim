package app.danim.automotive.ui

import app.danim.automotive.SignalInfo
import app.danim.automotive.nav.NaviOutcome
import app.danim.automotive.voice.EarState
import app.danim.automotive.voice.SpeechState
import app.danim.core.Availability
import app.danim.core.Signal
import app.danim.core.Value
import app.danim.core.copilot.CarNow
import app.danim.core.copilot.CopilotContext
import app.danim.core.copilot.DayPlan
import app.danim.core.copilot.Intent
import app.danim.core.copilot.NextService
import app.danim.core.copilot.Origin
import app.danim.core.copilot.Say
import app.danim.core.copilot.Tariff
import app.danim.core.copilot.TripRecap
import app.danim.core.copilot.Week
import app.danim.core.showcase.CompetitionReport
import kotlin.math.roundToLong

enum class CopilotTab { BRIEFING, ASK, DRIVE, CHARGE, AUTOMATE }

data class Turn(val question: String, val answer: Say, val intent: Intent)

/**
 * Presentation state of the copilot (0.5.0). It lives only in the ViewModel: the open tab, the questions
 * asked in this session, which concept automations are switched on, and the last speech / navigation
 * outcomes. Nothing is persisted, uploaded or sent to the car.
 */
data class CopilotUi(
    val context: CopilotContext,
    val tab: CopilotTab = CopilotTab.BRIEFING,
    val turns: List<Turn> = emptyList(),
    val enabled: Set<String> = emptySet(),
    val ran: Map<String, Say> = emptyMap(),
    val chargers: NaviOutcome? = null,
    val destination: NaviOutcome? = null,
    val speech: SpeechState = SpeechState.IDLE,
    val speechError: String? = null,
    val ear: EarState = EarState.IDLE,
    val earError: String? = null,
    val heard: String = "",
) {
    fun asked(turn: Turn): CopilotUi = copy(turns = (turns + turn).takeLast(MAX_TURNS))
    fun toggled(ruleId: String): CopilotUi = copy(enabled = if (ruleId in enabled) enabled - ruleId else enabled + ruleId)

    companion object { const val MAX_TURNS = 3 }
}

/**
 * Builds the copilot's context. The car state is this car's live collector reading when one exists
 * ([Origin.LIVE]) and the bundled fixed public record otherwise ([Origin.FIXED]). The week and the next
 * service are always the fixed records; the day plan, tariff and battery size are examples
 * ([Origin.EXAMPLE]). Unit-tested in `CopilotModelTest`.
 */
internal object CopilotModel {
    val plan = DayPlan(Say("판교 미팅", "Pangyo meeting"), "09:30", 62.0)
    val tariff = Tariff(offPeakFromHour = 23, offPeakToHour = 9, offPeakKrwPerKwh = 120, standardKrwPerKwh = 250, batteryKwh = 77.4)

    /** The low-battery prompt's example situation, used when this car is above the threshold. */
    val lowBatteryExample = CarNow(18.0, 72.0, null, false, Origin.EXAMPLE)

    val questions: List<Say> = listOf(
        Say("배터리 얼마나 남았어?", "How much battery is left?"),
        Say("오늘 일정 충전 없이 다녀올 수 있어?", "Can I make today's plan without charging?"),
        Say("이번 주 얼마나 탔어?", "How far did I drive this week?"),
        Say("충전비 얼마 나왔어?", "How much did charging cost?"),
        Say("정비 뭐 챙겨야 해?", "What maintenance is coming up?"),
        Say("언제 충전하는 게 싸?", "When is charging cheapest?"),
        Say("주변 충전소 보여줘", "Show nearby chargers"),
    )

    fun week(): Week = CompetitionReport.summary().let { Week(it.tripCount, it.tripDistanceKm, it.chargingSessionCount, it.chargingCostKrw) }

    fun service(): NextService? = ShowcaseModel.nextMaintenance(ShowcaseModel.maintenanceRows())
        ?.let { NextService(Say(it.name.ko, it.name.en), it.daysRemaining, it.kmRemaining) }

    fun fixedNow(): CarNow = CompetitionReport.report.status.let { CarNow(it.batterySocPercent, it.rangeKm, it.odometerKm, true, Origin.FIXED) }

    private fun number(info: SignalInfo?): Double? =
        if (info != null && info.availability == Availability.AVAILABLE) (info.value as? Value.Num)?.v else null

    private fun motion(info: SignalInfo?): String? =
        if (info != null && info.availability == Availability.AVAILABLE) (info.value as? Value.Text)?.v else null

    /** This car's readings, or null when the collector has neither a battery level nor a range to show. */
    fun liveNow(signals: Map<Signal, SignalInfo>?): CarNow? {
        if (signals == null) return null
        val soc = number(signals[Signal.BATTERY_SOC_PERCENT])
        val range = number(signals[Signal.RANGE_KM])
        if (soc == null && range == null) return null
        val parked = when (motion(signals[Signal.VEHICLE_MOTION])) { "parked" -> true; "moving" -> false; else -> null }
        return CarNow(soc, range, number(signals[Signal.ODOMETER_KM]), parked, Origin.LIVE)
    }

    /** The last trip of the fixed records; its charge use was not recorded, so it stays unknown. */
    fun fixedTrip(): TripRecap? = CompetitionReport.report.trips.lastOrNull()?.let {
        val minutes = if (it.averageSpeedKph > 0) (it.distanceKm / it.averageSpeedKph * 60).roundToLong() else 0L
        TripRecap(it.distanceKm, minutes, null, Origin.FIXED)
    }

    fun context(signals: Map<Signal, SignalInfo>?, liveTrip: TripRecap?, hour: Int): CopilotContext =
        CopilotContext(liveNow(signals) ?: fixedNow(), week(), service(), plan, tariff, liveTrip ?: fixedTrip(), hour)

    /** Motion word, odometer and charge for the on-device trip tracker. */
    fun trackerInput(signals: Map<Signal, SignalInfo>): Triple<String?, Double?, Double?> =
        Triple(motion(signals[Signal.VEHICLE_MOTION]), number(signals[Signal.ODOMETER_KM]), number(signals[Signal.BATTERY_SOC_PERCENT]))
}
