package app.danim.automotive.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.danim.automotive.R
import app.danim.automotive.ui.ShowcaseModel.fmtKrw
import app.danim.automotive.ui.ShowcaseModel.fmtNum
import app.danim.automotive.ui.ShowcaseModel.fmtWhen
import app.danim.core.Qr
import app.danim.core.showcase.CompetitionReport
import java.util.Locale

/**
 * The fixed competition records in the car (0.4.1): a glanceable dashboard (recorded SOC, range,
 * charging state, next maintenance), the management week, structured maintenance, and the mobile/AI
 * handoff (public report QR, owner service and AI setup pages). The data is the bundled public
 * [CompetitionReport] (`competition-service.json`) through [ShowcaseModel]; nothing here is a vehicle
 * reading, nothing is simulated, and nothing reads the Vehicle SDK, the vault, Room, settings or the
 * network. The real collector/pairing path is a separate screen (`SetupScreen` / `StatusScreen`).
 *
 * Layout contract (verified on the official 2560×1440 landscape viewport): five tabs of one fixed
 * height in one row, every control at least [ShowcaseModel.MIN_TARGET_DP]; the dashboard fits the
 * first screen, scrolling is for secondary detail only.
 */
enum class ShowcaseTab { STATUS, REPORT, MAINTENANCE, CONNECT, ROADMAP }

/** Presentation state only: the open tab, a reset counter that proves a repeat in a rehearsal, and the limitations toggle. */
data class ShowcaseUi(val tab: ShowcaseTab = ShowcaseTab.STATUS, val resets: Int = 0, val limits: Boolean = false) {
    /** Back to the dashboard, counter +1, detail collapsed; no record changes. */
    fun reset() = ShowcaseUi(resets = resets + 1)
    fun toggleLimits() = copy(limits = !limits)
}

private val KO: Boolean get() = Locale.getDefault().language == "ko"
private fun CompetitionReport.Label.text(): String = if (KO) ko else en
private fun CompetitionReport.Question.text(): String = if (KO) ko else en

@Composable
fun ShowcaseScreen(s: ShowcaseUi, vm: MainViewModel) {
    val r = CompetitionReport.report
    val dash = remember { ShowcaseModel.dashboard() }
    ShowcaseProvenance(dash)
    ShowcaseTabs(s.tab, vm::showcaseTab)
    when (s.tab) {
        ShowcaseTab.STATUS -> ShowcaseDashboard(dash, r)
        ShowcaseTab.REPORT -> ShowcaseReport(r)
        ShowcaseTab.MAINTENANCE -> ShowcaseMaintenance(r)
        ShowcaseTab.CONNECT -> ShowcaseConnect(r)
        ShowcaseTab.ROADMAP -> ShowcaseRoadmap()
    }
    ShowcaseControls(s, vm, r)
}

// ---- Provenance: compact, persistent, neutral ------------------------------------------------------

@Composable
private fun ShowcaseProvenance(d: ShowcaseModel.Dashboard) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("showcaseBanner"),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.showcase_prov_label), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
                stringResource(R.string.showcase_prov_line, d.version, d.asOf, d.source),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }
    }
}

// ---- Tabs: five equal, stable targets ----------------------------------------------------------------

@Composable
private fun tabLabel(tab: ShowcaseTab): String = when (tab) {
    ShowcaseTab.STATUS -> stringResource(R.string.showcase_tab_status)
    ShowcaseTab.REPORT -> stringResource(R.string.showcase_tab_report)
    ShowcaseTab.MAINTENANCE -> stringResource(R.string.showcase_tab_maintenance)
    ShowcaseTab.CONNECT -> stringResource(R.string.showcase_tab_connect)
    ShowcaseTab.ROADMAP -> stringResource(R.string.showcase_tab_roadmap)
}

/** One row of five tabs in the landscape app viewport; a 3 + 2 grid of the same tiles in a narrow window. Every tile has the same size in both states. */
@Composable
private fun ShowcaseTabs(current: ShowcaseTab, onSelect: (ShowcaseTab) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val perRow = if (maxWidth >= 900.dp) ShowcaseTab.entries.size else 3
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ShowcaseTab.entries.chunked(perRow).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { tab -> ShowcaseTabButton(tabLabel(tab), tab == current, { onSelect(tab) }, "showcaseTab_${tab.name}") }
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** The one tab component: same height, shape, padding and type in both states; only colours change on selection. */
@Composable
internal fun RowScope.ShowcaseTabButton(label: String, selected: Boolean, onClick: () -> Unit, tag: String) {
    Button(
        onClick = onClick,
        modifier = Modifier.weight(1f).height(ShowcaseModel.TAB_HEIGHT_DP.dp).testTag(tag).semantics { this.selected = selected },
        shape = MaterialTheme.shapes.medium,
        colors = if (selected) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary),
        border = if (selected) null else BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
    ) { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

// ---- Building blocks -------------------------------------------------------------------------------

@Composable
internal fun ShowcaseCard(modifier: Modifier = Modifier, tag: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.let { if (tag != null) it.testTag(tag) else it },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}

@Composable
internal fun ShowcaseSecondary(label: String, onClick: () -> Unit, tag: String, danger: Boolean = false) {
    OutlinedButton(
        onClick = onClick, modifier = Modifier.heightIn(min = ShowcaseModel.MIN_TARGET_DP.dp).testTag(tag),
        shape = MaterialTheme.shapes.small, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
    ) { Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
}

/** A labelled value line; the tag sits on the whole line so a read-back sees label and value together. */
@Composable
private fun ShowcaseLine(label: String, value: String, tag: String? = null, emphasis: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).let { if (tag != null) it.testTag(tag) else it }, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = if (emphasis) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.6f))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun ShowcaseTitle(text: String) = Text(text, style = MaterialTheme.typography.titleSmall)

@Composable
internal fun ShowcaseNote(text: String, tag: String? = null) =
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.let { if (tag != null) it.testTag(tag) else it })

/** A glanceable number: label, big value, one small sub-line. All tiles in a row share the row's height. */
@Composable
internal fun RowScope.ShowcaseTile(label: String, value: String, sub: String, tag: String, big: Boolean = true, tinted: Boolean = false) {
    Surface(
        modifier = Modifier.weight(1f).fillMaxHeight().testTag(tag),
        shape = MaterialTheme.shapes.medium,
        color = if (tinted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = if (tinted) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (tinted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = if (big) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge, color = if (tinted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = if (tinted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun stateLabel(state: CompetitionReport.OutlookState): String = when (state) {
    CompetitionReport.OutlookState.UNKNOWN -> stringResource(R.string.showcase_outlook_unknown)
    CompetitionReport.OutlookState.OVERDUE -> stringResource(R.string.showcase_outlook_overdue)
    CompetitionReport.OutlookState.DUE_SOON -> stringResource(R.string.showcase_outlook_due_soon)
    CompetitionReport.OutlookState.OK -> stringResource(R.string.showcase_outlook_ok)
}

/** One state indicator per schedule. Error colour only for overdue; unknown is neutral, not an error. */
@Composable
private fun StateChip(state: CompetitionReport.OutlookState, tag: String? = null) {
    val (bg, fg) = when (state) {
        CompetitionReport.OutlookState.OVERDUE -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        CompetitionReport.OutlookState.DUE_SOON -> MaterialTheme.colorScheme.tertiary to MaterialTheme.colorScheme.onTertiary
        CompetitionReport.OutlookState.OK -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        CompetitionReport.OutlookState.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = MaterialTheme.shapes.small, color = bg, modifier = Modifier.let { if (tag != null) it.testTag(tag) else it }) {
        Text(stateLabel(state), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = fg, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), maxLines = 1)
    }
}

/** "7 days · 10,910.5 km left", only the known parts; an unknown schedule states its one reason instead. */
@Composable
private fun dueSummary(row: ShowcaseModel.MaintenanceRow): String = when (row.unknownReason) {
    ShowcaseModel.UnknownReason.NO_BASELINE -> stringResource(R.string.showcase_unknown_no_baseline)
    ShowcaseModel.UnknownReason.NO_INTERVAL -> stringResource(R.string.showcase_unknown_no_interval)
    ShowcaseModel.UnknownReason.UNUSABLE_BASIS -> stringResource(R.string.showcase_unknown_basis)
    null -> listOfNotNull(
        row.daysRemaining?.let { stringResource(R.string.showcase_days_remaining, it) },
        row.kmRemaining?.let { stringResource(R.string.showcase_km_remaining, fmtNum(it)) },
    ).joinToString(" · ")
}

@Composable
private fun basisLine(row: ShowcaseModel.MaintenanceRow): String {
    val parts = listOfNotNull(row.lastServiceDate, row.lastServiceOdometerKm?.let { fmtNum(it) + " km" })
    return stringResource(R.string.showcase_basis) + " " + (if (parts.isEmpty()) stringResource(R.string.showcase_basis_none) else parts.joinToString(" · "))
}

@Composable
private fun intervalLine(row: ShowcaseModel.MaintenanceRow): String {
    val parts = listOfNotNull(
        row.intervalDays?.let { stringResource(R.string.showcase_interval_days, it) },
        row.intervalKm?.let { stringResource(R.string.showcase_interval_km, fmtNum(it)) },
    )
    return stringResource(R.string.showcase_interval) + " " + (if (parts.isEmpty()) stringResource(R.string.showcase_interval_none) else parts.joinToString(" · "))
}

@Composable
private fun chargingText(state: String?): String = when (state) {
    null -> stringResource(R.string.unknown)
    "idle" -> stringResource(R.string.showcase_charging_idle)
    "charging" -> stringResource(R.string.showcase_charging_charging)
    else -> state
}

// ---- Status: the glanceable dashboard ----------------------------------------------------------------

@Composable
private fun ShowcaseDashboard(d: ShowcaseModel.Dashboard, r: CompetitionReport.Report) {
    val unknown = stringResource(R.string.unknown)
    val recorded = stringResource(R.string.showcase_recorded_at) + " " + fmtWhen(d.observedAt)
    val same = stringResource(R.string.showcase_same_record)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max).testTag("showcaseStatusCard"), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ShowcaseTile(stringResource(R.string.showcase_battery), d.socPercent?.let { fmtNum(it) + "%" } ?: unknown, recorded, tag = "showcaseSoc", tinted = true)
        ShowcaseTile(stringResource(R.string.showcase_range), d.rangeKm?.let { fmtNum(it) + " km" } ?: unknown, same, tag = "showcaseRange")
        ShowcaseTile(stringResource(R.string.showcase_charging_state), chargingText(d.chargingState), same, tag = "showcaseChargingState", big = false)
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ShowcaseCard(Modifier.weight(1f).fillMaxHeight(), tag = "showcaseDetailsCard") {
            ShowcaseTitle(stringResource(R.string.showcase_details_title))
            ShowcaseLine(stringResource(R.string.showcase_recorded_at), fmtWhen(d.observedAt), tag = "showcaseObservedAt")
            ShowcaseLine(stringResource(R.string.showcase_odometer), d.odometerKm?.let { fmtNum(it) + " km" } ?: unknown, tag = "showcaseOdometer")
            ShowcaseLine(stringResource(R.string.showcase_fuel), if (d.fuelUnsupported) stringResource(R.string.showcase_unsupported) else (d.fuelLevelPercent?.let { fmtNum(it) + "%" } ?: unknown), tag = "showcaseFuel")
            ShowcaseLine(stringResource(R.string.showcase_health), d.batteryHealthPercent?.let { fmtNum(it) + "%" } ?: (unknown + " · " + stringResource(R.string.showcase_health_unknown)), tag = "showcaseHealth")
            ShowcaseNote(stringResource(R.string.showcase_status_note), tag = "showcaseStatusLabel")
        }
        ShowcaseCard(Modifier.weight(1f).fillMaxHeight(), tag = "showcaseNextCard") {
            ShowcaseTitle(stringResource(R.string.showcase_next_title))
            val next = d.next
            if (next == null) {
                Text(stringResource(R.string.showcase_next_none), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("showcaseNext"))
            } else {
                Row(Modifier.testTag("showcaseNext"), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    StateChip(next.state)
                    Text(next.name.text(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(dueSummary(next), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("showcaseNextDue"))
            }
            ShowcaseNote(
                (if (d.unknownScheduleCount > 0) stringResource(R.string.showcase_unknown_schedules, d.unknownScheduleCount) + " · " else "") + stringResource(R.string.showcase_see_maintenance),
                tag = "showcaseNextNote",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ShowcaseTitle(stringResource(R.string.showcase_notices_title))
            r.alerts.forEachIndexed { i, a ->
                Row(Modifier.fillMaxWidth().testTag("showcaseNotice_$i"), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(a.recordedAt.take(10), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(a.label.text(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
            ShowcaseNote(stringResource(R.string.showcase_notice_delivery))
        }
    }
}

// ---- Report: the management week ------------------------------------------------------------------

@Composable
private fun ShowcaseReport(r: CompetitionReport.Report) {
    val sum = remember { CompetitionReport.summary() }
    ShowcaseNote(r.title.text() + " · " + r.period.label.text() + " · " + r.period.from.take(10) + " → " + r.period.to.take(10) + " · " + r.timeZone, tag = "showcasePeriod")
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max).testTag("showcaseReportCard"), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ShowcaseTile(stringResource(R.string.showcase_trip_total), fmtNum(sum.tripDistanceKm) + " km", stringResource(R.string.showcase_trip_total_value, sum.tripCount), tag = "showcaseTripTotal")
        ShowcaseTile(stringResource(R.string.showcase_charge_total), fmtNum(sum.gridEnergyKwh) + " kWh", stringResource(R.string.showcase_charge_total_value, sum.chargingSessionCount, fmtNum(sum.batteryEnergyKwh)), tag = "showcaseChargeTotal")
        ShowcaseTile(stringResource(R.string.showcase_cost_total), fmtKrw(sum.chargingCostKrw), stringResource(R.string.showcase_cost_basis_short), tag = "showcaseCostTotal", tinted = true)
    }
    ShowcaseNote(stringResource(R.string.showcase_cost_basis), tag = "showcaseCostBasis")
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ShowcaseCard(Modifier.weight(1f).fillMaxHeight(), tag = "showcaseTripsCard") {
            ShowcaseTitle(stringResource(R.string.showcase_trips_title))
            r.trips.forEachIndexed { i, t ->
                ShowcaseLine(t.date, stringResource(R.string.showcase_trip_line, fmtNum(t.distanceKm), fmtNum(t.averageSpeedKph), fmtNum(t.maximumSpeedKph), fmtNum(t.assistedDistanceKm)), tag = "showcaseTrip_$i")
            }
            ShowcaseNote(stringResource(R.string.showcase_trips_note))
        }
        ShowcaseCard(Modifier.weight(1f).fillMaxHeight(), tag = "showcaseChargesCard") {
            ShowcaseTitle(stringResource(R.string.showcase_charges_title))
            r.chargingSessions.forEachIndexed { i, c ->
                ShowcaseLine(c.startedAt.take(10), stringResource(R.string.showcase_charge_line, fmtNum(c.fromSoc), fmtNum(c.toSoc), fmtNum(c.energyKwh), fmtNum(c.gridEnergyKwh), c.tariffPerKwh, fmtKrw(c.cost)), tag = "showcaseCharge_$i")
            }
            ShowcaseNote(stringResource(R.string.showcase_coverage_note))
        }
    }
}

// ---- Maintenance: one state chip, a concise due line, separate baseline / interval lines -----------

@Composable
private fun ShowcaseMaintenance(r: CompetitionReport.Report) {
    val rows = remember { ShowcaseModel.maintenanceRows() }
    val unknown = stringResource(R.string.unknown)
    ShowcaseCard(Modifier.fillMaxWidth(), tag = "showcaseMaintenanceCard") {
        ShowcaseTitle(stringResource(R.string.showcase_maintenance_title))
        ShowcaseNote(r.maintenance.assumptions.text(), tag = "showcaseMaintenanceLabel")
        rows.forEach { row ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("showcaseSchedule_${row.id}"), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.width(220.dp).padding(top = 4.dp)) { StateChip(row.state) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.name.text(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(dueSummary(row), style = MaterialTheme.typography.bodySmall)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(basisLine(row), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(intervalLine(row), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        ShowcaseNote(stringResource(R.string.showcase_maintenance_note, r.asOf, r.status.odometerKm?.let { fmtNum(it) } ?: unknown))
    }
    ShowcaseCard(Modifier.fillMaxWidth(), tag = "showcaseRecordsCard") {
        ShowcaseTitle(stringResource(R.string.showcase_records_title))
        r.maintenance.records.forEachIndexed { i, rec ->
            ShowcaseLine(
                rec.serviceName.text(),
                rec.occurredOn + " · " + (rec.odometerKm?.let { fmtNum(it) + " km" } ?: unknown) + " · " + (rec.amountKrw?.let { fmtKrw(it) } ?: (stringResource(R.string.showcase_amount) + " " + unknown)) + " · " + rec.source,
                tag = "showcaseRecord_$i",
            )
        }
    }
}

// ---- Connect: the same public record on the phone, and the public AI endpoint ------------------------

@Composable
private fun ShowcaseConnect(r: CompetitionReport.Report) {
    val h = remember { ShowcaseModel.handoff() }
    val qr = remember { Qr.matrix(h.qrUrl) }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ShowcaseCard(Modifier.weight(1f).fillMaxHeight(), tag = "showcaseWebCard") {
            ShowcaseTitle(stringResource(R.string.showcase_web_title))
            ShowcaseNote(stringResource(R.string.showcase_web_label), tag = "showcaseWebLabel")
            QrCode(qr, h.qrUrl)
            Text(h.qrUrl, style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("showcaseWebUrl"))
            ShowcaseNote(stringResource(R.string.showcase_qr_hint))
            Column(Modifier.testTag("showcaseWebVersion"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.showcase_report_version) + " " + h.version, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(h.reportId, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ShowcaseCard(Modifier.weight(1.4f).fillMaxHeight(), tag = "showcaseHandoffCard") {
            ShowcaseTitle(stringResource(R.string.showcase_handoff_title))
            ShowcaseLine(stringResource(R.string.showcase_role_car), stringResource(R.string.showcase_role_car_value), tag = "showcaseRoleCar")
            ShowcaseLine(stringResource(R.string.showcase_role_mobile), stringResource(R.string.showcase_role_mobile_value), tag = "showcaseRoleMobile")
            ShowcaseLine(stringResource(R.string.showcase_link_public), h.showcaseUrl, tag = "showcasePublicUrl")
            ShowcaseLine(stringResource(R.string.showcase_link_owner), h.ownerSiteUrl, tag = "showcaseOwnerUrl")
            ShowcaseLine(stringResource(R.string.showcase_link_integrations), h.integrationsUrl, tag = "showcaseIntegrationsUrl")
            ShowcaseNote(stringResource(R.string.showcase_handoff_note), tag = "showcaseHandoffNote")
        }
    }
    ShowcaseCard(Modifier.fillMaxWidth(), tag = "showcaseAiCard") {
        ShowcaseTitle(stringResource(R.string.showcase_ai_title))
        ShowcaseNote(stringResource(R.string.showcase_ai_label), tag = "showcaseAiLabel")
        ShowcaseLine(stringResource(R.string.showcase_ai_endpoint), h.mcpUrl + "\n" + stringResource(R.string.showcase_ai_tools, CompetitionReport.MCP_MAINTENANCE_TOOL), tag = "showcaseAiEndpoint")
        r.questions.forEachIndexed { i, q -> ShowcaseLine(stringResource(R.string.showcase_question_n, i + 1), q.text(), tag = "showcaseQuestion_${q.id}") }
        ShowcaseLine(stringResource(R.string.showcase_ai_real), stringResource(R.string.showcase_ai_real_value), tag = "showcaseAiReal")
        ShowcaseLine(stringResource(R.string.showcase_ai_consumers), stringResource(R.string.showcase_ai_consumers_value), tag = "showcaseAiConsumers")
    }
}

@Composable
private fun ShowcaseRoadmap() {
    ShowcaseCard(Modifier.fillMaxWidth(), tag = "showcaseRoadmapCard") {
        ShowcaseTitle(stringResource(R.string.showcase_roadmap_title))
        Text(stringResource(R.string.showcase_roadmap_now), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.showcase_roadmap_planned), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("showcaseRoadmap"))
        ShowcaseNote(stringResource(R.string.showcase_roadmap_gate))
    }
}

// ---- Controls: Reset / Exit / Limitations ------------------------------------------------------------

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ShowcaseControls(s: ShowcaseUi, vm: MainViewModel, r: CompetitionReport.Report) {
    ShowcaseCard(Modifier.fillMaxWidth(), tag = "showcaseControls") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ShowcaseSecondary(stringResource(R.string.showcase_reset), vm::mockReset, tag = "showcaseReset", danger = true)
            ShowcaseSecondary(stringResource(R.string.showcase_exit), vm::exitMock, tag = "showcaseExit")
            ShowcaseSecondary(stringResource(if (s.limits) R.string.showcase_limits_hide else R.string.showcase_limits), vm::showcaseLimits, tag = "showcaseLimitsToggle")
        }
        if (s.resets > 0) Text(stringResource(R.string.showcase_reset_count, s.resets), style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("showcaseResetCount"))
        ShowcaseNote(stringResource(R.string.showcase_controls_note))
        if (s.limits) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.testTag("showcaseLimits"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(r.notice.text(), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.showcase_limits_realcar), style = MaterialTheme.typography.bodySmall)
                (if (KO) r.assumptions.ko else r.assumptions.en).forEachIndexed { i, a -> ShowcaseNote("• $a", tag = "showcaseAssumption_$i") }
            }
        }
    }
}
