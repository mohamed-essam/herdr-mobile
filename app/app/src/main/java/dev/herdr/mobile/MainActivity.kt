package dev.herdr.mobile

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.herdr.mobile.data.PaneRepository
import dev.herdr.mobile.data.Settings
import dev.herdr.mobile.net.CompanionClient
import dev.herdr.mobile.ui.DashboardScreen
import dev.herdr.mobile.ui.DashboardViewModel
import dev.herdr.mobile.ui.theme.HerdrTheme
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.UnifiedPush

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // UnifiedPush connector 3.3.3 dropped the 2.x `registerAppWithDialog` helper.
        // Pick (or reuse) a distributor first, then register once one is confirmed.
        UnifiedPush.tryUseCurrentOrDefaultDistributor(this) { success ->
            if (success) UnifiedPush.register(this)
        }

        val settings = Settings(applicationContext)
        val vm = DashboardViewModel(CompanionClient(), PaneRepository())
        val initialPane = intent.getStringExtra("paneId")

        setContent {
            var url by remember { mutableStateOf<String?>(null) }
            var loaded by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                val stored = settings.companionUrl.first()
                url = stored
                loaded = true
                if (stored != null) vm.start(stored)
            }

            // Forward whatever push endpoint the UnifiedPush receiver saves to the
            // companion, so it knows where to deliver notifications for this device.
            LaunchedEffect(Unit) {
                settings.pushEndpoint.filterNotNull().collect { endpoint -> vm.registerPush(endpoint) }
            }

            HerdrTheme {
                if (!loaded) {
                    Surface {}
                } else if (url == null) {
                    OnboardUrl { entered ->
                        lifecycleScope.launch { settings.setCompanionUrl(entered) }
                        url = entered
                        vm.start(entered)
                    }
                } else {
                    DashboardScreen(vm, initialPane)
                }
            }
        }
    }
}

@Composable
private fun OnboardUrl(onConnect: (String) -> Unit) {
    var text by remember { mutableStateOf("ws://") }
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Connect to your herdr companion", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Companion URL") },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            Button(
                onClick = { if (text.isNotBlank()) onConnect(text) },
                modifier = Modifier.padding(top = 16.dp),
            ) { Text("Connect") }
        }
    }
}
