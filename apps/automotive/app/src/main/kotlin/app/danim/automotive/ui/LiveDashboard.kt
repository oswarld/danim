package app.danim.automotive.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.danim.automotive.R
import app.danim.core.Qr
import app.danim.core.Signal
import java.text.DateFormat
import java.util.Date

/**
 * The paired car's glanceable dashboard: this car's own latest readings from the running collector, the
 * upload state, and the phone hand-off to the owner site. It sits above the collector diagnostics on the
 * paired screen. Values come only from [Diagnostics.signals]; a missing reading shows why it is missing.
 */
@Composable
internal fun LiveDashboard(d: Diagnostics, siteUrl: String, afterTiles: @Composable () -> Unit = {}) {
    val running = d.collectorState == "running"
    ShowcaseTitle(stringResource(if (running) R.string.live_title_running else R.string.live_title_stopped))
    ShowcaseNote(stringResource(R.string.live_provenance), tag = "liveProvenance")

    val soc = LiveModel.number(d.signals[Signal.BATTERY_SOC_PERCENT], "%")
    val range = LiveModel.number(d.signals[Signal.RANGE_KM], "km")
    val odo = LiveModel.number(d.signals[Signal.ODOMETER_KM], "km")
    val (motionTile, motion) = LiveModel.motion(d.signals[Signal.VEHICLE_MOTION])
    val (chargeTile, charge) = LiveModel.charging(d.signals[Signal.CHARGING_STATE])

    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max).testTag("liveTilesPrimary"), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ShowcaseTile(stringResource(R.string.showcase_battery), tileValue(soc), tileSub(soc), tag = "liveSoc", tinted = true)
        ShowcaseTile(stringResource(R.string.showcase_range), tileValue(range), tileSub(range), tag = "liveRange")
        ShowcaseTile(stringResource(R.string.live_motion), motionLabel(motionTile, motion), tileSub(motionTile), tag = "liveMotion", big = false)
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max).testTag("liveTilesSecondary"), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ShowcaseTile(stringResource(R.string.showcase_odometer), tileValue(odo), tileSub(odo), tag = "liveOdometer", big = false)
        ShowcaseTile(stringResource(R.string.live_charging), chargingLabel(chargeTile, charge), tileSub(chargeTile), tag = "liveCharging", big = false)
        val uploaded = if (d.lastUploadAtMs == 0L) stringResource(R.string.never) else fmtClock(d.lastUploadAtMs)
        ShowcaseTile(
            stringResource(R.string.live_sync), stringResource(R.string.live_sync_value, d.acked),
            stringResource(R.string.live_sync_sub, uploaded, d.queued), tag = "liveSync", big = false,
        )
    }

    afterTiles()

    // Phone hand-off: the owner site only (no credential, no pairing code); the web shows the same records.
    if (siteUrl.startsWith("https://")) {
        val qr = remember(siteUrl) { Qr.matrix(siteUrl) }
        ShowcaseCard(Modifier.fillMaxWidth(), tag = "livePhoneCard") {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                QrCode(qr, siteUrl)
                Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShowcaseTitle(stringResource(R.string.live_phone_title))
                    Text(siteUrl, style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("livePhoneUrl"))
                    ShowcaseNote(stringResource(R.string.live_phone_note))
                }
            }
        }
    }
}

@Composable
private fun tileValue(t: LiveModel.Tile): String = t.text ?: reasonLabel(t.kind)

@Composable
private fun reasonLabel(kind: LiveModel.Kind): String = when (kind) {
    LiveModel.Kind.VALUE -> ""
    LiveModel.Kind.NO_DATA -> stringResource(R.string.live_no_data)
    LiveModel.Kind.UNSUPPORTED -> stringResource(R.string.live_unsupported)
    LiveModel.Kind.DENIED -> stringResource(R.string.live_denied)
    LiveModel.Kind.UNAVAILABLE -> stringResource(R.string.unknown)
    LiveModel.Kind.ERROR -> stringResource(R.string.live_error)
}

/** Read time; another day's reading carries its date, and one past the freshness window is marked stale. */
@Composable
private fun tileSub(t: LiveModel.Tile): String {
    val at = t.observedAtMs ?: return stringResource(R.string.live_waiting)
    val now = System.currentTimeMillis()
    val read = stringResource(R.string.live_observed_at, if (LiveModel.sameDay(at, now)) fmtClock(at) else fmtDateClock(at))
    return if (LiveModel.isStale(at, now)) stringResource(R.string.live_stale, read) else read
}

@Composable
private fun motionLabel(t: LiveModel.Tile, m: LiveModel.Motion?): String = when (m) {
    LiveModel.Motion.MOVING -> stringResource(R.string.live_motion_moving)
    LiveModel.Motion.PARKED -> stringResource(R.string.live_motion_parked)
    LiveModel.Motion.UNKNOWN -> stringResource(R.string.unknown)
    null -> reasonLabel(t.kind)
}

@Composable
private fun chargingLabel(t: LiveModel.Tile, c: LiveModel.Charging?): String = when (c) {
    LiveModel.Charging.CHARGING -> stringResource(R.string.showcase_charging_charging)
    LiveModel.Charging.COMPLETE -> stringResource(R.string.live_charge_complete)
    LiveModel.Charging.IDLE -> stringResource(R.string.showcase_charging_idle)
    LiveModel.Charging.DISCHARGING -> stringResource(R.string.live_charge_discharging)
    LiveModel.Charging.UNKNOWN -> stringResource(R.string.unknown)
    null -> reasonLabel(t.kind)
}

private fun fmtClock(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

private fun fmtDateClock(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))
