@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.retroidsettings.lineage

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * Shows every key, stick, trigger and D-pad input live and keeps track of which ones were tested.
 * All key events are consumed here, so B/Back and the volume keys don't leave the screen;
 * Home and Power are reserved by the system and never reach apps.
 */
class ControllerTestActivity : ComponentActivity() {

    private data class Btn(val code: Int, val label: String)

    private val face = listOf(
        Btn(KeyEvent.KEYCODE_BUTTON_X, "X"), Btn(KeyEvent.KEYCODE_BUTTON_Y, "Y"),
        Btn(KeyEvent.KEYCODE_BUTTON_A, "A"), Btn(KeyEvent.KEYCODE_BUTTON_B, "B")
    )
    private val dpad = listOf(
        Btn(KeyEvent.KEYCODE_DPAD_UP, "↑"), Btn(KeyEvent.KEYCODE_DPAD_LEFT, "←"),
        Btn(KeyEvent.KEYCODE_DPAD_RIGHT, "→"), Btn(KeyEvent.KEYCODE_DPAD_DOWN, "↓")
    )
    private val shoulders = listOf(
        Btn(KeyEvent.KEYCODE_BUTTON_L1, "L1"), Btn(KeyEvent.KEYCODE_BUTTON_L2, "L2"),
        Btn(KeyEvent.KEYCODE_BUTTON_R1, "R1"), Btn(KeyEvent.KEYCODE_BUTTON_R2, "R2"),
        Btn(KeyEvent.KEYCODE_BUTTON_THUMBL, "L3"), Btn(KeyEvent.KEYCODE_BUTTON_THUMBR, "R3")
    )
    private val system = listOf(
        Btn(KeyEvent.KEYCODE_BUTTON_SELECT, "Select"), Btn(KeyEvent.KEYCODE_BUTTON_START, "Start"),
        Btn(KeyEvent.KEYCODE_BACK, "Back"),
        Btn(KeyEvent.KEYCODE_VOLUME_UP, "Vol +"), Btn(KeyEvent.KEYCODE_VOLUME_DOWN, "Vol −")
    )
    private val expected by lazy { (face + dpad + shoulders + system).map { it.code }.toSet() }

    private val pressed = mutableStateMapOf<Int, Boolean>()
    private val tested = mutableStateMapOf<Int, Boolean>()
    /** Keys that are not in the expected list (e.g. M1/M2 mapped to something else). */
    private val extra = mutableStateListOf<Int>()
    private val log = mutableStateListOf<String>()
    private var lx by mutableFloatStateOf(0f); private var ly by mutableFloatStateOf(0f)
    private var rx by mutableFloatStateOf(0f); private var ry by mutableFloatStateOf(0f)
    private var lt by mutableFloatStateOf(0f); private var rt by mutableFloatStateOf(0f)
    private var device by mutableStateOf(tr(R.string.test_device_hint))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        calibOnly = intent.getBooleanExtra(EXTRA_CALIBRATE, false)
        if (savedInstanceState == null && calibOnly) calib.open()
        setContent {
            val ctx = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive()) { Screen() }
        }
    }

    override fun onStart() {
        super.onStart()
        UiVisibility.started()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) UiVisibility.stopped(this)
    }

    // ---------------------------------------------------------------- input

    private fun mark(code: Int, down: Boolean) {
        pressed[code] = down
        if (down) {
            tested[code] = true
            if (code !in expected && code !in extra) extra += code
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                mark(code, true)
                addLog("${KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")} ↓")
                event.device?.let { device = tr(R.string.test_device, it.name) }
            }
            KeyEvent.ACTION_UP -> mark(code, false)
        }
        return true // consume everything so nothing navigates away
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        val joystick = ev.isFromSource(InputDevice.SOURCE_JOYSTICK) || ev.isFromSource(InputDevice.SOURCE_GAMEPAD)
        if (!joystick || ev.action != MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(ev)
        lx = ev.getAxisValue(MotionEvent.AXIS_X); ly = ev.getAxisValue(MotionEvent.AXIS_Y)
        rx = ev.getAxisValue(MotionEvent.AXIS_Z); ry = ev.getAxisValue(MotionEvent.AXIS_RZ)
        lt = max(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE))
        rt = max(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS))
        // Analog triggers count as L2/R2, the hat axes as the D-pad.
        triggerKey(KeyEvent.KEYCODE_BUTTON_L2, lt > 0.5f)
        triggerKey(KeyEvent.KEYCODE_BUTTON_R2, rt > 0.5f)
        val hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X); val hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
        triggerKey(KeyEvent.KEYCODE_DPAD_LEFT, hx < -0.5f); triggerKey(KeyEvent.KEYCODE_DPAD_RIGHT, hx > 0.5f)
        triggerKey(KeyEvent.KEYCODE_DPAD_UP, hy < -0.5f); triggerKey(KeyEvent.KEYCODE_DPAD_DOWN, hy > 0.5f)
        ev.device?.let { device = tr(R.string.test_device, it.name) }
        return true
    }

    private fun triggerKey(code: Int, down: Boolean) {
        if ((pressed[code] == true) != down) {
            mark(code, down)
            if (down) addLog(tr(R.string.test_axis_log, KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")))
        }
    }

    private fun addLog(s: String) {
        log.add(0, s)
        while (log.size > 6) log.removeAt(log.lastIndex)
    }

    private fun reset() {
        tested.clear(); extra.clear(); log.clear()
    }

    // ---------------------------------------------------------------- UI

    @Composable
    private fun Screen() {
        val done = expected.count { tested[it] == true }
        if (calib.visible) CalibrationDialog(calib)
        if (calibOnly) LaunchedEffect(calib.visible) { if (!calib.visible) finish() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.safeDrawingPadding().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr(R.string.test_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text("$done / ${expected.size}", style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    FilledTonalButton(onClick = { calib.open() }) { Text(tr(R.string.calibration)) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { reset() }) { Text(tr(R.string.test_again)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { finish() }) { Text(tr(R.string.close)) }
                }
                LinearWavyProgressIndicator(progress = { done / expected.size.toFloat() }, modifier = Modifier.fillMaxWidth())

                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Left side: shoulder L, stick, D-pad
                    Panel(Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Key(shoulders[0]); Key(shoulders[1]); Key(shoulders[4])
                        }
                        TriggerBar("L2", lt)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Stick(lx, ly, pressed[KeyEvent.KEYCODE_BUTTON_THUMBL] == true)
                            Cross(dpad[0], dpad[1], dpad[2], dpad[3])
                        }
                        Text(tr(R.string.test_lstick, "%+.2f".format(lx), "%+.2f".format(ly)), style = MaterialTheme.typography.bodySmall)
                    }
                    // Middle: system keys + log
                    Panel(Modifier.weight(0.8f)) {

                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            system.forEach { Key(it) }
                            extra.forEach { Key(Btn(it, KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_"))) }
                        }
                        Text(tr(R.string.test_recent), style = MaterialTheme.typography.titleSmall)
                        log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    // Right side: shoulder R, face buttons, stick
                    Panel(Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Key(shoulders[5]); Key(shoulders[3]); Key(shoulders[2])
                        }
                        TriggerBar("R2", rt)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Cross(face[0], face[1], face[2], face[3]) // X top, Y left, A right, B bottom (RP6 labels)
                            Stick(rx, ry, pressed[KeyEvent.KEYCODE_BUTTON_THUMBR] == true)
                        }
                        Text(tr(R.string.test_rstick, "%+.2f".format(rx), "%+.2f".format(ry)), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    private val calib by lazy { CalibrationController(this, lifecycleScope) }
    /** Opened from the main screen only to calibrate: closing the dialog closes this screen too. */
    private var calibOnly = false

    companion object {
        /** Open straight into the calibration dialog (from the main screen's controller card). */
        const val EXTRA_CALIBRATE = "calibrate"
    }

    @Composable
    private fun Panel(modifier: Modifier, content: @Composable () -> Unit) {
        Card(modifier.fillMaxSize(), shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
    }

    /** One key chip: filled while held, tonal once tested, outlined if not tested yet. */
    @Composable
    private fun Key(b: Btn, size: Int = 0) {
        val down = pressed[b.code] == true
        val ok = tested[b.code] == true
        val bg by animateColorAsState(
            when {
                down -> MaterialTheme.colorScheme.primary
                ok -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHighest
            }, label = "key"
        )
        val fg = when {
            down -> MaterialTheme.colorScheme.onPrimary
            ok -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Surface(
            shape = if (down) MaterialTheme.shapes.medium else MaterialTheme.shapes.extraLarge,
            color = bg,
            modifier = if (size > 0) Modifier.size(size.dp) else Modifier
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(b.label, color = fg, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
        }
    }

    /** Diamond of four keys: top, left, right, bottom. */
    @Composable
    private fun Cross(top: Btn, left: Btn, right: Btn, bottom: Btn) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Key(top, 56)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Key(left, 56); Spacer(Modifier.size(56.dp)); Key(right, 56) }
            Key(bottom, 56)
        }
    }

    @Composable
    private fun Stick(x: Float, y: Float, clicked: Boolean) {
        val ring = MaterialTheme.colorScheme.outline
        val dot = if (clicked) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
        val moved = abs(x) > 0.1f || abs(y) > 0.1f
        Canvas(Modifier.size(120.dp)) {
            val r = size.minDimension / 2
            drawCircle(ring, r - 4, style = Stroke(width = 4f))
            drawLine(ring.copy(alpha = 0.4f), Offset(center.x - r, center.y), Offset(center.x + r, center.y), 2f)
            drawLine(ring.copy(alpha = 0.4f), Offset(center.x, center.y - r), Offset(center.x, center.y + r), 2f)
            drawCircle(dot, if (moved || clicked) 26f else 20f, Offset(center.x + x * (r - 26), center.y + y * (r - 26)))
        }
    }

    @Composable
    private fun TriggerBar(label: String, v: Float) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.test_analog, label), Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { v.coerceIn(0f, 1f) }, modifier = Modifier.weight(1f).height(8.dp))
            Text(" ${(v * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        }
    }
}
