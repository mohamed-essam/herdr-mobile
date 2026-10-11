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
import dev.herdr.mobile.data.Server
import dev.herdr.mobile.data.ServerOpen
import dev.herdr.mobile.data.Settings
import dev.herdr.mobile.data.defaultServerName
import dev.herdr.mobile.data.pushEnabled
import dev.herdr.mobile.data.resolveServerOpen
import dev.herdr.mobile.data.savedAt
import dev.herdr.mobile.data.withRemoved
import dev.herdr.mobile.data.withRenamed
import dev.herdr.mobile.net.CompanionClient
import dev.herdr.mobile.push.EXTRA_PANE_ID
import dev.herdr.mobile.push.EXTRA_SERVER_ID
import dev.herdr.mobile.push.Notifications
import dev.herdr.mobile.ui.CompanionAddress
import dev.herdr.mobile.ui.ConnectScreen
import dev.herdr.mobile.ui.DashboardScreen
import dev.herdr.mobile.ui.DashboardViewModel
import dev.herdr.mobile.ui.FirstConnectionScreen
import dev.herdr.mobile.ui.ServerSheet
import dev.herdr.mobile.ui.theme.HerdrTheme
import java.util.UUID
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.UnifiedPush

/** Where the app is: setup (or adding a server), the first connection attempt, or the dashboard. */
private sealed interface Route {
    data object Loading : Route
    /** [adding]: from the dashboard's server sheet, so Back returns there. */
    data class Connect(val adding: Boolean = false) : Route
    data class FirstConnect(val serverId: String, val address: CompanionAddress, val push: Boolean, val adding: Boolean) : Route
    data object Dashboard : Route
}

/** One companion connection, the server it belongs to, and the view model fed by it. */
private class Session(val serverId: String?, val client: CompanionClient, val vm: DashboardViewModel)

/** A tapped notification's target; [serverId] is null on ones posted before multiple servers. */
private data class OpenTarget(val serverId: String?, val paneId: String)

private fun Intent.openTarget(): OpenTarget? =
    getStringExtra(EXTRA_PANE_ID)?.let { OpenTarget(getStringExtra(EXTRA_SERVER_ID), it) }

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private var permissionAsked = false

    /** Ask for the notification permission once, then register each server's UnifiedPush instance. */
    private fun enablePush(serverIds: List<String>) {
        if (serverIds.isEmpty()) return
        if (!permissionAsked) {
            permissionAsked = true
            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // UnifiedPush connector 3.3.3 dropped the 2.x `registerAppWithDialog` helper.
        // Pick (or reuse) a distributor first, then register once one is confirmed.
        // The instance is the server id, so each companion gets its own endpoint.
        UnifiedPush.tryUseCurrentOrDefaultDistributor(this) { success ->
            if (success) serverIds.forEach { UnifiedPush.register(this, it) }
        }
    }

    /** A notification to act on; cleared once handled. */
    private var openTarget by mutableStateOf<OpenTarget?>(null)

    // singleTop: a notification tapped while the app is open lands here instead
    // of recreating the activity (and its companion session).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.openTarget()?.let { openTarget = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settings = Settings(applicationContext)
        fun newSession(serverId: String?): Session {
            val client = CompanionClient()
            return Session(
                serverId,
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
        if (savedInstanceState == null) openTarget = intent.openTarget()

        setContent {
            var session by remember { mutableStateOf(newSession(null)) }
            var route by remember { mutableStateOf<Route>(Route.Loading) }
            var pushOn by remember { mutableStateOf(false) }
            val servers by settings.servers.collectAsState(initial = emptyList())
            val activeId by settings.activeServerId.collectAsState(initial = null)
            val active = servers.firstOrNull { it.id == activeId }
            var showServers by remember { mutableStateOf(false) }
            // A pane id handed to the dashboard (from a notification), on the active server.
            var dashboardOpen by remember { mutableStateOf<String?>(null) }
            // What the connect screen shows again after a failed first attempt.
            var draftInput by remember { mutableStateOf("") }
            var draftPush by remember { mutableStateOf(false) }

            /** Drops the current socket and starts a fresh session on [server] (or none). */
            fun connectTo(server: Server?) {
                session.client.close()
                session = newSession(server?.id)
                // An unparseable host (OkHttp is stricter than our field) just never connects.
                server?.let { runCatching { session.vm.start(it.url) } }
            }

            fun switchTo(server: Server) {
                if (server.id == session.serverId) return
                lifecycleScope.launch { settings.setActiveServer(server.id) }
                connectTo(server)
                route = Route.Dashboard
            }

            fun removeServer(server: Server) {
                val left = servers.withRemoved(server.id, activeId)
                if (pushOn) UnifiedPush.unregister(this@MainActivity, server.id)
                Notifications.cancelServer(this@MainActivity, server.id)
                lifecycleScope.launch {
                    settings.setServers(left.servers, left.activeId)
                    settings.removePushEndpoint(server.id)
                }
                if (server.id != session.serverId) return
                showServers = false
                val next = left.servers.firstOrNull { it.id == left.activeId }
                connectTo(next)
                if (next == null) { draftInput = ""; draftPush = false; route = Route.Connect() }
            }

            LaunchedEffect(Unit) {
                settings.migrate()
                val all = settings.servers.first()
                val stored = settings.activeServerId.first()
                val start = all.firstOrNull { it.id == stored } ?: all.firstOrNull()
                if (start == null) {
                    route = Route.Connect()
                    return@LaunchedEffect
                }
                if (start.id != stored) settings.setActiveServer(start.id)
                connectTo(start)
                route = Route.Dashboard
                if (pushEnabled(settings.pushEnabledChoice.first(), hasCompanionUrl = true)) {
                    enablePush(all.map { it.id })
                    pushOn = true
                }
            }

            // Forward the session's own server's endpoint to its companion, so it
            // knows where to deliver notifications. Keyed on the session: a switch
            // never sends one server's endpoint to another.
            LaunchedEffect(session, pushOn) {
                val id = session.serverId ?: return@LaunchedEffect
                if (!pushOn) return@LaunchedEffect
                settings.pushEndpoint(id).filterNotNull().collect { endpoint -> session.vm.registerPush(endpoint) }
            }

            // A tapped notification: switch to its server if needed, then open the pane.
            // It waits through startup and setup, and is handled once on the dashboard.
            LaunchedEffect(openTarget, route) {
                val t = openTarget ?: return@LaunchedEffect
                if (route != Route.Dashboard) return@LaunchedEffect
                val all = settings.servers.first()
                when (val o = resolveServerOpen(t.serverId, session.serverId, all)) {
                    ServerOpen.Stay -> dashboardOpen = t.paneId
                    is ServerOpen.Switch -> { switchTo(o.server); dashboardOpen = t.paneId }
                    ServerOpen.Unknown -> {}
                }
                openTarget = null
            }

            HerdrTheme {
                when (val r = route) {
                    Route.Loading -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
                    is Route.Connect -> ConnectScreen(
                        draftInput,
                        draftPush,
                        showPush = !(r.adding && pushOn),
                        duplicateOf = { a -> servers.savedAt(a.url)?.name },
                        onBack = if (r.adding) ({ route = Route.Dashboard }) else null,
                    ) { input, address, push ->
                        draftInput = input
                        draftPush = push
                        val id = UUID.randomUUID().toString()
                        session.client.close()
                        session = newSession(id)
                        // An unparseable host just never connects; the first-connection screen then offers Back.
                        runCatching { session.vm.start(address.url) }
                        route = Route.FirstConnect(id, address, push || (r.adding && pushOn), r.adding)
                    }
                    is Route.FirstConnect -> {
                        // Drop the failed attempt; a fresh session keeps a stale socket and
                        // its collectors from feeding the next one. Adding a server puts the
                        // previous server's session back behind the connect screen.
                        fun back() {
                            connectTo(if (r.adding) active else null)
                            route = Route.Connect(adding = r.adding)
                        }
                        val connected by session.vm.connected.collectAsState()
                        BackHandler(enabled = !connected) { back() }
                        FirstConnectionScreen(
                            session.vm,
                            r.address.hostPort,
                            onConnected = {
                                // Only now is the address worth keeping: it joins the list as the active server.
                                val server = Server(r.serverId, defaultServerName(r.address.url), r.address.url)
                                lifecycleScope.launch {
                                    settings.addServer(server)
                                    settings.setPushEnabled(r.push)
                                }
                                if (r.push) {
                                    enablePush(if (pushOn) listOf(server.id) else servers.map { it.id } + server.id)
                                    pushOn = true
                                }
                            },
                            onContinue = { route = Route.Dashboard },
                            onBack = ::back,
                        )
                    }
                    Route.Dashboard -> {
                        key(session) {
                            DashboardScreen(
                                session.vm, dashboardOpen, { dashboardOpen = null }, active?.url,
                                serverName = active?.name,
                                onServers = { showServers = true },
                            )
                        }
                        if (showServers) ServerSheet(
                            servers,
                            activeId,
                            onSwitch = { showServers = false; switchTo(it) },
                            onRename = { s, name ->
                                lifecycleScope.launch { settings.setServers(servers.withRenamed(s.id, name), activeId) }
                            },
                            onRemove = ::removeServer,
                            onAdd = {
                                showServers = false
                                draftInput = ""
                                draftPush = false
                                route = Route.Connect(adding = true)
                            },
                            onDismiss = { showServers = false },
                        )
                    }
                }
            }
        }
    }
}
