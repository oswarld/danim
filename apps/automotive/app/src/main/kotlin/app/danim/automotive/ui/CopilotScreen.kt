package app.danim.automotive.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.danim.automotive.R
import app.danim.automotive.nav.NaviOutcome
import app.danim.automotive.voice.EarState
import app.danim.automotive.voice.SpeechState
import app.danim.core.copilot.Automations
import app.danim.core.copilot.Copilot
import app.danim.core.copilot.CopilotContext
import app.danim.core.copilot.Needs
import app.danim.core.copilot.Origin
import app.danim.core.copilot.Rule
import app.danim.core.copilot.Say
import java.util.Locale
import kotlin.math.roundToInt

/**
 * DANIM Copilot in the car (0.5.0): the moments that only exist in a car. Departure briefing, voice
 * questions, a low-battery prompt with a navigation hand-off, the arrival report, charging advice and
 * automation concepts. Sentences come from the pure [Copilot] over a [CopilotContext]; speech and voice
 * input are real Pleos TextToSpeech / SpeechToText calls and the navigation buttons are real NaviHelper
 * calls. The day plan, tariff and battery size are examples and are labelled where they appear. No
 * vehicle command or location read exists in this build; those rules are shown as concepts.
 */
private val KO: Boolean get() = Locale.getDefault().language == "ko"
private fun Say.show(): String = text(KO)

@Composable
fun CopilotScreen(s: CopilotUi, vm: MainViewModel) {
    val c = s.context
    CopilotProvenance(c.now.origin)
    CopilotTabs(s.tab, vm::copilotTab)
    when (s.tab) {
        CopilotTab.BRIEFING -> CopilotBriefing(s, c, vm)
        CopilotTab.ASK -> CopilotAsk(s, vm)
        CopilotTab.DRIVE -> CopilotDrive(s, c, vm)
        CopilotTab.CHARGE -> CopilotCharge(s, c, vm)
        CopilotTab.AUTOMATE -> CopilotAutomate(s, vm)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        ShowcaseSecondary(stringResource(R.string.copilot_close), vm::exitCopilot, "copilotClose")
        SpeechStatus(s)
    }
}

@Composable
private fun CopilotProvenance(origin: Origin) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("copilotBanner"),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.copilot_prov_label), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
                stringResource(if (origin == Origin.LIVE) R.string.copilot_prov_live else R.string.copilot_prov_fixed),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun tabLabel(tab: CopilotTab): String = stringResource(
    when (tab) {
        CopilotTab.BRIEFING -> R.string.copilot_tab_briefing
        CopilotTab.ASK -> R.string.copilot_tab_ask
        CopilotTab.DRIVE -> R.string.copilot_tab_drive
        CopilotTab.CHARGE -> R.string.copilot_tab_charge
        CopilotTab.AUTOMATE -> R.string.copilot_tab_automate
    },
)

@Composable
private fun CopilotTabs(current: CopilotTab, onSelect: (CopilotTab) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val perRow = if (maxWidth >= 900.dp) CopilotTab.entries.size else 3
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CopilotTab.entries.chunked(perRow).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { tab -> ShowcaseTabButton(tabLabel(tab), tab == current, { onSelect(tab) }, "copilotTab_${tab.name}") }
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

// ---- Shared pieces ---------------------------------------------------------------------------------

@Composable
private fun originLabel(origin: Origin): String = stringResource(
    when (origin) {
        Origin.LIVE -> R.string.copilot_origin_live
        Origin.FIXED -> R.string.copilot_origin_fixed
        Origin.EXAMPLE -> R.string.copilot_origin_example
    },
)

@Composable
private fun Badge(text: String, strong: Boolean = false) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (strong) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        border = if (strong) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(
            text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
            color = if (strong) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun PrimaryAction(label: String, onClick: () -> Unit, tag: String) {
    Button(
        onClick = onClick, modifier = Modifier.heightIn(min = ShowcaseModel.MIN_TARGET_DP.dp).testTag(tag),
        shape = MaterialTheme.shapes.small, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 8.dp),
    ) { Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
}

/** The sentence the car says, with the control that says it. The text is always visible, so speech is never the only channel. */
@Composable
private fun Spoken(text: Say, s: CopilotUi, vm: MainViewModel, tag: String, extra: @Composable () -> Unit = {}) {
    Text(text.show(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("${tag}Text"))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (s.speech == SpeechState.SPEAKING || s.speech == SpeechState.PREPARING) {
            ShowcaseSecondary(stringResource(R.string.copilot_stop), vm::copilotHush, "${tag}Stop")
        } else {
            PrimaryAction(stringResource(R.string.copilot_listen), { vm.copilotSay(text) }, "${tag}Listen")
        }
        extra()
    }
}

@Composable
private fun SpeechStatus(s: CopilotUi) {
    val text = when (s.speech) {
        SpeechState.IDLE -> ""
        SpeechState.PREPARING -> stringResource(R.string.copilot_speech_preparing)
        SpeechState.SPEAKING -> stringResource(R.string.copilot_speech_speaking)
        SpeechState.UNAVAILABLE -> stringResource(R.string.copilot_speech_unavailable, s.speechError ?: "")
    }
    if (text.isNotEmpty()) ShowcaseNote(text, tag = "copilotSpeech")
}

@Composable
private fun ConceptSwitch(rule: Rule, s: CopilotUi, vm: MainViewModel) {
    val on = rule.id in s.enabled
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${rule.trigger.show()} ${rule.action.show()}", style = MaterialTheme.typography.bodySmall)
            ShowcaseNote(stringResource(if (rule.needs == Needs.LOCATION) R.string.copilot_needs_location_note else R.string.copilot_needs_control_note))
        }
        ShowcaseSecondary(stringResource(if (on) R.string.copilot_on else R.string.copilot_off), { vm.copilotToggle(rule.id) }, "copilotSwitch_${rule.id}")
    }
}

private fun rule(id: String): Rule = Automations.rules.first { it.id == id }

// ---- Departure -------------------------------------------------------------------------------------

@Composable
private fun CopilotBriefing(s: CopilotUi, c: CopilotContext, vm: MainViewModel) {
    val unknown = stringResource(R.string.unknown)
    ShowcaseCard(Modifier.fillMaxWidth(), tag = "copilotBriefing") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ShowcaseTitle(stringResource(R.string.copilot_briefing_title))
            Badge(originLabel(c.now.origin))
        }
        Spoken(Copilot.briefing(c), s, vm, "copilotBriefing")
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ShowcaseTile(stringResource(R.string.showcase_battery), c.now.socPercent?.let { Copilot.num(it) + "%" } ?: unknown, originLabel(c.now.origin), tag = "copilotSoc", tinted = true)
        ShowcaseTile(stringResource(R.string.showcase_range), c.now.rangeKm?.let { Copilot.num(it) + " km" } ?: unknown, originLabel(c.now.origin), tag = "copilotRange")
        val left = Copilot.chargeAfterPlan(c.now, c.plan)
        ShowcaseTile(
            stringResource(R.string.copilot_plan_label),
            stringResource(R.string.copilot_plan_value, Copilot.num(c.plan.roundTripKm)),
            if (left != null) stringResource(R.string.copilot_plan_sub, c.plan.place.show(), c.plan.departAt, left.roundToInt()) else stringResource(R.string.copilot_plan_sub_unknown, c.plan.place.show(), c.plan.departAt),
            tag = "copilotPlan", big = false,
        )
    }
    ShowcaseCard(Modifier.fillMaxWidth(), tag = "copilotPrecondition") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ShowcaseTitle(stringResource(R.string.copilot_precondition_title))
            Badge(stringResource(R.string.copilot_concept), strong = true)
        }
        ConceptSwitch(rule("precondition"), s, vm)
    }
}

// ---- Ask -------------------------------------------------------------------------------------------

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun CopilotAsk(s: CopilotUi, vm: MainViewModel) {
    ShowcaseCard(Modifier.fillMaxWidth(), tag = "copilotAsk") {
        ShowcaseTitle(stringResource(R.string.copilot_ask_title))
        ShowcaseNote(stringResource(R.string.copilot_ask_hint))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s.ear == EarState.LISTENING || s.ear == EarState.PREPARING) {
                ShowcaseSecondary(stringResource(R.string.copilot_stop), vm::copilotStopListening, "copilotMicStop")
            } else {
                PrimaryAction(stringResource(R.string.copilot_mic), vm::copilotListen, "copilotMic")
            }
            val status = when (s.ear) {
                EarState.IDLE -> if (s.heard.isNotBlank()) stringResource(R.string.copilot_heard, s.heard) else ""
                EarState.PREPARING -> stringResource(R.string.copilot_mic_preparing)
                EarState.LISTENING -> if (s.heard.isNotBlank()) stringResource(R.string.copilot_heard, s.heard) else stringResource(R.string.copilot_mic_listening)
                EarState.NOTHING_HEARD -> stringResource(R.string.copilot_mic_nothing)
                EarState.UNAVAILABLE -> stringResource(R.string.copilot_mic_unavailable, s.earError ?: "")
            }
            if (status.isNotEmpty()) ShowcaseNote(status, tag = "copilotMicStatus")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CopilotModel.questions.forEachIndexed { i, q -> ShowcaseSecondary(q.show(), { vm.copilotAsk(q.show()) }, "copilotQuestion_$i") }
        }
    }
    s.turns.asReversed().forEachIndexed { i, turn ->
        ShowcaseCard(Modifier.fillMaxWidth(), tag = "copilotTurn_$i") {
            ShowcaseNote(turn.question)
            Spoken(turn.answer, s, vm, "copilotAnswer_$i")
        }
    }
}

// ---- Driving and arrival ---------------------------------------------------------------------------

@Composable
private fun naviText(o: NaviOutcome?, c: CopilotContext): String = when (o) {
    null -> ""
    NaviOutcome.Waiting -> stringResource(R.string.copilot_navi_waiting)
    NaviOutcome.ChargersRequested -> stringResource(R.string.copilot_navi_opened)
    NaviOutcome.NoDestination -> stringResource(R.string.copilot_navi_none)
    is NaviOutcome.Failed -> stringResource(R.string.copilot_navi_failed, o.reason)
    is NaviOutcome.Destination -> Copilot.destinationLine(c.now, o.name, o.remainingKm).show()
}

@Composable
private fun CopilotDrive(s: CopilotUi, c: CopilotContext, vm: MainViewModel) {
    val unknown = stringResource(R.string.unknown)
    ShowcaseNote(stringResource(R.string.copilot_drive_note))
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val liveLow = Copilot.lowBattery(c.now)
        ShowcaseCard(Modifier.weight(1f), tag = "copilotLow") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ShowcaseTitle(stringResource(R.string.copilot_low_title))
                Badge(originLabel(if (liveLow != null) c.now.origin else Origin.EXAMPLE))
            }
            if (liveLow == null) ShowcaseNote(stringResource(R.string.copilot_low_example))
            Spoken(liveLow ?: Copilot.lowBattery(CopilotModel.lowBatteryExample)!!, s, vm, "copilotLow") {
                ShowcaseSecondary(stringResource(R.string.copilot_chargers), vm::copilotChargers, "copilotChargers")
            }
            naviText(s.chargers, c).let { if (it.isNotEmpty()) ShowcaseNote(it, tag = "copilotChargersResult") }
        }
        ShowcaseCard(Modifier.weight(1f), tag = "copilotDestination") {
            ShowcaseTitle(stringResource(R.string.copilot_dest_title))
            ShowcaseNote(stringResource(R.string.copilot_dest_hint))
            ShowcaseSecondary(stringResource(R.string.copilot_dest_button), vm::copilotDestination, "copilotDestinationRead")
            naviText(s.destination, c).let { if (it.isNotEmpty()) Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("copilotDestinationResult")) }
        }
    }
    val trip = c.lastTrip
    if (trip != null) {
        ShowcaseCard(Modifier.fillMaxWidth(), tag = "copilotArrival") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ShowcaseTitle(stringResource(R.string.copilot_arrival_title))
                Badge(originLabel(trip.origin))
            }
            ShowcaseNote(stringResource(if (trip.origin == Origin.LIVE) R.string.copilot_arrival_live else R.string.copilot_arrival_fixed))
            Spoken(Copilot.arrival(trip), s, vm, "copilotArrival")
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ShowcaseTile(stringResource(R.string.copilot_trip_distance), trip.distanceKm?.let { Copilot.num(it) + " km" } ?: unknown, originLabel(trip.origin), tag = "copilotTripDistance", big = false)
            ShowcaseTile(stringResource(R.string.copilot_trip_time), stringResource(R.string.copilot_minutes, trip.minutes), originLabel(trip.origin), tag = "copilotTripTime", big = false)
            ShowcaseTile(stringResource(R.string.copilot_trip_used), trip.socUsed?.let { Copilot.num(it) + "%" } ?: unknown, originLabel(trip.origin), tag = "copilotTripUsed", big = false)
            ShowcaseTile(stringResource(R.string.copilot_trip_eff), Copilot.kmPerPercent(trip)?.let { Copilot.num((it * 10).roundToInt() / 10.0) + " km" } ?: unknown, stringResource(R.string.copilot_trip_eff_sub), tag = "copilotTripEff", big = false)
        }
    }
}

// ---- Charging --------------------------------------------------------------------------------------

@Composable
private fun CopilotCharge(s: CopilotUi, c: CopilotContext, vm: MainViewModel) {
    val advice = Copilot.chargeAdvice(c)
    val unknown = stringResource(R.string.unknown)
    fun krw(v: Int?): String? = v?.let { "%,d".format(Locale.ROOT, it) }
    ShowcaseCard(Modifier.fillMaxWidth(), tag = "copilotCharge") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ShowcaseTitle(stringResource(R.string.copilot_charge_title))
            Badge(originLabel(c.now.origin))
            Badge(stringResource(R.string.copilot_tariff_badge))
        }
        Spoken(advice.text, s, vm, "copilotCharge")
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val example = originLabel(Origin.EXAMPLE)
        ShowcaseTile(stringResource(R.string.copilot_cost_now), krw(advice.costNowKrw)?.let { stringResource(R.string.copilot_krw, it) } ?: unknown, example, tag = "copilotCostNow", big = false)
        ShowcaseTile(stringResource(R.string.copilot_cost_off), krw(advice.costOffPeakKrw)?.let { stringResource(R.string.copilot_krw, it) } ?: unknown, example, tag = "copilotCostOff", big = false)
        ShowcaseTile(stringResource(R.string.copilot_cost_saving), krw(advice.savingKrw)?.let { stringResource(R.string.copilot_krw, it) } ?: unknown, example, tag = "copilotSaving", big = false, tinted = true)
    }
    ShowcaseCard(Modifier.fillMaxWidth(), tag = "copilotTariff") {
        // 24 hours, off-peak hours filled, the current hour outlined.
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (hour in 0 until 24) {
                val off = c.tariff.isOffPeak(hour)
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (hour == c.hour) MaterialTheme.colorScheme.tertiary else if (off) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0", "6", "12", "18", "24").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        ShowcaseNote(
            stringResource(
                R.string.copilot_tariff_note, c.tariff.offPeakFromHour, c.tariff.offPeakToHour, c.tariff.offPeakKrwPerKwh,
                c.tariff.standardKrwPerKwh, Copilot.num(c.tariff.batteryKwh), c.hour,
            ),
            tag = "copilotTariffNote",
        )
    }
    ShowcaseCard(Modifier.fillMaxWidth(), tag = "copilotOffPeak") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ShowcaseTitle(stringResource(R.string.copilot_offpeak_title))
            Badge(stringResource(R.string.copilot_concept), strong = true)
        }
        ConceptSwitch(rule("off_peak_charge"), s, vm)
    }
}

// ---- Automations -----------------------------------------------------------------------------------

@Composable
private fun needsLabel(needs: Needs): String = stringResource(
    when (needs) {
        Needs.RECORDS -> R.string.copilot_needs_records
        Needs.VEHICLE_CONTROL -> R.string.copilot_needs_control
        Needs.LOCATION -> R.string.copilot_needs_location
    },
)

@Composable
private fun CopilotAutomate(s: CopilotUi, vm: MainViewModel) {
    ShowcaseNote(stringResource(R.string.copilot_auto_note))
    Automations.rules.forEach { r ->
        ShowcaseCard(Modifier.fillMaxWidth(), tag = "copilotRule_${r.id}") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ShowcaseTitle(r.name.show())
                        Badge(needsLabel(r.needs), strong = r.needs != Needs.RECORDS)
                    }
                    Text("${r.trigger.show()} ${r.action.show()}", style = MaterialTheme.typography.bodySmall)
                }
                ShowcaseSecondary(stringResource(if (r.id in s.enabled) R.string.copilot_on else R.string.copilot_off), { vm.copilotToggle(r.id) }, "copilotRuleSwitch_${r.id}")
                PrimaryAction(stringResource(R.string.copilot_run), { vm.copilotRun(r) }, "copilotRuleRun_${r.id}")
            }
            s.ran[r.id]?.let { ShowcaseNote(it.show(), tag = "copilotRuleResult_${r.id}") }
        }
    }
}
