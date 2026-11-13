package app.danim.automotive.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    // Runtime grants: notification (best effort) and the dangerous AAOS speed/energy permissions. A denial is not
    // hidden: the affected signals are reported `permission_denied` in diagnostics.
    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onStart() {
        super.onStart()
        vm.resumeIfWanted() // guarded foreground recovery of a collector lost to force-stop/process death
    }

    private var permissionsRequested = false

    /** Requests the runtime grants once, the first time the real pairing/collection path is entered. */
    private fun requestCollectionPermissions() {
        if (permissionsRequested) return
        permissionsRequested = true
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            add("android.car.permission.CAR_SPEED")
            add("android.car.permission.CAR_ENERGY")
        }.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by vm.ui.collectAsStateWithLifecycle()
            // The setup screen and the labelled mock demo need no vehicle permission, so the dialog appears only
            // once pairing starts or a paired car is shown. Grants are re-checked by the collector on every heartbeat.
            LaunchedEffect(state.phase) {
                if (state.phase == Phase.PAIRING || state.phase == Phase.PAIRED) requestCollectionPermissions()
            }
            DanimTheme { DanimScreen(state, vm) }
        }
    }
}
