package app.danim.core.copilot

import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * DANIM Copilot (0.5.0): what the car says and shows at the moments that only exist in a car — before
 * leaving, while driving, on arrival and while charging — composed from the car's own readings and the
 * owner's records. Pure and unit-tested ([CopilotTest]); nothing here reads an SDK, speaks or navigates.
 *
 * Every input carries its [Origin]. LIVE is this car's collector reading, FIXED is the bundled public
 * record set, EXAMPLE is an illustrative concept input (a day plan, a tariff, a battery size). A missing
 * value produces a sentence that says it is missing; it is never replaced by zero or a guess. Derived
 * figures (arrival charge, cost) are estimates from the stated inputs and are worded as such.
 */
data class Say(val ko: String, val en: String) {
    fun text(korean: Boolean): String = if (korean) ko else en
    operator fun plus(o: Say): Say = Say("$ko ${o.ko}", "$en ${o.en}")
}

enum class Origin { LIVE, FIXED, EXAMPLE }

data class CarNow(val socPercent: Double?, val rangeKm: Double?, val odometerKm: Double?, val parked: Boolean?, val origin: Origin)

/** Example day plan (concept input; DANIM does not read a calendar or a location in this build). */
data class DayPlan(val place: Say, val departAt: String, val roundTripKm: Double)

/** Example time-of-use tariff and battery size (concept inputs, shown as examples wherever they are used). */
data class Tariff(val offPeakFromHour: Int, val offPeakToHour: Int, val offPeakKrwPerKwh: Int, val standardKrwPerKwh: Int, val batteryKwh: Double) {
    fun isOffPeak(hour: Int): Boolean =
        if (offPeakFromHour <= offPeakToHour) hour in offPeakFromHour until offPeakToHour else hour >= offPeakFromHour || hour < offPeakToHour
}

data class Week(val trips: Int, val distanceKm: Double, val sessions: Int, val costKrw: Int)
data class NextService(val name: Say, val daysRemaining: Long?, val kmRemaining: Double?)
data class TripRecap(val distanceKm: Double?, val minutes: Long, val socUsed: Double?, val origin: Origin)

data class CopilotContext(
    val now: CarNow, val week: Week, val service: NextService?, val plan: DayPlan, val tariff: Tariff,
    val lastTrip: TripRecap?, val hour: Int,
)

data class ChargeAdvice(
    val text: Say, val kwhToTarget: Double?, val costNowKrw: Int?, val costOffPeakKrw: Int?, val savingKrw: Int?, val offPeakNow: Boolean,
)

/** CHARGERS is the one intent that acts: the caller hands off to the navigation app's nearby-chargers screen. */
enum class Intent { BATTERY, DAY_PLAN, WEEK, COST, SERVICE, CHARGE_WHEN, LAST_TRIP, CHARGERS, UNKNOWN }
data class Answer(val intent: Intent, val text: Say)

object Copilot {
    const val LOW_BATTERY_PERCENT = 20.0
    const val CHARGE_TARGET_PERCENT = 80.0
    /** A round trip is called comfortable only when this much charge is left on return. */
    const val RETURN_MARGIN_PERCENT = 15.0

    /** Grouped, at most one decimal: 12,089.5 · 74 · 296. */
    fun num(v: Double): String = if (v == Math.rint(v)) "%,.0f".format(Locale.ROOT, v) else "%,.1f".format(Locale.ROOT, v)
    private fun krw(v: Int): String = "%,d".format(Locale.ROOT, v)
    private fun round100(v: Double): Int = ((v / 100.0).roundToLong() * 100).toInt()

    /** Charge left after the plan's round trip, from the car's own reported range. Null when either reading is missing. */
    fun chargeAfterPlan(now: CarNow, plan: DayPlan): Double? {
        val soc = now.socPercent ?: return null
        val range = now.rangeKm ?: return null
        if (range <= 0.0 || soc <= 0.0) return null
        return soc - plan.roundTripKm / range * soc
    }

    private fun status(now: CarNow): Say {
        val soc = now.socPercent
        val range = now.rangeKm
        return when {
            soc != null && range != null -> Say("배터리는 ${num(soc)}%, 주행 가능 거리는 ${num(range)} km입니다.", "The battery is at ${num(soc)}% with ${num(range)} km of range.")
            soc != null -> Say("배터리는 ${num(soc)}%입니다. 주행 가능 거리는 아직 받지 못했습니다.", "The battery is at ${num(soc)}%. The range has not been reported yet.")
            range != null -> Say("주행 가능 거리는 ${num(range)} km입니다. 배터리 잔량은 아직 받지 못했습니다.", "The range is ${num(range)} km. The battery level has not been reported yet.")
            else -> Say("배터리와 주행 가능 거리를 아직 받지 못했습니다.", "The battery level and range have not been reported yet.")
        }
    }

    private fun planLine(now: CarNow, plan: DayPlan): Say {
        val left = chargeAfterPlan(now, plan)
        val km = num(plan.roundTripKm)
        return when {
            left == null -> Say("${plan.place.ko} 왕복 $km km를 다녀올 수 있는지는 배터리 값을 받은 뒤 알려드리겠습니다.", "I can tell whether the $km km round trip to ${plan.place.en} fits once the battery is reported.")
            left >= RETURN_MARGIN_PERCENT -> Say(
                "${plan.departAt} ${plan.place.ko} 왕복 $km km는 충전 없이 다녀올 수 있고, 돌아오면 약 ${left.roundToInt()}%가 남습니다.",
                "The ${plan.departAt} round trip to ${plan.place.en}, $km km, fits without charging and leaves about ${left.roundToInt()}% on return.",
            )
            else -> Say(
                "${plan.departAt} ${plan.place.ko} 왕복 $km km에는 충전이 필요합니다. 출발 전이나 가는 길에 충전하세요.",
                "The ${plan.departAt} round trip to ${plan.place.en}, $km km, needs a charge. Charge before leaving or on the way.",
            )
        }
    }

    private fun serviceLine(s: NextService?): Say? {
        val days = s?.daysRemaining ?: return null
        if (days > 14) return null
        return if (days < 0) Say("${s.name.ko} 시기가 ${-days}일 지났습니다.", "${s.name.en} is ${-days} days overdue.")
        else Say("${s.name.ko}까지 ${days}일 남았습니다.", "${s.name.en} is due in $days days.")
    }

    private fun weekLine(w: Week): Say = Say(
        "지난 한 주는 ${num(w.distanceKm)} km를 달렸고 충전비는 ${krw(w.costKrw)}원이었습니다.",
        "Last week you drove ${num(w.distanceKm)} km and spent ${krw(w.costKrw)} KRW on charging.",
    )

    /** Spoken and shown when the driver gets in: state, today's plan, the next service. */
    fun briefing(c: CopilotContext): Say = listOfNotNull(status(c.now), planLine(c.now, c.plan), serviceLine(c.service)).reduce(Say::plus)

    /** Spoken while driving when the charge is low; null above the threshold or without a reading. */
    fun lowBattery(now: CarNow): Say? {
        val soc = now.socPercent ?: return null
        if (soc > LOW_BATTERY_PERCENT) return null
        val range = now.rangeKm
        return if (range != null) Say(
            "배터리가 ${num(soc)}%, 주행 가능 거리가 ${num(range)} km 남았습니다. 주변 충전소를 내비게이션에 띄울까요?",
            "The battery is at ${num(soc)}% with ${num(range)} km left. Shall I show nearby chargers in navigation?",
        ) else Say("배터리가 ${num(soc)}% 남았습니다. 주변 충전소를 내비게이션에 띄울까요?", "The battery is at ${num(soc)}%. Shall I show nearby chargers in navigation?")
    }

    /** Charge left at a navigation destination [remainingKm] away, from the car's reported range. */
    fun chargeAtDestination(now: CarNow, remainingKm: Double): Double? {
        val soc = now.socPercent ?: return null
        val range = now.rangeKm ?: return null
        if (range <= 0.0 || soc <= 0.0 || remainingKm < 0.0) return null
        return soc - remainingKm / range * soc
    }

    fun destinationLine(now: CarNow, name: String, remainingKm: Double): Say {
        val left = chargeAtDestination(now, remainingKm)
        val km = num((remainingKm * 10).roundToLong() / 10.0)
        return when {
            left == null -> Say("$name 까지 $km km 남았습니다. 도착 배터리는 배터리 값을 받은 뒤 알려드리겠습니다.", "$km km to $name. I can estimate the arrival charge once the battery is reported.")
            left >= RETURN_MARGIN_PERCENT -> Say("$name 까지 $km km 남았고, 도착하면 약 ${left.roundToInt()}%가 남습니다.", "$km km to $name, arriving with about ${left.roundToInt()}%.")
            left > 0 -> Say("$name 까지 $km km 남았고, 도착하면 약 ${left.roundToInt()}%로 여유가 적습니다. 가는 길에 충전을 권합니다.", "$km km to $name, arriving with only about ${left.roundToInt()}%. A charge on the way is advised.")
            else -> Say("$name 까지 $km km는 현재 배터리로 갈 수 없습니다. 충전소를 경유하세요.", "$name, $km km away, is out of range on this charge. Add a charging stop.")
        }
    }

    /** Spoken and shown when a trip ends in Park. */
    fun arrival(t: TripRecap): Say {
        val head = when (val d = t.distanceKm) {
            null -> Say("도착했습니다. 이번 주행은 ${t.minutes}분이었습니다.", "You have arrived. This trip took ${t.minutes} minutes.")
            else -> Say("도착했습니다. 이번 주행은 ${num(d)} km, ${t.minutes}분이었습니다.", "You have arrived. This trip was ${num(d)} km in ${t.minutes} minutes.")
        }
        val used = t.socUsed
        val tail = when {
            used == null -> Say("배터리 사용량은 기록되지 않았습니다.", "Battery use was not recorded.")
            used <= 0.0 -> Say("배터리 잔량은 변하지 않았습니다.", "The battery level did not change.")
            else -> Say("배터리는 ${num(used)}%를 사용했습니다.", "It used ${num(used)}% of the battery.")
        }
        return head + tail
    }

    /** Kilometres per battery percent on a trip; null unless both were observed and positive. */
    fun kmPerPercent(t: TripRecap): Double? {
        val d = t.distanceKm ?: return null
        val used = t.socUsed ?: return null
        return if (d > 0.0 && used > 0.0) d / used else null
    }

    /** What charging to [CHARGE_TARGET_PERCENT] costs now and in the off-peak window, with the example tariff. */
    fun chargeAdvice(c: CopilotContext): ChargeAdvice {
        val soc = c.now.socPercent
        val t = c.tariff
        val offNow = t.isOffPeak(c.hour)
        if (soc == null) return ChargeAdvice(Say("배터리 잔량을 받은 뒤 충전 비용을 계산하겠습니다.", "I will estimate the charging cost once the battery level is reported."), null, null, null, null, offNow)
        if (soc >= CHARGE_TARGET_PERCENT) return ChargeAdvice(
            Say("배터리가 ${num(soc)}%입니다. ${num(CHARGE_TARGET_PERCENT)}% 이상이라 지금은 충전할 필요가 없습니다.", "The battery is at ${num(soc)}%. It is above ${num(CHARGE_TARGET_PERCENT)}%, so there is no need to charge now."),
            0.0, 0, 0, 0, offNow,
        )
        val kwh = (CHARGE_TARGET_PERCENT - soc) / 100.0 * t.batteryKwh
        val std = round100(kwh * t.standardKrwPerKwh)
        val off = round100(kwh * t.offPeakKrwPerKwh)
        val k = num((kwh * 10).roundToLong() / 10.0)
        val text = if (offNow) Say(
            "지금이 경부하 시간입니다. ${num(CHARGE_TARGET_PERCENT)}%까지 약 $k kWh, 약 ${krw(off)}원입니다.",
            "It is off-peak now. Charging to ${num(CHARGE_TARGET_PERCENT)}% takes about $k kWh, about ${krw(off)} KRW.",
        ) else Say(
            "${num(CHARGE_TARGET_PERCENT)}%까지 약 $k kWh입니다. 지금 충전하면 약 ${krw(std)}원, ${t.offPeakFromHour}시 이후 경부하 시간에는 약 ${krw(off)}원으로 ${krw(std - off)}원을 아낄 수 있습니다.",
            "Charging to ${num(CHARGE_TARGET_PERCENT)}% takes about $k kWh: about ${krw(std)} KRW now, or about ${krw(off)} KRW after ${t.offPeakFromHour}:00 off-peak, saving ${krw(std - off)} KRW.",
        )
        return ChargeAdvice(text, kwh, std, off, std - off, offNow)
    }

    private val keywords: List<Pair<Intent, List<String>>> = listOf(
        Intent.CHARGERS to listOf("충전소", "charger", "chargingstation"),
        Intent.CHARGE_WHEN to listOf("언제충전", "충전언제", "싸게", "경부하", "충전시간", "저렴", "whenshouldicharge", "cheapest", "offpeak"),
        Intent.COST to listOf("충전비", "비용", "얼마썼", "요금", "얼마나왔", "cost", "spend", "spent"),
        Intent.DAY_PLAN to listOf("갈수있", "다녀올", "일정", "충전없이", "왕복", "canimake", "schedule", "roundtrip", "enoughfor"),
        Intent.LAST_TRIP to listOf("방금", "마지막주행", "최근주행", "오늘주행", "lasttrip", "latesttrip"),
        Intent.WEEK to listOf("이번주", "지난주", "한주", "얼마나탔", "얼마나달", "주행기록", "몇킬로", "thisweek", "lastweek", "howfar"),
        Intent.SERVICE to listOf("정비", "점검", "교체", "필터", "타이어", "오일", "maintenance", "service", "filter", "tire", "tyre"),
        Intent.BATTERY to listOf("배터리", "주행가능", "얼마나갈", "잔량", "battery", "range", "charge"),
    )

    fun intentOf(question: String): Intent {
        val q = question.lowercase(Locale.ROOT).filterNot { it.isWhitespace() || it in "?!.," }
        if (q.isEmpty()) return Intent.UNKNOWN
        return keywords.firstOrNull { (_, words) -> words.any { it in q } }?.first ?: Intent.UNKNOWN
    }

    /** Answers a spoken or tapped question from the context only; an unrecognised question says what can be asked. */
    fun answer(question: String, c: CopilotContext): Answer = answer(intentOf(question), c)

    fun answer(intent: Intent, c: CopilotContext): Answer = when (intent) {
        Intent.BATTERY -> Answer(intent, status(c.now))
        Intent.DAY_PLAN -> Answer(intent, planLine(c.now, c.plan))
        Intent.WEEK -> Answer(intent, Say("지난 한 주는 ${c.week.trips}번, ${num(c.week.distanceKm)} km를 달렸습니다.", "Last week you made ${c.week.trips} trips and drove ${num(c.week.distanceKm)} km."))
        Intent.COST -> Answer(intent, Say("지난 한 주 충전은 ${c.week.sessions}번, 충전비는 ${krw(c.week.costKrw)}원이었습니다.", "Last week you charged ${c.week.sessions} times for ${krw(c.week.costKrw)} KRW."))
        Intent.SERVICE -> Answer(intent, serviceAnswer(c.service))
        Intent.CHARGE_WHEN -> Answer(intent, chargeAdvice(c).text)
        Intent.LAST_TRIP -> Answer(intent, c.lastTrip?.let(::arrival) ?: Say("아직 기록된 주행이 없습니다.", "No trip has been recorded yet."))
        Intent.CHARGERS -> Answer(intent, Say("내비게이션에 주변 충전소를 띄웁니다.", "Showing nearby chargers in navigation."))
        Intent.UNKNOWN -> Answer(intent, Say(
            "기록에서 답을 찾지 못했습니다. 배터리, 오늘 일정, 이번 주 주행, 충전비, 정비, 충전 시간, 주변 충전소를 물어보세요.",
            "I could not find that in your records. Ask about the battery, today's plan, this week's driving, charging cost, maintenance, when to charge or nearby chargers.",
        ))
    }

    private fun serviceAnswer(s: NextService?): Say {
        if (s == null) return Say("기준이 있는 정비 항목이 없습니다. 정비 기록을 입력하면 다음 시기를 알려드립니다.", "No maintenance item has a baseline. Enter a service record and I will tell you when the next one is due.")
        val days = s.daysRemaining
        val km = s.kmRemaining
        return when {
            days != null && km != null -> Say("다음 정비는 ${s.name.ko}입니다. ${days}일 또는 ${num(km)} km 남았습니다.", "Next is ${s.name.en}, due in $days days or ${num(km)} km.")
            days != null -> Say("다음 정비는 ${s.name.ko}입니다. ${days}일 남았습니다.", "Next is ${s.name.en}, due in $days days.")
            km != null -> Say("다음 정비는 ${s.name.ko}입니다. ${num(km)} km 남았습니다.", "Next is ${s.name.en}, due in ${num(km)} km.")
            else -> Say("다음 정비는 ${s.name.ko}이며 남은 기간은 알 수 없습니다.", "Next is ${s.name.en}; the remaining time is unknown.")
        }
    }

    /** Units spelled out for the speech engine; the screen keeps the symbols. */
    fun spoken(text: String, korean: Boolean): String = if (korean) {
        text.replace("%p", "퍼센트포인트").replace("%", "퍼센트").replace(" kWh", " 킬로와트시").replace(" km", " 킬로미터").replace("DANIM", "다님")
    } else text.replace("%", " percent").replace(" kWh", " kilowatt hours").replace(" km", " kilometres").replace(" KRW", " won")
}

/**
 * Turns the collector's motion readings into a trip recap on the device: the first "moving" starts a trip
 * from the last parked odometer and charge, and the next "parked" ends it. "unknown" changes nothing.
 * A trip with no recorded distance gain is dropped; a charge that rose during the trip is not reported as use.
 */
class TripTracker {
    private data class Mark(val atMs: Long, val odometerKm: Double?, val socPercent: Double?)
    private var parkedAt: Mark? = null
    private var start: Mark? = null

    val inTrip: Boolean get() = start != null

    fun onReading(nowMs: Long, motion: String?, odometerKm: Double?, socPercent: Double?): TripRecap? {
        when (motion) {
            "moving" -> if (start == null) {
                val base = parkedAt
                start = Mark(nowMs, base?.odometerKm ?: odometerKm, base?.socPercent ?: socPercent)
            }
            "parked" -> {
                val s = start
                parkedAt = Mark(nowMs, odometerKm, socPercent)
                if (s != null) {
                    start = null
                    val distance = if (odometerKm != null && s.odometerKm != null) odometerKm - s.odometerKm else null
                    if (distance != null && distance <= 0.0) return null
                    val used = if (socPercent != null && s.socPercent != null) (s.socPercent - socPercent).takeIf { it >= 0.0 } else null
                    val minutes = ((nowMs - s.atMs) / 60_000.0).roundToLong().coerceAtLeast(1)
                    return TripRecap(distance?.let { (it * 10).roundToLong() / 10.0 }, minutes, used, Origin.LIVE)
                }
            }
        }
        return null
    }
}

/** What an automation needs before it can really run. RECORDS runs on observations DANIM already has. */
enum class Needs { RECORDS, VEHICLE_CONTROL, LOCATION }

data class Rule(val id: String, val name: Say, val trigger: Say, val action: Say, val needs: Needs)

/**
 * Concept automations. Nothing here sends a command, a notification or a location: [simulate] only states
 * what the rule would do with the current context. Rules that need Vehicle Control or location are roadmap
 * items that open when those Pleos SDK entitlements are granted.
 */
object Automations {
    val rules: List<Rule> = listOf(
        Rule("low_battery_parked", Say("배터리 부족 알림", "Low battery reminder"),
            Say("배터리 20% 이하로 주차하면", "When parked at 20% or less"),
            Say("휴대폰 알림을 보내고 캘린더에 충전 일정을 넣습니다", "notify the phone and add a charging slot to the calendar"), Needs.RECORDS),
        Rule("service_due", Say("정비 미리 챙기기", "Service ahead of time"),
            Say("정비 시기가 7일 남으면", "When a service is due in 7 days"),
            Say("연결된 AI 비서가 정비소 예약 초안을 만듭니다", "the connected AI assistant drafts a workshop booking"), Needs.RECORDS),
        Rule("precondition", Say("출발 전 실내 예열", "Cabin preconditioning"),
            Say("평일 출발 30분 전에", "30 minutes before a weekday departure"),
            Say("실내를 22℃로 맞춥니다", "set the cabin to 22℃"), Needs.VEHICLE_CONTROL),
        Rule("off_peak_charge", Say("경부하 예약 충전", "Off-peak charging"),
            Say("충전기를 연결해 두면 경부하 시간에", "When plugged in, at off-peak"),
            Say("충전을 시작합니다", "start charging"), Needs.VEHICLE_CONTROL),
        Rule("home_arrival", Say("집 도착 스마트홈", "Home arrival"),
            Say("집 근처에 도착하면", "When arriving near home"),
            Say("현관 조명과 난방을 켭니다", "turn on the entrance light and heating"), Needs.LOCATION),
    )

    fun simulate(rule: Rule, c: CopilotContext): Say {
        val outcome: Say = when (rule.id) {
            "low_battery_parked" -> when (val soc = c.now.socPercent) {
                null -> Say("배터리 값을 받지 못해 조건을 판단할 수 없습니다.", "The battery level is missing, so the condition cannot be judged.")
                else -> if (soc <= Copilot.LOW_BATTERY_PERCENT && c.now.parked == true)
                    Say("배터리 ${Copilot.num(soc)}%로 주차 중이라 조건을 충족합니다. 알림과 충전 일정이 만들어집니다.", "Parked at ${Copilot.num(soc)}%: the condition is met. A reminder and a charging slot would be created.")
                else Say("배터리 ${Copilot.num(soc)}%라 조건을 충족하지 않습니다.", "At ${Copilot.num(soc)}% the condition is not met.")
            }
            "service_due" -> when (val days = c.service?.daysRemaining) {
                null -> Say("기준이 있는 정비 항목이 없어 조건을 판단할 수 없습니다.", "No maintenance item has a baseline, so the condition cannot be judged.")
                else -> if (days <= 7) Say("${c.service.name.ko}까지 ${days}일 남아 조건을 충족합니다. 예약 초안이 만들어집니다.", "${c.service.name.en} is due in $days days: the condition is met. A booking draft would be created.")
                else Say("${c.service.name.ko}까지 ${days}일 남아 아직 조건을 충족하지 않습니다.", "${c.service.name.en} is due in $days days: not yet.")
            }
            "precondition" -> Say("${c.plan.departAt} 출발 30분 전에 실내 예열을 요청합니다.", "Preconditioning would be requested 30 minutes before the ${c.plan.departAt} departure.")
            "off_peak_charge" -> Say("${c.tariff.offPeakFromHour}시에 충전 시작을 요청합니다.", "Charging would be requested at ${c.tariff.offPeakFromHour}:00.")
            else -> Say("도착을 감지하면 스마트홈에 요청을 보냅니다.", "A smart-home request would be sent on arrival.")
        }
        val tail = when (rule.needs) {
            Needs.RECORDS -> Say("시연이며 실제로 보내지 않았습니다.", "This is a demonstration; nothing was sent.")
            Needs.VEHICLE_CONTROL -> Say("차량 제어 권한이 필요한 컨셉이며 차량에 명령을 보내지 않았습니다.", "This concept needs the vehicle-control entitlement; no command was sent to the car.")
            Needs.LOCATION -> Say("위치 권한이 필요한 컨셉이며 위치를 읽지 않았습니다.", "This concept needs the location entitlement; no location was read.")
        }
        return outcome + tail
    }
}
