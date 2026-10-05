package dev.herdr.mobile

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import dev.herdr.mobile.data.PaneRepository
import dev.herdr.mobile.data.Settings
import dev.herdr.mobile.data.pushEnabled
import dev.herdr.mobile.net.CompanionClient
import dev.herdr.mobile.ui.CompanionAddress
import dev.herdr.mobile.ui.ConnectScreen
import dev.herdr.mobile.ui.DashboardScreen
import dev.herdr.mobile.ui.DashboardViewModel
import dev.herdr.mobile.ui.FirstConnectionScreen
import dev.herdr.mobile.ui.theme.HerdrTheme
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.UnifiedPush

/** Where the app is: setup, the first connection attempt, or the dashboard. */
private sealed interface Route {
    data object Loading : Route
    data object Connect : Route
    data class FirstConnect(val address: CompanionAddress, val push: Boolean) : Route
    data object Dashboard : Route
}

/** One companion connection and the view model fed by it. */
private class Session(val client: CompanionClient, val vm: DashboardViewModel)

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private var pushRegistered = false

    /** Ask for the notification permission and register with a UnifiedPush distributor, once. */
    private fun enablePush() {
        if (pushRegistered) return
        pushRegistered = true
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        // UnifiedPush connector 3.3.3 dropped the 2.x `registerAppWithDialog` helper.
        // Pick (or reuse) a distributor first, then register once one is confirmed.
        UnifiedPush.tryUseCurrentOrDefaultDistributor(this) { success ->
            if (success) UnifiedPush.register(this)
        }
    }

    /** A pane to open, from a tapped notification; cleared once the dashboard handles it. */
    private var openRequest by mutableStateOf<String?>(null)

    // singleTop: a notification tapped while the app is open lands here instead
    // of recreating the activity (and its companion session).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("paneId")?.let { openRequest = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settings = Settings(applicationContext)
        fun newSession(): Session {
            val client = CompanionClient()
            return Session(
                client,
                DashboardViewModel(
                    client,
                    PaneRepository(),
                    fontSizeStore = settings.terminalFontSize,
                    persistFontSize = { px -> lifecycleScope.launch { settings.setTerminalFontSize(px) } },
                    recentAgentsStore = settings.recentAgents,
                    persistRecentAgent = { name -> lifecycleScope.launch { settings.addRecentAgent(name) } },
                ),
            )
        }
        // Only a fresh launch honours the extra: a recreated activity still carries
        // the launch intent, and must not reopen a pane the user has since left.
        if (savedInstanceState == null) openRequest = intent.getStringExtra("paneId")

        setContent {
            var session by remember { mutableStateOf(newSession()) }
            var route by remember { mutableStateOf<Route>(Route.Loading) }
            var pushOn by remember { mutableStateOf(false) }
            var companionUrl by remember { mutableStateOf<String?>(null) }
            // What the connect screen shows again after a failed first attempt.
            var draftInput by remember { mutableStateOf("") }
            var draftPush by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                val stored = settings.companionUrl.first()
                if (stored == null) {
                    route = Route.Connect
                    return@LaunchedEffect
                }
                companionUrl = stored
                route = Route.Dashboard
                session.vm.start(stored)
                if (pushEnabled(settings.pushEnabledChoice.first(), hasCompanionUrl = true)) {
                    enablePush()
                    pushOn = true
                }
            }

            // Forward whatever push endpoint the UnifiedPush receiver saves to the
            // companion, so it knows where to deliver notifications for this device.
            LaunchedEffect(session, pushOn) {
                if (!pushOn) return@LaunchedEffect
                settings.pushEndpoint.filterNotNull().collect { endpoint -> session.vm.registerPush(endpoint) }
            }

            HerdrTheme {
                when (val r = route) {
                    Route.Loading -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
                    Route.Connect -> ConnectScreen(draftInput, draftPush) { input, address, push ->
                        draftInput = input
                        draftPush = push
                        // An unparseable host (OkHttp is stricter than our field) just never connects;
                        // the first-connection screen then offers Back.
                        runCatching { session.vm.start(address.url) }
                        route = Route.FirstConnect(address, push)
                    }
                    is Route.FirstConnect -> {
                        // Drop the failed attempt; a fresh session keeps a stale socket and
                        // its collectors from feeding the next one.
                        fun back() {
                            session.client.close()
                            session = newSession()
                            route = Route.Connect
                        }
                        val connected by session.vm.connected.collectAsState()
                        BackHandler(enabled = !connected) { back() }
                        FirstConnectionScreen(
                            session.vm,
                            r.address.hostPort,
                            onConnected = {
                                // Only now is the address worth keeping: later launches go
                                // straight to the dashboard with it.
                                lifecycleScope.launch {
                                    settings.setCompanionUrl(r.address.url)
                                    settings.setPushEnabled(r.push)
                                }
                                if (r.push) { enablePush(); pushOn = true }
                            },
                            onContinue = { companionUrl = r.address.url; route = Route.Dashboard },
                            onBack = ::back,
                        )
                    }
                    Route.Dashboard -> DashboardScreen(session.vm, openRequest, { openRequest = null }, companionUrl)
                }
            }
        }
    }
}
