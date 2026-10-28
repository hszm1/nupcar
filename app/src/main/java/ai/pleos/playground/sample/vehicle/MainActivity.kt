package ai.pleos.playground.sample.vehicle

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import java.util.Locale

/*
 * Nupcar first screen.
 * This version implements mode selection, a foreground session timer and an
 * alarm tone. It does not move seats, change vehicle settings or play ASMR.
 * The ViewModel survives activity recreation, not Android process termination.
 * A persistent alarm/service and vehicle integration are separate next steps.
 */
class MainActivity : ComponentActivity() {
    private val nupcar: NupcarViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val state = nupcar.state
            val keepScreenOn =
                state.phase == NupcarPhase.RUNNING || state.phase == NupcarPhase.PAUSED

            DisposableEffect(keepScreenOn) {
                if (keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            NupcarTheme {
                NupcarScreen(
                    state = state,
                    onModeSelected = nupcar::selectMode,
                    onMinutesSelected = nupcar::selectMinutes,
                    onAlarmChanged = nupcar::setAlarmEnabled,
                    onStart = { nupcar.start(preview = false) },
                    onPreview = { nupcar.start(preview = true) },
                    onPause = nupcar::pause,
                    onResume = nupcar::resume,
                    onFinish = nupcar::reset,
                )
            }
        }
    }
}

enum class NupcarMode(
    val title: String,
    val description: String,
    val defaultMinutes: Int,
) {
    NAP("낮잠", "잠깐 눈을 붙이고 쉬어가요.", 20),
    MEDITATION("명상", "잠시 호흡과 나에게 집중해요.", 10),
    ASMR("ASMR", "편안한 소리와 함께하는 휴식.", 30),
}

enum class NupcarPhase { READY, RUNNING, PAUSED, FINISHED }

data class NupcarState(
    val mode: NupcarMode = NupcarMode.NAP,
    val selectedMinutes: Int = 20,
    val phase: NupcarPhase = NupcarPhase.READY,
    val remainingMs: Long = 20 * 60_000L,
    val totalMs: Long = 20 * 60_000L,
    val alarmEnabled: Boolean = true,
    val isPreview: Boolean = false,
    val alarmError: String? = null,
) {
    val progress: Float
        get() = if (totalMs <= 0L) 0f else
            (1f - remainingMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
}

class NupcarViewModel : ViewModel() {
    var state by mutableStateOf(NupcarState())
        private set

    private var timer: CountDownTimer? = null
    private var deadlineMs = 0L
    private var generation = 0L
    private val alarmHandler = Handler(Looper.getMainLooper())
    private var alarmTask: Runnable? = null
    private var tone: ToneGenerator? = null

    fun selectMode(mode: NupcarMode) {
        if (state.phase != NupcarPhase.READY) return
        val duration = mode.defaultMinutes * 60_000L
        state = state.copy(
            mode = mode,
            selectedMinutes = mode.defaultMinutes,
            remainingMs = duration,
            totalMs = duration,
        )
    }

    fun selectMinutes(minutes: Int) {
        if (state.phase != NupcarPhase.READY || minutes !in listOf(10, 20, 30, 60)) return
        val duration = minutes * 60_000L
        state = state.copy(
            selectedMinutes = minutes,
            remainingMs = duration,
            totalMs = duration,
        )
    }

    fun setAlarmEnabled(enabled: Boolean) {
        if (state.phase == NupcarPhase.READY) {
            state = state.copy(alarmEnabled = enabled)
        }
    }

    fun start(preview: Boolean) {
        if (state.phase != NupcarPhase.READY) return
        stopAlarm()
        val duration = if (preview) 10_000L else state.selectedMinutes * 60_000L
        state = state.copy(
            phase = NupcarPhase.RUNNING,
            totalMs = duration,
            remainingMs = duration,
            isPreview = preview,
            alarmError = null,
        )
        startTimer(duration)
    }

    fun pause() {
        if (state.phase != NupcarPhase.RUNNING) return
        val remaining = (deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        cancelTimer()
        if (remaining == 0L) {
            complete()
        } else {
            state = state.copy(phase = NupcarPhase.PAUSED, remainingMs = remaining)
        }
    }

    fun resume() {
        if (state.phase != NupcarPhase.PAUSED) return
        if (state.remainingMs <= 0L) {
            complete()
            return
        }
        state = state.copy(phase = NupcarPhase.RUNNING)
        startTimer(state.remainingMs)
    }

    fun reset() {
        cancelTimer()
        stopAlarm()
        val duration = state.selectedMinutes * 60_000L
        state = state.copy(
            phase = NupcarPhase.READY,
            remainingMs = duration,
            totalMs = duration,
            isPreview = false,
            alarmError = null,
        )
    }

    private fun startTimer(durationMs: Long) {
        cancelTimer()
        deadlineMs = SystemClock.elapsedRealtime() + durationMs
        val token = generation
        timer = object : CountDownTimer(durationMs, 250L) {
            override fun onTick(millisUntilFinished: Long) {
                if (token != generation || state.phase != NupcarPhase.RUNNING) return
                val remaining = (deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                state = state.copy(remainingMs = remaining)
            }

            override fun onFinish() {
                if (token == generation && state.phase == NupcarPhase.RUNNING) {
                    complete()
                }
            }
        }.start()
    }

    private fun cancelTimer() {
        generation += 1L
        timer?.cancel()
        timer = null
    }

    private fun complete() {
        cancelTimer()
        state = state.copy(phase = NupcarPhase.FINISHED, remainingMs = 0L)
        if (state.alarmEnabled) playAlarm()
    }

    private fun playAlarm() {
        stopAlarm()
        tone = runCatching {
            ToneGenerator(AudioManager.STREAM_ALARM, 65)
        }.getOrNull()
        if (tone == null) {
            state = state.copy(alarmError = "알림음을 재생할 수 없어요. 기기의 소리 설정을 확인해 주세요.")
            return
        }

        var played = 0
        val task = object : Runnable {
            override fun run() {
                if (state.phase != NupcarPhase.FINISHED || played >= 6) {
                    stopAlarm()
                    return
                }
                val started = runCatching {
                    tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 400) == true
                }.getOrDefault(false)
                if (!started) {
                    state = state.copy(alarmError = "알림음을 재생할 수 없어요. 기기의 소리 설정을 확인해 주세요.")
                    stopAlarm()
                    return
                }
                played += 1
                alarmHandler.postDelayed(this, 900L)
            }
        }
        alarmTask = task
        alarmHandler.post(task)
    }

    private fun stopAlarm() {
        alarmTask?.let { alarmHandler.removeCallbacks(it) }
        alarmTask = null
        tone?.let {
            runCatching { it.stopTone() }
            runCatching { it.release() }
        }
        tone = null
    }

    override fun onCleared() {
        cancelTimer()
        stopAlarm()
        super.onCleared()
    }
}

private val NupcarBackground = Color(0xFF07151E)
private val NupcarPanel = Color(0xFF112732)
private val NupcarMint = Color(0xFF95F3D1)
private val NupcarMuted = Color(0xFFA6BAC2)

@Composable
private fun NupcarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NupcarMint,
            onPrimary = NupcarBackground,
            background = NupcarBackground,
            onBackground = Color(0xFFEAF5F5),
            surface = NupcarPanel,
            onSurface = Color(0xFFEAF5F5),
        ),
        content = content,
    )
}

@Composable
private fun NupcarScreen(
    state: NupcarState,
    onModeSelected: (NupcarMode) -> Unit = {},
    onMinutesSelected: (Int) -> Unit = {},
    onAlarmChanged: (Boolean) -> Unit = {},
    onStart: () -> Unit = {},
    onPreview: () -> Unit = {},
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onFinish: () -> Unit = {},
) {
    Surface(modifier = Modifier.fillMaxSize(), color = NupcarBackground) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            val wide = maxWidth >= 850.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(if (wide) 32.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("NUPCAR", color = NupcarMint, fontSize = 14.sp, letterSpacing = 3.sp)
                    Text("눕카", fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    Text("쉬는 시간도, 내 취향대로.", color = NupcarMuted, fontSize = 18.sp)
                }

                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        ModeSettings(
                            state = state,
                            onModeSelected = onModeSelected,
                            onMinutesSelected = onMinutesSelected,
                            onAlarmChanged = onAlarmChanged,
                            modifier = Modifier.weight(1.1f),
                        )
                        SessionPanel(
                            state = state,
                            onStart = onStart,
                            onPreview = onPreview,
                            onPause = onPause,
                            onResume = onResume,
                            onFinish = onFinish,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    ModeSettings(state, onModeSelected, onMinutesSelected, onAlarmChanged)
                    SessionPanel(state, onStart, onPreview, onPause, onResume, onFinish)
                }

                Text(
                    "주차 후 사용해 주세요. 현재 버전에서는 시트·조명·공조를 직접 조절해 주세요.",
                    color = NupcarMuted,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )
            }
        }
    }
}

@Composable
private fun ModeSettings(
    state: NupcarState,
    onModeSelected: (NupcarMode) -> Unit,
    onMinutesSelected: (Int) -> Unit,
    onAlarmChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editable = state.phase == NupcarPhase.READY
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = NupcarPanel),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("어떻게 쉬고 싶으세요?", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NupcarMode.values().forEach { mode ->
                    ChoiceButton(
                        text = mode.title,
                        selected = state.mode == mode,
                        enabled = editable,
                        onClick = { onModeSelected(mode) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(state.mode.description, color = NupcarMuted, fontSize = 16.sp)
            Spacer(Modifier.height(2.dp))
            Text("휴식 시간", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 20, 30, 60).forEach { minutes ->
                    ChoiceButton(
                        text = minutes.toString() + "분",
                        selected = state.selectedMinutes == minutes,
                        enabled = editable,
                        onClick = { onMinutesSelected(minutes) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("종료 알림음", fontSize = 18.sp)
                    Text("기기의 알람 음량으로 재생돼요.", color = NupcarMuted, fontSize = 13.sp)
                }
                Switch(
                    checked = state.alarmEnabled,
                    enabled = editable,
                    onCheckedChange = onAlarmChanged,
                )
            }
        }
    }
}

@Composable
private fun ChoiceButton(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(58.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            1.dp,
            if (selected) NupcarMint.copy(alpha = 0.7f) else NupcarMuted.copy(alpha = 0.25f),
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) NupcarMint else Color(0xFF19343F),
            contentColor = if (selected) NupcarBackground else Color(0xFFEAF5F5),
            disabledContainerColor = if (selected) Color(0xFF345C59) else Color(0xFF19343F),
            disabledContentColor = Color(0xFFB0C6CB),
        ),
    ) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun SessionPanel(
    state: NupcarState,
    onStart: () -> Unit,
    onPreview: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val heading = when (state.phase) {
        NupcarPhase.READY -> "나를 위한 휴식"
        NupcarPhase.RUNNING -> if (state.isPreview) "알림 체험 중" else "편안하게 쉬어가요"
        NupcarPhase.PAUSED -> "잠시 멈췄어요"
        NupcarPhase.FINISHED -> if (state.isPreview) "알림 체험 완료" else "휴식이 끝났어요"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = NupcarPanel),
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(heading, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (state.isPreview) "10초 뒤 알림을 확인해 보세요." else
                    state.mode.title + " · " + state.selectedMinutes.toString() + "분",
                color = NupcarMuted,
                fontSize = 17.sp,
            )
            Text(
                formatNupcarTime(state.remainingMs),
                fontSize = 64.sp,
                fontWeight = FontWeight.Light,
                color = NupcarMint,
            )
            Box(
                modifier = Modifier.fillMaxWidth().height(8.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Color(0xFF24434E)),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(state.progress).height(8.dp)
                        .background(NupcarMint),
                )
            }
            state.alarmError?.let {
                Text(it, color = Color(0xFFFFC8AE), fontSize = 14.sp, lineHeight = 21.sp)
            }
            val primaryAction = when (state.phase) {
                NupcarPhase.READY -> onStart
                NupcarPhase.RUNNING -> onPause
                NupcarPhase.PAUSED -> onResume
                NupcarPhase.FINISHED -> onFinish
            }
            val primaryText = when (state.phase) {
                NupcarPhase.READY -> "쉬기 시작"
                NupcarPhase.RUNNING -> "잠시 멈추기"
                NupcarPhase.PAUSED -> "계속 쉬기"
                NupcarPhase.FINISHED -> "확인하고 돌아가기"
            }
            Button(
                onClick = primaryAction,
                modifier = Modifier.fillMaxWidth().height(66.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(primaryText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            when (state.phase) {
                NupcarPhase.READY -> TextButton(
                    onClick = onPreview,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text("알림 10초 체험", fontSize = 17.sp)
                }
                NupcarPhase.RUNNING, NupcarPhase.PAUSED -> OutlinedButton(
                    onClick = onFinish,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("휴식 끝내기", fontSize = 18.sp)
                }
                NupcarPhase.FINISHED -> Text(
                    "준비가 되면 다음 휴식을 설정할 수 있어요.",
                    color = NupcarMuted,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

private fun formatNupcarTime(remainingMs: Long): String {
    val seconds = (remainingMs.coerceAtLeast(0L) + 999L) / 1000L
    return String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
}

@Preview(showBackground = true, widthDp = 1280, heightDp = 720)
@Composable
private fun NupcarPreview() {
    NupcarTheme {
        NupcarScreen(state = NupcarState())
    }
}
