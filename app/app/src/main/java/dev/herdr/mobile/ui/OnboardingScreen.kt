package dev.herdr.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdr.mobile.ui.theme.Herdr
import dev.herdr.mobile.ui.theme.HerdrRadius
import dev.herdr.mobile.ui.theme.HerdrType
import kotlinx.coroutines.delay

/**
 * Setup (spec screen 01): run the companion, enter its tailnet address, opt
 * into push. Connect is pinned to the bottom, above the keyboard.
 */
@Composable
fun ConnectScreen(
    initialInput: String,
    initialPush: Boolean,
    onConnect: (input: String, address: CompanionAddress, push: Boolean) -> Unit,
) {
    val c = Herdr.colors
    var input by rememberSaveable { mutableStateOf(initialInput) }
    var push by rememberSaveable { mutableStateOf(initialPush) }
    val address = parseCompanionAddress(input)
    val steps = connectStepStates(hostEntered = address != null)
    fun connect() { address?.let { onConnect(input.trim(), it, push) } }

    Column(Modifier.fillMaxSize().background(c.crust).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 48.dp, bottom = 32.dp)) {
                Wordmark(style = HerdrType.wordmark.copy(fontSize = 28.sp))
                Spacer(Modifier.height(8.dp))
                Text("One terminal for the whole herd, in your pocket.", style = HerdrType.body, color = c.subtext1)
            }
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Step(1, steps[0]) {
                    Text("Run the companion on your host", style = HerdrType.title, color = c.text)
                    CommandBlock(COMPANION_COMMAND)
                }
                Step(2, steps[1]) {
                    Text("Enter its tailnet address", style = HerdrType.title, color = c.text)
                    AddressField(input, address, onChange = { input = it }, onGo = ::connect)
                    if (input.isNotBlank() && address == null) {
                        Text("That doesn't look like a host or ws:// address.", style = HerdrType.small, color = c.red)
                    } else {
                        Text("Keep it on your tailnet. The companion has no auth.", style = HerdrType.small, color = c.overlay2)
                    }
                }
                Step(3, steps[2], dim = steps[2] == StepState.Upcoming && !push) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Notifications", style = HerdrType.title, color = c.text)
                            Text("UnifiedPush · optional", style = HerdrType.small, color = c.overlay2)
                        }
                        Switch(
                            checked = push,
                            onCheckedChange = { push = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = c.crust,
                                checkedTrackColor = c.mauve,
                                checkedBorderColor = Color.Transparent,
                                uncheckedThumbColor = c.overlay0,
                                uncheckedTrackColor = c.surface0,
                                uncheckedBorderColor = Color.Transparent,
                            ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        PrimaryButton(
            "Connect",
            onClick = ::connect,
            enabled = address != null,
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        )
    }
}

/** A numbered setup step: a 24dp marker (✓ done, mauve current, outlined upcoming) beside [content]. */
@Composable
private fun Step(number: Int, state: StepState, dim: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = Herdr.colors
    Row(Modifier.fillMaxWidth().alpha(if (dim) 0.6f else 1f)) {
        val marker = Modifier.size(24.dp).clip(CircleShape)
        Box(
            when (state) {
                StepState.Done -> marker.background(c.green)
                StepState.Current -> marker.background(c.mauve)
                StepState.Upcoming -> marker.border(1.5.dp, c.surface2, CircleShape)
            },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (state == StepState.Done) "✓" else "$number",
                style = HerdrType.meta.copy(fontWeight = HerdrType.badge.fontWeight),
                color = if (state == StepState.Upcoming) c.text else c.crust,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** The companion command on base with a "copy" action. */
@Composable
private fun CommandBlock(command: String) {
    val c = Herdr.colors
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    Row(
        Modifier.fillMaxWidth().clip(HerdrRadius.field).background(c.base).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(command, style = HerdrType.meta.copy(lineHeight = 16.5.sp), color = c.subtext1, modifier = Modifier.weight(1f))
        Text(
            if (copied) "copied" else "copy",
            style = HerdrType.meta,
            color = c.mauve,
            modifier = Modifier.clickable {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.setPrimaryClip(ClipData.newPlainText("herdr companion command", command))
                copied = true
            },
        )
    }
}

/** Mono address field: the user types the host; the `ws://` and `:8787` it adds show dimmed. */
@Composable
private fun AddressField(input: String, address: CompanionAddress?, onChange: (String) -> Unit, onGo: () -> Unit) {
    val c = Herdr.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val style = HerdrType.code.copy(fontSize = 15.sp, lineHeight = 20.sp, color = c.text)
    val showScheme = !(address?.hasScheme ?: input.contains("://"))
    val showPort = address == null || (!address.hasPort && !address.hasScheme)
    BasicTextField(
        value = input,
        onValueChange = onChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(c.mauve),
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(HerdrRadius.tile)
                    .background(c.base)
                    .border(1.5.dp, if (focused) c.mauve else c.surface0, HerdrRadius.tile)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showScheme) Text("ws://", style = style, color = c.overlay0)
                Box(Modifier.weight(1f)) {
                    if (input.isEmpty()) Text("100.x.y.z", style = style, color = c.surface2)
                    inner()
                }
                if (showPort) Text(":$DEFAULT_COMPANION_PORT", style = style, color = c.overlay0)
            }
        },
    )
}

/**
 * The first connection (spec screen 02): a connecting state, then a summary
 * of the herd once the companion answers. After a while without an answer,
 * offers to go back and fix the address.
 */
@Composable
fun FirstConnectionScreen(vm: DashboardViewModel, hostPort: String, onConnected: () -> Unit, onContinue: () -> Unit, onBack: () -> Unit) {
    val c = Herdr.colors
    val connected by vm.connected.collectAsState()
    val repos by vm.repoTree.collectAsState()
    var everConnected by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(connected) {
        if (connected && !everConnected) { everConnected = true; onConnected() }
    }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (!everConnected) { delay(500); elapsed = System.currentTimeMillis() - start }
    }
    val phase = firstConnectPhase(everConnected, elapsed)
    val ok = phase == FirstConnectPhase.Connected

    Box(Modifier.fillMaxSize().background(c.crust).statusBarsPadding().navigationBarsPadding()) {
        val glow = if (ok) c.green else c.yellow
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = 92.dp).size(240.dp)
                .background(Brush.radialGradient(listOf(glow.copy(alpha = 0.18f), Color.Transparent))),
        )
        Column(
            Modifier.fillMaxWidth().padding(top = 160.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .drawBehind {
                        drawCircle(glow.copy(alpha = 0.12f), radius = size.minDimension / 2 + 12.dp.toPx(), center = Offset(size.width / 2, size.height / 2))
                    }
                    .clip(CircleShape)
                    .background(if (ok) c.green else c.surface0),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (ok) "✓" else spinnerFrame(),
                    style = if (ok) HerdrType.display.copy(fontSize = 32.sp) else HerdrType.stat,
                    color = if (ok) c.crust else c.yellow,
                )
            }
            Spacer(Modifier.height(32.dp))
            Text(if (ok) "Connected" else "Connecting", style = HerdrType.display, color = c.text)
            Spacer(Modifier.height(8.dp))
            Text(hostPort, style = HerdrType.badge.copy(fontWeight = null), color = c.overlay2)
            if (ok) {
                val s = herdSummary(repos)
                Spacer(Modifier.height(32.dp))
                Column(
                    Modifier.padding(horizontal = 24.dp).fillMaxWidth().clip(HerdrRadius.card).background(c.base).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(herdLine(s), style = HerdrType.small, color = c.overlay2)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        HerdCount(s.needYou, "need you", c.red)
                        HerdCount(s.working, "working", c.yellow)
                        HerdCount(s.done, "done", c.green)
                    }
                }
            } else if (phase == FirstConnectPhase.Slow) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "Still trying. Check that the companion is running and this phone is on your tailnet.",
                    style = HerdrType.small,
                    color = c.overlay2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 40.dp),
                )
            }
        }
        val bottom = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
        when (phase) {
            FirstConnectPhase.Connected -> PrimaryButton("See who needs you", onClick = onContinue, modifier = bottom)
            FirstConnectPhase.Slow -> SecondaryButton("Back", onClick = onBack, modifier = bottom, height = 52.dp, shape = HerdrRadius.card)
            FirstConnectPhase.Connecting -> {}
        }
    }
}

@Composable
private fun HerdCount(n: Int, label: String, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$n", style = HerdrType.stat, color = color)
        Text(label, style = HerdrType.caption, color = Herdr.colors.overlay2)
    }
}
