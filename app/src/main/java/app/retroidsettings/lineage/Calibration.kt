@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.retroidsettings.lineage

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Stick and trigger calibration for the built-in controller, written to the driver's calibration node.
 * Steps: stick centre -> stick range -> triggers released -> triggers fully pressed -> review and save.
 * The value found before the first calibration is kept so it can be restored.
 */
class CalibrationController(private val ctx: Context, private val scope: CoroutineScope) {
    enum class Step { INTRO, CENTER, RANGE, TRIG_REST, TRIG_FULL, REVIEW }

    var visible by mutableStateOf(false); private set
    var step by mutableStateOf(Step.INTRO); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf("")
    var raw by mutableStateOf<Gamepad.Raw?>(null); private set
    var current by mutableStateOf<Gamepad.Calibration?>(null); private set
    private var currentText: String? = null

    // Measured values
    private val center = IntArray(4)
    val lo = IntArray(4); val hi = IntArray(4)
    private val rest = IntArray(2)
    val travel = IntArray(2)
    private val fullRaw = IntArray(2)
    var rangeTick by mutableIntStateOf(0); private set
    var stickDeadzone by mutableIntStateOf(200)
    var triggerDeadzone by mutableIntStateOf(30)

    private var poller: Job? = null

    fun open() {
        visible = true; step = Step.INTRO; message = ""
        busy = true
        scope.launch {
            val text = withContext(Dispatchers.IO) { Gamepad.readCalibration(ctx) }
            currentText = text
            current = Gamepad.Calibration.parse(text)
            current?.let { stickDeadzone = it.sticks[0].deadzone }
            if (text == null) message = tr(R.string.cal_read_failed)
            busy = false
            startPolling()
        }
    }

    fun close() { poller?.cancel(); visible = false }

    private fun startPolling() {
        poller?.cancel()
        poller = scope.launch {
            while (isActive) {
                val r = withContext(Dispatchers.IO) { Gamepad.readRaw(ctx) }
                if (r != null) {
                    raw = r
                    if (step == Step.RANGE) {
                        val v = intArrayOf(r.lx, r.ly, r.rx, r.ry)
                        for (i in 0..3) { lo[i] = minOf(lo[i], v[i]); hi[i] = maxOf(hi[i], v[i]) }
                        rangeTick++
                    }
                    if (step == Step.TRIG_FULL) {
                        val t = intArrayOf(r.l2, r.r2)
                        for (i in 0..1) if (abs(t[i] - rest[i]) > travel[i]) { travel[i] = abs(t[i] - rest[i]); fullRaw[i] = t[i] }
                        rangeTick++
                    }
                }
                delay(80)
            }
        }
    }

    /** Averages ~1 s of samples. */
    private suspend fun average(pick: (Gamepad.Raw) -> IntArray): IntArray? {
        val sums = LongArray(4); var n = 0
        repeat(12) {
            withContext(Dispatchers.IO) { Gamepad.readRaw(ctx) }?.let { r ->
                val v = pick(r); for (i in v.indices) sums[i] += v[i].toLong(); n++
            }
            delay(80)
        }
        if (n == 0) return null
        return IntArray(pick(raw ?: return null).size) { (sums[it] / n).toInt() }
    }

    fun next() {
        when (step) {
            Step.INTRO -> step = Step.CENTER
            Step.CENTER -> measure {
                val c = average { intArrayOf(it.lx, it.ly, it.rx, it.ry) } ?: return@measure false
                c.copyInto(center); for (i in 0..3) { lo[i] = c[i]; hi[i] = c[i] }
                step = Step.RANGE; true
            }
            Step.RANGE -> {
                val small = (0..3).filter { hi[it] - lo[it] < 800 }
                if (small.isNotEmpty()) { message = tr(R.string.cal_more_range); return }
                message = ""; step = Step.TRIG_REST
            }
            Step.TRIG_REST -> measure {
                val t = average { intArrayOf(it.l2, it.r2) } ?: return@measure false
                t.copyInto(rest); travel[0] = 0; travel[1] = 0; rest.copyInto(fullRaw)
                step = Step.TRIG_FULL; true
            }
            Step.TRIG_FULL -> {
                if (travel.any { it < 300 }) { message = tr(R.string.cal_press_triggers); return }
                message = ""; step = Step.REVIEW
            }
            Step.REVIEW -> save()
        }
    }

    private fun measure(block: suspend () -> Boolean) {
        busy = true; message = ""
        scope.launch {
            if (!block()) message = tr(R.string.cal_read_retry)
            busy = false
        }
    }

    /** The calibration that would be written now. Range is pulled in 3 % so a full push reaches 100 %. */
    fun proposed(): Gamepad.Calibration {
        val sticks = (0..3).map { i ->
            Gamepad.Axis(
                min = center[i] + ((lo[i] - center[i]) * 0.97).roundToInt(),
                max = center[i] + ((hi[i] - center[i]) * 0.97).roundToInt(),
                center = center[i], deadzone = stickDeadzone
            )
        }
        // Driver maps raw linearly from `start` (0 %) to `full` (100 %). A 3 % margin at the bottom makes a
        // full press always reach 100 %; the dead zone moves the start point away from the resting value.
        val trig = (0..1).map { i ->
            val dir = if (fullRaw[i] < rest[i]) -1 else 1          // raw drops when pressed on the RP6
            val margin = travel[i] * 3 / 100
            Gamepad.Trigger(full = fullRaw[i] - dir * margin, start = rest[i] + dir * (triggerDeadzone + margin))
        }
        return Gamepad.Calibration(sticks, trig)
    }

    private fun save() {
        val value = proposed().encode()
        busy = true
        scope.launch {
            val prefs = Prefs(ctx)
            if (prefs.calibrationBackup == null && currentText != null) prefs.calibrationBackup = currentText
            val back = withContext(Dispatchers.IO) { Gamepad.writeCalibration(ctx, value) }
            busy = false
            if (back == value) {
                prefs.calibration = value
                toast(tr(R.string.cal_saved))
                close()
            } else message = tr(R.string.cal_save_failed, back ?: tr(R.string.none_word))
        }
    }

    /** Back to the stock settings app's calibration (triggers still reach 100 %), see Gamepad.resetCalibration. */
    fun resetToDefault() {
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Gamepad.resetCalibration(ctx) }
            busy = false
            if (ok) { toast(tr(R.string.cal_reset_done)); close() } else message = tr(R.string.cal_reset_failed)
        }
    }

    private fun toast(t: String) = android.widget.Toast.makeText(ctx, t, android.widget.Toast.LENGTH_LONG).show()
}

@Composable
fun CalibrationDialog(c: CalibrationController) {
    val steps = CalibrationController.Step.entries
    AlertDialog(
        onDismissRequest = {},
        title = { Text(tr(R.string.cal_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearWavyProgressIndicator(progress = { c.step.ordinal / (steps.size - 1f) }, modifier = Modifier.fillMaxWidth())
                when (c.step) {
                    CalibrationController.Step.INTRO -> {
                        Text(tr(R.string.cal_intro))
                        c.current?.let { cur ->
                            Text(tr(R.string.cal_current) + System.lineSeparator() + describe(cur), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    CalibrationController.Step.CENTER ->
                        Text(tr(R.string.cal_step1))
                    CalibrationController.Step.RANGE -> {
                        @Suppress("UNUSED_EXPRESSION") c.rangeTick
                        Text(tr(R.string.cal_step2))
                        Text(listOf("LX", "LY", "RX", "RY").mapIndexed { i, n -> "$n ${c.lo[i]} ~ ${c.hi[i]}" }.joinToString("   "),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    CalibrationController.Step.TRIG_REST ->
                        Text(tr(R.string.cal_step3))
                    CalibrationController.Step.TRIG_FULL -> {
                        @Suppress("UNUSED_EXPRESSION") c.rangeTick
                        Text(tr(R.string.cal_step4))
                        Text(tr(R.string.cal_travel, c.travel[0], c.travel[1]), style = MaterialTheme.typography.bodySmall)
                    }
                    CalibrationController.Step.REVIEW -> {
                        Text(tr(R.string.cal_step5))
                        DzSlider(tr(R.string.cal_stick_dz), c.stickDeadzone, 0..400) { c.stickDeadzone = it }
                        DzSlider(tr(R.string.cal_trigger_dz), c.triggerDeadzone, 0..300) { c.triggerDeadzone = it }
                        Text(describe(c.proposed()), style = MaterialTheme.typography.bodySmall)
                    }
                }
                c.raw?.let {
                    Text(tr(R.string.cal_raw, it.lx, it.ly, it.rx, it.ry, it.l2, it.r2),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (c.message.isNotEmpty()) Text(c.message, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Row {
                if (c.busy) LoadingIndicator()
                Button(enabled = !c.busy, onClick = { c.next() }) {
                    Text(when (c.step) {
                        CalibrationController.Step.INTRO -> tr(R.string.cal_start)
                        CalibrationController.Step.CENTER, CalibrationController.Step.TRIG_REST -> tr(R.string.cal_measure)
                        CalibrationController.Step.REVIEW -> tr(R.string.cal_save)
                        else -> tr(R.string.cal_next)
                    })
                }
            }
        },
        dismissButton = {
            Row {
                if (c.step == CalibrationController.Step.INTRO) {
                    TextButton(enabled = !c.busy, onClick = { c.resetToDefault() }) { Text(tr(R.string.cal_reset)) }
                }
                TextButton(onClick = { c.close() }) { Text(tr(R.string.close)) }
            }
        }
    )
}

@Composable
private fun DzSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    var v by androidx.compose.runtime.remember { mutableStateOf(value.toFloat()) }
    Column {
        Row { Text(label, Modifier.weight(1f)); Text("${v.roundToInt()}") }
        Slider(value = v, onValueChange = { v = it; onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat())
    }
}

private fun describe(c: Gamepad.Calibration): String {
    val n = listOf("LX", "LY", "RX", "RY")
    return c.sticks.mapIndexed { i, a -> tr(R.string.cal_axis, n[i], a.min, a.max, a.center, a.deadzone) }.joinToString("\n") +
        "\n" + tr(R.string.cal_triggers, c.triggers[0].start, c.triggers[0].full, c.triggers[1].start, c.triggers[1].full)
}
