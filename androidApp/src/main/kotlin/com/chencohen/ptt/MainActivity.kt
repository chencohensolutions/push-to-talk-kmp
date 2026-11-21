package com.chencohen.ptt

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.chencohen.ptt.enrollment.parseEnrollmentLink
import com.chencohen.ptt.ui.EnrollmentWizard
import com.chencohen.ptt.ui.TalkTheme
import com.chencohen.ptt.ui.home.HomeApp

class MainActivity : ComponentActivity() {
    private val viewModel: PttViewModel by viewModels()

    /** Set when an enrollment link arrives, to open the wizard on it. */
    private var linkOpened by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only on a fresh start: after a rotation the link was handled already.
        if (savedInstanceState == null) openLink(intent)

        setContent {
            TalkTheme {
                // Android 17 blocks connections to local network addresses (a home server) without this.
                val localNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission(LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
                        localNetwork.launch(LOCAL_NETWORK)
                    }
                }
                // Without an identity the phone has to be registered first.
                var showWizard by rememberSaveable { mutableStateOf(!viewModel.connection.currentState.enrolled) }
                // An enrollment QR code scanned with the camera: register (again) with its code.
                LaunchedEffect(linkOpened) {
                    if (linkOpened) {
                        showWizard = true
                        linkOpened = false
                    }
                }
                if (showWizard) {
                    EnrollmentWizard(
                        controller = viewModel.enrollment,
                        onOpenApp = {
                            showWizard = false
                            viewModel.enrollment.reset()
                            viewModel.connection.connect()
                        },
                    )
                } else {
                    HomeApp(
                        connection = viewModel.connection,
                        settings = viewModel.settings,
                        onRegisterAgain = {
                            viewModel.enrollment.reset()
                            showWizard = true
                        },
                    )
                }
            }
        }
    }

    // Each time the app comes to the front it checks the connection (and finds the server if it moved); in the back
    // it stops checking again while offline. Staying connected in the background is a setting in phase 5.
    override fun onStart() {
        super.onStart()
        if (viewModel.connection.currentState.enrolled) viewModel.connection.connect()
    }

    override fun onStop() {
        viewModel.connection.stop()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openLink(intent)
    }

    private fun openLink(intent: Intent?) {
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        val link = parseEnrollmentLink(data) ?: return
        viewModel.enrollment.openLink(link)
        linkOpened = true
    }
}

/** `Manifest.permission.ACCESS_LOCAL_NETWORK`, API 37. */
private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
