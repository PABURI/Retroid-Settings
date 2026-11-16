@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.retroidsettings.lineage

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val prefs = Prefs(this)
        if (prefs.showNotification) ControlService.start(this)
        handleTileLongPress(intent)
        setContent { AppTheme { MainScreen(prefs) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTileLongPress(intent)
    }

    /**
     * Long-press on a quick settings tile opens this screen with the tile's component:
     * jump to the matching card, or hand over to the stock settings for LED / controller layout.
     */
    private fun handleTileLongPress(intent: Intent?) {
        if (intent?.action != "android.service.quicksettings.action.QS_TILE_PREFERENCES") return
        val comp = intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java) ?: return
        when (comp.className.substringAfterLast('.')) {
            "TdpTile" -> Jump.target.value = "tdp"
            "FanTile" -> Jump.target.value = "fan"
            "DensityTile" -> Jump.target.value = "density"
            "FullscreenTile", "RotationTile" -> Jump.target.value = "fullscreen"
            "RefreshTile" -> {
                runCatching { startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) }
                finish()
            }
            "LayoutTile" -> {
                runCatching { startActivity(Intent().setComponent(ComponentName("org.lineageos.settings.ayn", "org.lineageos.settings.ayn.AynSettingsActivity"))) }
                finish()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        UiVisibility.started()
    }

    override fun onStop() {
        super.onStop()
        visible = false
        // The daemon runs on its own; once none of our screens is visible the adb link is dropped,
        // so the system's "Wireless debugging connected" notification goes away too.
        if (!isChangingConfigurations) UiVisibility.stopped(this)
    }

    companion object {
        /** True while the main screen is on screen; the live-reading loop only talks to adb then. */
        @Volatile var visible = false
    }
}

/** Card to scroll to after a tile long-press. */
object Jump { val target = androidx.compose.runtime.mutableStateOf<String?>(null) }

/** Restarts the root daemon with the current settings (call after any fan/TDP change). */
private fun applyControl(ctx: Context) {
    Thread { Status.daemonRunning = Daemon.update(ctx.applicationContext) }.start()
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
}

@Composable
private fun MainScreen(prefs: Prefs) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    // While the screen is open: keep root, make sure the daemon runs, and read temperatures every 2 s.
    LaunchedEffect(Unit) {
        var daemonChecked = false
        while (true) {
            withContext(Dispatchers.IO) {
                if (MainActivity.visible) {
                    // Readings come from the daemon's status file; adb is only needed for the rest.
                    Hardware.read(ctx)?.let { Status.reading = it }
                    if (RootShell.ensure(ctx) && !daemonChecked) {
                        Status.daemonRunning = Daemon.ensureCurrent(ctx)   // restarts only for a new script
                        Battery.loadCapacity(ctx)
                        daemonChecked = true
                    }
                }
            }
            tick++
            delay(2000)
        }
    }

    val wide = LocalConfiguration.current.screenWidthDp >= 840
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.safeDrawingPadding().padding(horizontal = 24.dp)) {
            Header(prefs, tick)
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        TemperatureCard(prefs, tick); TdpCard(prefs, tick); MemoryCard(tick); FanCard(prefs); BatteryCard(tick)
                        Spacer(Modifier.height(24.dp))
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ControllerCard(); DensityCard(); FullscreenCard(prefs); Spacer(Modifier.height(24.dp))
                    }
                }
            } else {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TemperatureCard(prefs, tick); TdpCard(prefs, tick); MemoryCard(tick); FanCard(prefs); BatteryCard(tick); ControllerCard(); DensityCard(); FullscreenCard(prefs)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun Header(prefs: Prefs, tick: Int) {
    @Suppress("UNUSED_EXPRESSION") tick
    var showConnection by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Retroid Settings", Modifier.weight(1f), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
        StatusPill(tick) { showConnection = true }
    }
    if (showConnection) {
        AlertDialog(
            onDismissRequest = { showConnection = false },
            icon = { Icon(Icons.Rounded.Link, null) },
            title = { Text(tr(R.string.connection)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { ConnectionPanel(prefs, tick) } },
            confirmButton = { TextButton(onClick = { showConnection = false }) { Text(tr(R.string.close)) } }
        )
    }
}

@Composable
private fun StatusPill(tick: Int, onClick: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") tick
    val ok = RootShell.isRoot
    val busy = !ok && !RootShell.needsPairing && (RootShell.state == tr(R.string.rs_connecting) || RootShell.state == tr(R.string.rs_switching))
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                busy -> LoadingIndicator(Modifier.size(24.dp))
                ok -> Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                else -> Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
            Spacer(Modifier.width(8.dp))
            Text(when { ok -> tr(R.string.st_root); RootShell.needsPairing -> tr(R.string.st_pair); busy -> tr(R.string.st_connecting); else -> tr(R.string.st_not_connected) },
                style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.Settings, tr(R.string.connection_settings), Modifier.size(18.dp))
        }
    }
}

// ---------------------------------------------------------------- cards

@Composable
private fun SectionCard(icon: ImageVector, title: String, anchor: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val requester = remember { BringIntoViewRequester() }
    val target = Jump.target.value
    LaunchedEffect(target) {
        if (anchor != null && target == anchor) { delay(350); requester.bringIntoView(); Jump.target.value = null }
    }
    Card(
        modifier = Modifier.bringIntoViewRequester(requester),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(icon, null, Modifier.padding(8.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
private fun TemperatureCard(prefs: Prefs, tick: Int) {
    @Suppress("UNUSED_EXPRESSION") tick
    val r = Status.reading
    SectionCard(Icons.Rounded.Thermostat, tr(R.string.temperature)) {
        if (r == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(); Spacer(Modifier.width(12.dp))
                Text(tr(R.string.reading_temps), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TempStat("CPU", r.cpuC); TempStat("GPU", r.gpuC); TempStat(tr(R.string.battery), r.battC)
            }
            val level = r.fanLevel.coerceAtLeast(0)
            val max = Hardware.fanMax.coerceAtLeast(1)
            val who = when (prefs.fanMode) {
                FanMode.AUTO -> tr(R.string.fan_auto_long)
                FanMode.SMOOTH -> tr(R.string.fan_smooth)
                FanMode.MANUAL -> tr(R.string.fan_manual)
                FanMode.TARGET -> tr(R.string.fan_keep, prefs.targetTemp)
            }
            Text(tr(R.string.fan_status, level, max, who), style = MaterialTheme.typography.titleMedium)
            LinearWavyProgressIndicator(progress = { level / max.toFloat() }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TempStat(label: String, c: Double) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${c.roundToInt()}°", style = MaterialTheme.typography.displayMedium,
            color = when {
                c >= 80 -> MaterialTheme.colorScheme.error
                c >= 65 -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.primary
            })
    }
}

@Composable
private fun FanCard(prefs: Prefs) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(prefs.fanMode) }
    SectionCard(Icons.Rounded.Air, tr(R.string.fan), anchor = "fan") {
        ConnectedChoice(
            options = listOf(FanMode.AUTO to tr(R.string.fan_system), FanMode.SMOOTH to tr(R.string.fan_smooth), FanMode.MANUAL to tr(R.string.fan_manual), FanMode.TARGET to tr(R.string.fan_target)),
            selected = mode
        ) { mode = it; prefs.fanMode = it; applyControl(ctx) }
        Text(
            when (mode) {
                FanMode.AUTO -> tr(R.string.fan_desc_auto)
                FanMode.SMOOTH -> tr(R.string.fan_desc_smooth)
                FanMode.MANUAL -> tr(R.string.fan_desc_manual)
                FanMode.TARGET -> tr(R.string.fan_desc_target)
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AnimatedVisibility(mode == FanMode.SMOOTH || mode == FanMode.TARGET) {
            LabeledSlider(tr(R.string.smoothness), prefs.smoothness, 1..5, {
                listOf(tr(R.string.sm1), tr(R.string.sm2), tr(R.string.sm3), tr(R.string.sm4), tr(R.string.sm5))[it - 1]
            }) { prefs.smoothness = it; applyControl(ctx) }
        }
        AnimatedVisibility(mode == FanMode.MANUAL) {
            LabeledSlider(tr(R.string.fan_step), prefs.fanLevel, 0..8, { "$it / 8" }) { prefs.fanLevel = it; applyControl(ctx) }
        }
        AnimatedVisibility(mode == FanMode.TARGET) {
            LabeledSlider(tr(R.string.fan_target), prefs.targetTemp, 45..85, { "$it°C" }) { prefs.targetTemp = it; applyControl(ctx) }
        }
        LabeledSlider(tr(R.string.safety_temp), prefs.safetyTemp, 70..95, { tr(R.string.safety_value, it) }) { prefs.safetyTemp = it; applyControl(ctx) }
    }
}

@Composable
private fun TdpCard(prefs: Prefs, tick: Int) {
    @Suppress("UNUSED_EXPRESSION") tick
    val ctx = LocalContext.current
    var profile by remember { mutableStateOf(prefs.tdp) }
    SectionCard(Icons.Rounded.Speed, "TDP", anchor = "tdp") {
        ConnectedChoice(
            options = TdpProfile.entries.map { it to it.label },
            selected = profile
        ) { profile = it; prefs.tdp = it; applyControl(ctx) }
        AnimatedVisibility(profile == TdpProfile.CUSTOM) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledSlider(tr(R.string.cpu_max), prefs.cpuPct, 30..100, { "$it%" }) { prefs.cpuPct = it; applyControl(ctx) }
                LabeledSlider(tr(R.string.gpu_max), prefs.gpuPct, 30..100, { "$it%" }) { prefs.gpuPct = it; applyControl(ctx) }
            }
        }
        if (profile != TdpProfile.CUSTOM && profile != TdpProfile.NONE) {
            Text("CPU ${profile.cpuPct}% · GPU ${profile.gpuPct}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        var sleepSave by remember { mutableStateOf(prefs.sleepSave) }
        SwitchRow(tr(R.string.sleep_save), tr(R.string.sleep_save_desc), sleepSave) { sleepSave = it; prefs.sleepSave = it; applyControl(ctx) }
        Status.reading?.let { r ->
            HorizontalDivider()
            Text(tr(R.string.current_max, r.cpuMaxMHz.joinToString(" / ")) +
                    (if (r.gpuMaxMHz > 0) " · GPU ${r.gpuMaxMHz} MHz" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun FullscreenCard(prefs: Prefs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var lock by remember { mutableStateOf(prefs.lockLandscape) }
    var option by remember { mutableStateOf(prefs.fullscreenOption) }
    var autoNew by remember { mutableStateOf(prefs.autoNewApps) }
    val apps = remember { ScreenTools.launcherApps(ctx) }
    var selected by remember { mutableStateOf(prefs.forcedAppsOrAll(ctx)) }
    // Applied state per app, read from the system (6 = full screen).
    var aspects by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    fun refreshAspects() = scope.launch {
        aspects = withContext(Dispatchers.IO) { ScreenTools.readAspects(ctx, apps.map { it.first }) }
    }
    LaunchedEffect(Unit) {
        while (!RootShell.isRoot) delay(1000)
        refreshAspects()
    }

    /** Runs a root task in the background with a loading indicator, then shows a short toast. */
    fun run(done: String, task: () -> Boolean) {
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching(task).getOrDefault(false) }
            busy = false
            Toast.makeText(ctx, if (ok) done else tr(R.string.root_failed), Toast.LENGTH_SHORT).show()
            refreshAspects()
        }
    }

    fun applySelection(target: Set<String>) {
        val removed = prefs.forcedAppsOrAll(ctx) - target
        prefs.forcedApps = target
        prefs.excludedApps = apps.map { it.first }.toSet() - target
        applyControl(ctx)   // the daemon needs the new exclude list
        selected = target
        run(tr(R.string.fs_applied_n, target.size)) {
            val a = if (removed.isEmpty()) true else ScreenTools.forceFullscreen(ctx, removed, false)
            ScreenTools.forceFullscreen(ctx, target, true) && a
        }
    }

    SectionCard(Icons.Rounded.Fullscreen, tr(R.string.fullscreen), anchor = "fullscreen") {
        SwitchRow(tr(R.string.lock_landscape), tr(R.string.lock_landscape_desc), lock) {
            lock = it; prefs.lockLandscape = it; run(if (it) tr(R.string.lock_landscape_on) else tr(R.string.lock_landscape_off)) { ScreenTools.lockLandscape(ctx, it) }
        }
        SwitchRow(tr(R.string.fs_option), tr(R.string.fs_option_desc), option) {
            option = it; prefs.fullscreenOption = it
            if (it) run(tr(R.string.fs_option_on)) { ScreenTools.enableFullscreenOption(ctx) }
        }
        SwitchRow(tr(R.string.auto_new), tr(R.string.auto_new_desc), autoNew) {
            autoNew = it; prefs.autoNewApps = it; applyControl(ctx)
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            SubHeader(tr(R.string.apps_to_apply), Modifier.weight(1f))
            if (busy) LoadingIndicator(Modifier.size(32.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { applySelection(selected) }) { Text(tr(R.string.apply_selected)) }
            FilledTonalButton(enabled = !busy, onClick = { applySelection(apps.map { it.first }.toSet()) }) { Text(tr(R.string.apply_all)) }
            OutlinedButton(enabled = !busy, onClick = { run(tr(R.string.reapplied)) { ScreenTools.applyAll(ctx, prefs) } }) {
                Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr(R.string.reapply))
            }
        }
        Column {
            for ((pkg, label) in apps) {
                val applied = aspects[pkg] == ScreenTools.ASPECT_FULLSCREEN
                ListItem(
                    headlineContent = { Text(label) },
                    // Only a mismatch between the checkbox and the system is worth a line.
                    supportingContent = if (aspects.isNotEmpty() && applied != (pkg in selected)) ({
                        Text(if (applied) tr(R.string.app_applied_pending) else tr(R.string.app_not_applied),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }) else null,
                    trailingContent = {
                        Checkbox(pkg in selected, onCheckedChange = { c -> selected = if (c) selected + pkg else selected - pkg })
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun DensityCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<ScreenTools.Density?>(null) }
    var busy by remember { mutableStateOf(false) }
    val prefs = remember { Prefs(ctx) }
    var lockDate by remember { mutableStateOf(prefs.lockDateTop) }
    LaunchedEffect(Unit) {
        while (info == null) {
            if (RootShell.isRoot) info = withContext(Dispatchers.IO) { ScreenTools.readDensity(ctx) }
            if (info == null) delay(1000)
        }
    }
    SectionCard(Icons.Rounded.AspectRatio, tr(R.string.density), anchor = "density") {
        DensityChoice(info, busy, onBusy = { busy = it }, onInfo = { info = it })
        SwitchRow(tr(R.string.lock_date), tr(R.string.lock_date_desc), lockDate) {
            lockDate = it; prefs.lockDateTop = it
            scope.launch {
                val ok = withContext(Dispatchers.IO) { ScreenTools.lockScreenDate(ctx, it) }
                if (!ok) Toast.makeText(ctx, tr(R.string.root_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun DensityChoice(info: ScreenTools.Density?, busy: Boolean, onBusy: (Boolean) -> Unit, onInfo: (ScreenTools.Density?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val d = info
        if (d == null) {
            Text(tr(R.string.after_root), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        // 0 = physical (default). 288 is the highest value that still gives a tablet layout on 1080 px.
        val options = listOf(0 to tr(R.string.density_default), 288 to tr(R.string.density_tablet))
        val selected = if (d.current == d.physical) 0 else if (d.current == 288) 288 else -1
        ConnectedChoice(options = options, selected = selected) { v ->
            Prefs(ctx).tabletDensity = v == ScreenTools.TABLET_DPI
            onBusy(true)
            scope.launch {
                withContext(Dispatchers.IO) { ScreenTools.setDensity(ctx, if (v == 0) null else v) }
                onInfo(withContext(Dispatchers.IO) { ScreenTools.readDensity(ctx) })
                onBusy(false)
            }
        }
        val sw = ScreenTools.smallestWidthDp(d.shortSidePx, d.current)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr(R.string.density_line, d.current, if (sw >= 600) tr(R.string.tablet_ui) else tr(R.string.phone_ui)) +
                    if (d.current != d.physical) tr(R.string.density_default_n, d.physical) else "",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (busy) LoadingIndicator(Modifier.size(28.dp))
        }
    }
}

@Composable
private fun MemoryCard(tick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val live = remember(tick) { Memory.live(ctx) }
    var detail by remember { mutableStateOf<Memory.Detail?>(null) }
    var busy by remember { mutableStateOf(false) }
    SectionCard(Icons.Rounded.DataUsage, tr(R.string.memory)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Memory.gb(live.usedKb), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(tr(R.string.mem_used_of, Memory.gb(live.totalKb)), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 6.dp))
        }
        LinearWavyProgressIndicator(progress = { live.usedKb / live.totalKb.toFloat() }, modifier = Modifier.fillMaxWidth())
        Text(tr(R.string.mem_avail, Memory.gb(live.availKb)) +
                (if (live.swapTotalKb > 0) " · zRAM ${Memory.mb(live.swapUsedKb)}" else "") +
                (if (live.reservedKb > 0) tr(R.string.mem_installed, Memory.gb(live.installedKb), Memory.gb(live.reservedKb)) else ""),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(enabled = !busy, onClick = {
                busy = true
                scope.launch { detail = withContext(Dispatchers.IO) { Memory.detail(ctx) }; busy = false }
            }) { Text(if (detail == null) tr(R.string.details) else tr(R.string.refresh)) }
            Spacer(Modifier.width(12.dp))
            if (busy) LoadingIndicator(Modifier.size(32.dp))
        }
        detail?.let { d ->
            Text(tr(R.string.mem_detail, Memory.gb(d.usedPssKb), Memory.gb(d.kernelKb), Memory.gb(d.cachedKb), Memory.gb(d.freeKb)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            HorizontalDivider()
            SubHeader(tr(R.string.top_processes))
            val max = d.top.maxOfOrNull { it.pssKb }?.coerceAtLeast(1) ?: 1
            for (p in d.top) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(Memory.mb(p.pssKb), style = MaterialTheme.typography.bodyMedium)
                }
                LinearProgressIndicator(progress = { p.pssKb / max.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun BatteryCard(tick: Int) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var led by remember { mutableStateOf(prefs.batteryLed) }
    val b = remember(tick) { Battery.read(ctx) } ?: return
    SectionCard(Icons.Rounded.BatteryChargingFull, tr(R.string.battery)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${b.percent}%", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(b.status + (b.chargeType?.let { if (b.status == tr(R.string.bat_charging)) " ($it)" else "" } ?: "") +
                    (b.watts?.let { " · %.1f W".format(it) } ?: ""),
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(buildString {
            b.healthPct?.let { append(tr(R.string.bat_health, it, "%,d".format(b.capacityMah))) }
            b.cycles?.let { if (isNotEmpty()) append(" · "); append(tr(R.string.bat_cycles, it)) }
            if (isNotEmpty()) append(" · ")
            append("%.1f°C · %.2f V".format(b.tempC, b.voltage))
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        HorizontalDivider()
        SubHeader(tr(R.string.battery_led))
        ConnectedChoice(options = BatteryLed.entries.map { it to it.label }, selected = led) {
            led = it; prefs.batteryLed = it; applyControl(ctx)
        }
        if (led != BatteryLed.OFF) Text(tr(R.string.battery_led_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ControllerCard() {
    val ctx = LocalContext.current
    SectionCard(Icons.Rounded.SportsEsports, tr(R.string.controller), anchor = "controller") {
        Text(tr(R.string.controller_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { ctx.startActivity(Intent(ctx, ControllerTestActivity::class.java)) }) { Text(tr(R.string.input_test)) }
            FilledTonalButton(onClick = {
                ctx.startActivity(Intent(ctx, ControllerTestActivity::class.java).putExtra(ControllerTestActivity.EXTRA_CALIBRATE, true))
            }) { Text(tr(R.string.calibration)) }
        }
    }
}

@Composable
private fun ConnectionPanel(prefs: Prefs, tick: Int) {
    @Suppress("UNUSED_EXPRESSION") tick
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var port by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var boot by remember { mutableStateOf(prefs.startOnBoot) }
    var notif by remember { mutableStateOf(prefs.showNotification) }
    var wifiOff by remember { mutableStateOf(prefs.wirelessAutoOff) }
    var confirmRestore by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(RootShell.state, style = MaterialTheme.typography.bodyLarge)
        if (Status.note.isNotEmpty() && Status.note != RootShell.state) {
            Text(Status.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(tr(R.string.needs_debugging), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { ControlService.start(ctx, setup = true) }) { Text(tr(R.string.reconnect)) }
            OutlinedButton(onClick = {
                PairReceiver.showPairPrompt(ctx); openDeveloperOptions(ctx)
                Toast.makeText(ctx, tr(R.string.pair_hint), Toast.LENGTH_LONG).show()
            }) { Text(tr(R.string.pair)) }
            TextButton(onClick = { openDeveloperOptions(ctx) }) {
                Icon(Icons.Rounded.Settings, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr(R.string.dev_options))
            }
        }
        if (!RootShell.isRoot) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(port, { port = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text(tr(R.string.port_optional)) },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f), label = { Text(tr(R.string.pairing_code)) },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            TextButton(enabled = code.length == 6, onClick = {
                scope.launch {
                    val res = withContext(Dispatchers.IO) { RootShell.pair(ctx, port.toIntOrNull() ?: 0, code) }
                    Toast.makeText(ctx, res, Toast.LENGTH_LONG).show()
                    ControlService.start(ctx, setup = true)
                }
            }) { Text(tr(R.string.pair_manual)) }
        }
        SwitchRow(tr(R.string.start_on_boot), tr(R.string.start_on_boot_desc), boot) { boot = it; prefs.startOnBoot = it }
        SwitchRow(tr(R.string.status_notif), tr(R.string.status_notif_desc), notif) {
            notif = it; prefs.showNotification = it
            if (it) ControlService.start(ctx) else {
                ControlService.stop(ctx)
                ctx.getSystemService(android.app.NotificationManager::class.java).cancel(ControlService.NOTIF_ID)
            }
        }
        SwitchRow(tr(R.string.wifi_auto_off), tr(R.string.wifi_auto_off_desc), wifiOff) {
            wifiOff = it; prefs.wirelessAutoOff = it
        }
        HorizontalDivider()
        SubHeader(tr(R.string.language))
        var lang by remember { mutableStateOf(Lang.current(ctx).substringBefore('-')) }
        ConnectedChoice(options = listOf("" to tr(R.string.lang_system)) + Lang.OPTIONS, selected = lang) {
            lang = it; Lang.set(ctx, it)
        }
        HorizontalDivider()
        SubHeader(tr(R.string.before_uninstall))
        Text(tr(R.string.restore_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(enabled = RootShell.isRoot && !restoring, onClick = { confirmRestore = true }) { Text(tr(R.string.restore)) }
    }
    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(tr(R.string.restore)) },
            text = { Text(tr(R.string.restore_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false; restoring = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { ScreenTools.restoreStock(ctx.applicationContext, prefs) }
                        restoring = false
                        if (ok) {
                            prefs.startOnBoot = false
                            Toast.makeText(ctx, tr(R.string.restore_done), Toast.LENGTH_LONG).show()
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + ctx.packageName))) }
                        } else Toast.makeText(ctx, tr(R.string.restore_failed), Toast.LENGTH_LONG).show()
                    }
                }) { Text(tr(R.string.restore_action)) }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(tr(R.string.cancel)) } }
        )
    }
}

// ---------------------------------------------------------------- building blocks

/** Material 3 Expressive connected button group used as a single-choice selector. */
@Composable
private fun <T> ConnectedChoice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, (value, label) ->
            ToggleButton(
                checked = value == selected,
                onCheckedChange = { onSelect(value) },
                modifier = Modifier.weight(1f),
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                }
            ) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, initial: Int, range: IntRange, fmt: (Int) -> String, onCommit: (Int) -> Unit) {
    var v by remember { mutableStateOf(initial.coerceIn(range).toFloat()) }
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(fmt(v.roundToInt()), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = v,
            onValueChange = { v = it },
            onValueChangeFinished = { onCommit(v.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            // Few positions (fan 0..8) get tick marks; long ranges slide freely and are rounded.
            steps = if (range.last - range.first <= 10) range.last - range.first - 1 else 0
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Small section label inside a card (Material 3 label style in the primary colour). */
@Composable
private fun SubHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

private fun openDeveloperOptions(ctx: Context) {
    val tries = listOf(
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings"))
    )
    for (i in tries) { try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {} }
}

private fun launcherApps(ctx: Context): List<Pair<String, String>> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .filter { it.first != ctx.packageName }
        .distinctBy { it.first }
        .sortedBy { it.second.lowercase() }
}
