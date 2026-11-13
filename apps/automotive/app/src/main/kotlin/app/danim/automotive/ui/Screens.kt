package app.danim.automotive.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.danim.automotive.R
import app.danim.core.Availability
import app.danim.core.Signal
import app.danim.core.Value
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun DanimScreen(state: UiState, vm: MainViewModel) {
    // testTags double as view resource ids so an emulator rehearsal can be driven and read back with uiautomator.
    Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text("DANIM", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            // The showcase carries its own compact provenance line and repeats this real-car note under
            // "Limitations", so the car viewport keeps its first screen for the dashboard.
            if (state.phase != Phase.MOCK && state.phase != Phase.COPILOT) UnverifiedBanner()
            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("message"))
            }
            when (state.phase) {
                Phase.SETUP -> SetupScreen(state, vm)
                Phase.PAIRING -> PairingScreen(state, vm)
                Phase.PAIRED -> state.diag?.let { StatusScreen(it, state.siteUrl, vm) }
                Phase.MOCK -> state.mock?.let { ShowcaseScreen(it, vm) }
                Phase.COPILOT -> state.copilot?.let { CopilotScreen(it, vm) }
            }
        }
    }
}

@Composable
private fun UnverifiedBanner() {
    DanimSection {
        Text(stringResource(R.string.technical_preview), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.unverified_banner),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DanimSection(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
private fun SetupScreen(state: UiState, vm: MainViewModel) {
    CopilotEntry(vm, enabled = !state.busy)
    Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.setup_body),
        style = MaterialTheme.typography.bodySmall,
    )
    DanimSection {
        Text(
            stringResource(R.string.setup_signals),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.setup_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LabeledCheck(stringResource(R.string.ack_parked), state.parkedAck, vm::onParked, "parked")
        LabeledCheck(
            stringResource(R.string.ack_consent),
            state.consentAck, vm::onConsent, "consent",
        )
    }
    DanimSection {
        UrlSetting(
            label = stringResource(R.string.api_url_label), value = state.apiBaseUrl,
            onValueChange = { vm.onConfig(it, state.siteUrl) }, tag = "apiUrl",
        )
        UrlSetting(
            label = stringResource(R.string.site_url_label), value = state.siteUrl,
            onValueChange = { vm.onConfig(state.apiBaseUrl, it) }, tag = "siteUrl",
        )
        DanimButton(
            onClick = vm::startPairing,
            enabled = state.parkedAck && state.consentAck && !state.busy,
            modifier = Modifier.fillMaxWidth().testTag("startPairing"),
            label = if (state.busy) stringResource(R.string.starting) else stringResource(R.string.show_pairing_code),
            loading = state.busy,
        )
    }
    MockEntry(vm, enabled = !state.busy)
}

/** Entry to the copilot demonstration (spoken briefing, voice questions, driving and charging moments, automation concepts). */
@Composable
private fun CopilotEntry(vm: MainViewModel, enabled: Boolean = true) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.copilot_entry_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(stringResource(R.string.copilot_entry_body), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            DanimButton(stringResource(R.string.copilot_entry_button), vm::enterCopilot, enabled = enabled, modifier = Modifier.testTag("openCopilot"))
        }
    }
}

/** Entry to the labelled fixed-record showcase; shown on the setup and status screens, visibly apart from the real collector. */
@Composable
private fun MockEntry(vm: MainViewModel, enabled: Boolean = true) {
    DanimSection {
        Text(stringResource(R.string.showcase_entry_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.showcase_entry_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // The setup screen disables it while a pairing start is in flight; the ViewModel refuses entry in that state too.
        DanimSecondaryButton(stringResource(R.string.showcase_entry_button), vm::enterMock, Modifier.fillMaxWidth().testTag("openMock"), enabled = enabled)
    }
}

@Composable
private fun UrlSetting(label: String, value: String, onValueChange: (String) -> Unit, tag: String) {
    // Keep wrapped labels in normal layout flow with large vehicle typography.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value, onValueChange, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = label },
            textStyle = MaterialTheme.typography.bodySmall, shape = MaterialTheme.shapes.medium,
        )
    }
}

@Composable
private fun DanimButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    // Figma 67:22538: medium 80 high, radius 24, horizontal padding 32.
    Button(
        onClick = onClick, enabled = enabled && !loading,
        modifier = modifier.heightIn(min = 80.dp).widthIn(min = 120.dp),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, strokeWidth = 3.dp)
            Spacer(Modifier.size(16.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun DanimSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    // Figma 67:22550: small 64 high, radius 16, horizontal padding 24.
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 64.dp),
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
    ) { Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold) }
}

@Composable
private fun LabeledCheck(text: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Checkbox(checked, onCheckedChange = null, modifier = Modifier.size(64.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PairingScreen(state: UiState, vm: MainViewModel) {
    val p = state.pairing ?: return
    var left by remember { mutableLongStateOf(p.expiresAtMs - System.currentTimeMillis()) }
    LaunchedEffect(p.expiresAtMs) {
        while (true) {
            left = p.expiresAtMs - System.currentTimeMillis()
            delay(1_000)
        }
    }
    Text(stringResource(R.string.pairing_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.pairing_body), style = MaterialTheme.typography.bodySmall)
    DanimSection {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val details: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(p.code, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("code"))
                    Text(p.link, style = MaterialTheme.typography.labelSmall)
                    Text(
                        if (left > 0) stringResource(R.string.expires_in, left / 60_000, left / 1000 % 60) else stringResource(R.string.expired),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(stringResource(R.string.waiting_approval), style = MaterialTheme.typography.bodySmall)
                }
            }
            // The display guide's portrait windows are at most 840dp; landscape starts at 1256dp.
            if (maxWidth >= 900.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    QrCode(p.qr, p.link)
                    Box(Modifier.weight(1f)) { details() }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(32.dp)) {
                    QrCode(p.qr, p.link)
                    details()
                }
            }
        }
        DanimSecondaryButton(stringResource(R.string.cancel), vm::cancelPairing, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun QrCode(matrix: Array<BooleanArray>, link: String) {
    val description = stringResource(R.string.qr_description, link)
    val quiet = 2
    val n = matrix.size + quiet * 2
    Canvas(
        Modifier.size(240.dp).background(Color.White).semantics { contentDescription = description },
    ) {
        val cell = size.width / n
        for (y in matrix.indices) for (x in matrix[y].indices) {
            if (matrix[y][x]) {
                drawRect(
                    Color.Black,
                    topLeft = androidx.compose.ui.geometry.Offset((x + quiet) * cell, (y + quiet) * cell),
                    size = androidx.compose.ui.geometry.Size(cell + 0.5f, cell + 0.5f),
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun StatusScreen(d: Diagnostics, siteUrl: String, vm: MainViewModel) {
    var confirm by remember { mutableStateOf(false) }
    val running = d.collectorState == "running"

    // Glanceable live view of this car first; collector controls and diagnostics follow.
    LiveDashboard(d, siteUrl) { CopilotEntry(vm) }
    Text(stringResource(R.string.collection_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.device_line_short, d.deviceIdShort, stringResource(if (running) R.string.collector_on else R.string.collector_off)),
        style = MaterialTheme.typography.bodySmall,
    )
    if (d.disconnectIncomplete) {
        DanimSection {
            Text(
                stringResource(R.string.disconnect_incomplete),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(24.dp),
            )
        }
    }
    if (d.pausedReauth) {
        DanimSection {
            Text(
                stringResource(R.string.paused_reauth),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(24.dp),
            )
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (running) {
            DanimSecondaryButton(stringResource(R.string.stop_collecting), vm::stopCollection, Modifier.testTag("stop"))
        } else {
            DanimButton(stringResource(R.string.start_collecting), vm::startCollection, enabled = !d.pausedReauth && !d.disconnectIncomplete, modifier = Modifier.testTag("start"))
        }
        DanimSecondaryButton(stringResource(R.string.disconnect), { confirm = true }, Modifier.testTag("disconnect"), danger = true)
    }

    SectionTitle(stringResource(R.string.signals_title))
    DanimSection { Signal.entries.forEach { s -> SignalRow(s, d) } }

    SectionTitle(stringResource(R.string.sync_title))
    val age = d.oldestQueuedMs?.let { fmtAge(System.currentTimeMillis() - it) } ?: stringResource(R.string.dash)
    Line(stringResource(R.string.waiting_upload), stringResource(R.string.events_oldest, d.queued, age))
    Line(stringResource(R.string.acked_by_server), "${d.acked}")
    Line(stringResource(R.string.removed_by_retention), "${d.expired}")
    Line(stringResource(R.string.quarantined), "${d.quarantined}")
    if (d.lostOnShutdown > 0) Line(stringResource(R.string.lost_on_shutdown), "${d.lostOnShutdown}")
    Line(stringResource(R.string.last_upload), if (d.lastUploadAtMs == 0L) stringResource(R.string.never) else "${fmtTime(d.lastUploadAtMs)} — ${d.lastUploadResult}")
    Line(stringResource(R.string.credential), d.credentialResult.ifEmpty { stringResource(R.string.not_checked_yet) })
    Line(stringResource(R.string.collector_report), d.collectorReportResult.ifEmpty { stringResource(R.string.not_sent_yet) })
    // The SDK's own status text is diagnostic detail, kept with the other sync lines rather than under the dashboard.
    Line(stringResource(R.string.vehicle_sdk), d.sdkState)
    DanimSecondaryButton(stringResource(R.string.upload_now), vm::retryUpload)

    SectionTitle(stringResource(R.string.gaps_title))
    if (d.gaps.isEmpty()) Text(stringResource(R.string.none_recorded), style = MaterialTheme.typography.bodySmall)
    d.gaps.forEach { Line("${fmtTime(it.fromMs)} → ${fmtTime(it.toMs)}", it.cause) }
    Text(
        stringResource(R.string.gaps_body),
        style = MaterialTheme.typography.bodySmall,
    )
    if (d.bootNote.isNotEmpty()) Line(stringResource(R.string.last_boot_recovery), d.bootNote)
    MockEntry(vm)

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.disconnect_title)) },
            text = {
                Text(stringResource(R.string.disconnect_body))
            },
            confirmButton = { DanimSecondaryButton(stringResource(R.string.disconnect), { confirm = false; vm.disconnect() }, danger = true) },
            dismissButton = { DanimSecondaryButton(stringResource(R.string.keep), { confirm = false }) },
        )
    }
}

@Composable
private fun SignalRow(s: Signal, d: Diagnostics) {
    val info = d.signals[s]
    val text = when {
        info == null -> stringResource(R.string.no_data_this_session)
        info.availability == Availability.AVAILABLE -> (fmtValue(info.value) ?: stringResource(R.string.unknown)) + " · " + fmtTime(info.observedAtMs)
        else -> info.availability.wire + " · " + fmtTime(info.observedAtMs)
    }
    Line(s.key, text)
}

/** Null only when an available reading has no value, which the factory prevents; the caller labels it. */
private fun fmtValue(v: Value?): String? = when (v) {
    null -> null
    is Value.Num -> v.v.toString()
    is Value.Text -> v.v
    is Value.Bool -> v.v.toString()
}

@Composable
private fun SectionTitle(t: String) = Text(t, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Line(label: String, value: String) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        if (maxWidth >= 900.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.4f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun fmtTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(ms))

@Composable
private fun fmtAge(ms: Long): String = when {
    ms < 60_000 -> stringResource(R.string.age_seconds, ms / 1000)
    ms < 3_600_000 -> stringResource(R.string.age_minutes, ms / 60_000)
    ms < 86_400_000 -> stringResource(R.string.age_hours, ms / 3_600_000)
    else -> stringResource(R.string.age_days, ms / 86_400_000)
}
