package com.qorvexa.recorder

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qorvexa.recorder.ui.AppViewModel
import com.qorvexa.recorder.ui.navigation.QorvexaApp
import com.qorvexa.recorder.ui.theme.QorvexaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()
    /** Deep-link target (e.g. "settings" from the floating panel). */
    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Qorvexa)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingRoute.value = intent?.getStringExtra(EXTRA_ROUTE)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            QorvexaTheme(settings.theme) {
                QorvexaApp(vm, pendingRoute.value) { pendingRoute.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingRoute.value = intent.getStringExtra(EXTRA_ROUTE)
    }

    override fun onResume() {
        super.onResume()
        vm.refreshDevice()
        vm.reloadLibrary()
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}
