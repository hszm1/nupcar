package com.arc.nupcar

import com.arc.nupcar.explorer.VehicleDomain
import com.arc.nupcar.session.BreathCycle
import com.arc.nupcar.session.NupcarMode
import com.arc.nupcar.session.ProfileStore
import com.arc.nupcar.session.RestProfile
import com.arc.nupcar.session.RestSessionService
import com.arc.nupcar.session.RestSound
import com.arc.nupcar.session.RestState
import com.arc.nupcar.session.SeatView
import com.arc.nupcar.session.WakeSpeed
import com.arc.nupcar.ui.screen.SampleApp
import com.arc.nupcar.ui.theme.VehicleSampleAppTheme
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.Application
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Nupcar first screen.
 * Starting a rest runs the session in the session package, which controls the seat,
 * climate, light and sound. The 10-second alarm preview stays local and never touches
 * the vehicle. The DEBUG inspector provides explicit vehicle change/restore test actions.
 * A running session survives leaving the screen, not Android process termination.
 * DEBUG builds: long-press the Nupcar title to open the original SDK inspector.
 * The inspector sends real requests; a successful set callback is not proof
 * that a vehicle value changed. Use a fresh read or a received update to verify.
 */
class MainActivity : ComponentActivity() {
    private val nupcar: NupcarViewModel by viewModels()
    private val vehicleInspector: MainViewModel by viewModels()
    private val vehicleControls: NupcarVehicleControlViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            var showVehicleInspector by rememberSaveable { mutableStateOf(false) }
            val state = nupcar.state
            val session = nupcar.session.collectAsStateWithLifecycle().value
            val seat = nupcar.seat.collectAsStateWithLifecycle().value
            val keepScreenOn =
                state.phase == NupcarPhase.RUNNING || state.phase == NupcarPhase.PAUSED ||
                    session !is RestState.Idle

            DisposableEffect(keepScreenOn) {
                if (keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            if (showVehicleInspector && BuildConfig.DEBUG) {
                // Lazily creates the sample ViewModel only when opening tools.
                NupcarVehicleInspector(
                    viewModel = vehicleInspector,
                    controls = vehicleControls,
                    onClose = { showVehicleInspector = false },
                )
            } else NupcarTheme {
                NupcarScreen(
                    state = state,
                    session = session,
                    seat = seat,
                    onModeSelected = nupcar::selectMode,
                    onMinutesSelected = nupcar::selectMinutes,
                    onAlarmChanged = nupcar::setAlarmEnabled,
                    onProfileChanged = nupcar::updateProfile,
                    onStart = nupcar::startSession,
                    onWakeNow = nupcar::wakeNow,
                    onEmergencyStop = nupcar::emergencyStop,
                    onRestoreSeat = nupcar::restoreSeat,
                    onSessionDone = nupcar::finishSession,
                    onPreview = { nupcar.start(preview = true) },
                    onPause = nupcar::pause,
                    onResume = nupcar::resume,
                    onFinish = nupcar::reset,
                    onOpenVehicleInspector = {
                        if (BuildConfig.DEBUG && nupcar.state.phase == NupcarPhase.READY &&
                            session is RestState.Idle
                        ) {
                            showVehicleInspector = true
                        }
                    },
                )
            }
        }
    }
}

/** Development-only entry to the existing official sample. No automatic writes. */
@Composable
private fun NupcarVehicleInspector(
    viewModel: MainViewModel,
    controls: NupcarVehicleControlViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var report by remember { mutableStateOf("") }
    var showReport by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(false) }
    val controlState = controls.uiState.collectAsStateWithLifecycle().value
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controls) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controls.cancel()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controls.cancel()
        }
    }
    val probe = remember(context) { NupcarVehicleProbe(context) { report = it } }
    LaunchedEffect(probe) { probe.inspect() }
    DisposableEffect(probe) { onDispose { probe.close() } }
    val inspectorState = viewModel.uiState.collectAsStateWithLifecycle().value
    val closeInspector: () -> Unit = {
        if (controlState.busy) {
            controls.cancel()
            showControls = true
        } else {
            // Switching domain also releases any listeners registered by the sample.
            viewModel.showApiExplorer(VehicleDomain.SEAT)
            onClose()
        }
    }
    BackHandler(onBack = closeInspector)

    VehicleSampleAppTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = closeInspector) { Text("눕카로 돌아가기") }
                    TextButton(onClick = { showControls = true }) { Text("변경 시험") }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { showReport = true; probe.inspect() }) {
                        Text("지원 자동 점검")
                    }
                    TextButton(onClick = { viewModel.showApiExplorer(VehicleDomain.SEAT) }) {
                        Text("시트")
                    }
                    TextButton(onClick = { viewModel.showApiExplorer(VehicleDomain.HVAC) }) {
                        Text("공조")
                    }
                    TextButton(onClick = { viewModel.showApiExplorer(VehicleDomain.LIGHT) }) {
                        Text("조명")
                    }
                }
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    SampleApp(
                        uiState = inspectorState,
                        onOpenDomain = viewModel::showApiExplorer,
                        onSelectFeature = viewModel::selectFeature,
                        onSearchFeatures = viewModel::searchFeatures,
                        onRunCapability = viewModel::runFeatureCapability,
                        onRunRead = viewModel::runFeatureRead,
                        onRunSet = viewModel::runFeatureSet,
                        onStartUpdates = viewModel::startFeatureUpdates,
                        onStopUpdates = viewModel::stopFeatureUpdates,
                        onBackToFeatureList = viewModel::backToFeatureList,
                        onDismissError = viewModel::dismissError,
                    )
                }
            }
        }
        if (showReport) AlertDialog(
            onDismissRequest = { showReport = false },
            title = { Text("시트·공조·앰비언트 점검") },
            text = {
                SelectionContainer {
                    Text(
                        report.ifBlank { "Vehicle Service 응답을 기다리고 있습니다." },
                        modifier = Modifier.height(420.dp).verticalScroll(rememberScrollState()),
                        fontSize = 14.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("눕카 차량 점검", report))
                }) { Text("결과 복사") }
            },
            dismissButton = {
                TextButton(onClick = { showReport = false }) { Text("점검 화면 보기") }
            },
        )
        if (showControls) AlertDialog(
            onDismissRequest = { if (controlState.busy) controls.cancel() else showControls = false },
            title = { Text("차량 변경·복원 시험") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pleos 에뮬레이터용입니다. 원래 값을 읽은 후 변경하고, 재조회로 복원을 확인합니다.", fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = controls::testSeat,
                            enabled = !controlState.busy && !controlState.pendingRestore,
                            modifier = Modifier.weight(1f),
                        ) { Text("시트 0.5초") }
                        OutlinedButton(
                            onClick = controls::testEnvironment,
                            enabled = !controlState.busy && !controlState.pendingRestore,
                            modifier = Modifier.weight(1f),
                        ) { Text("공조·조명") }
                    }
                    if (controlState.pendingRestore && !controlState.busy) {
                        OutlinedButton(onClick = controls::retryRestore) { Text("복원 재시도") }
                    }
                    SelectionContainer {
                        Text(
                            controlState.report.ifBlank { "시험할 기능을 선택하세요. 온도 원래 값을 읽지 못하면 온도 변경은 건너뜁니다." },
                            modifier = Modifier.height(290.dp).verticalScroll(rememberScrollState()),
                            fontSize = 13.sp,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("눕카 변경 시험", controlState.report))
                }) { Text("결과 복사") }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (controlState.busy) controls.cancel() else showControls = false
                }) { Text(if (controlState.busy) "중지·복원" else "닫기") }
            },
        )
    }
}

data class NupcarVehicleControlState(
    val report: String = "",
    val busy: Boolean = false,
    val pendingRestore: Boolean = false,
)

class NupcarVehicleControlViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableUiState = MutableStateFlow(NupcarVehicleControlState())
    val uiState = mutableUiState.asStateFlow()
    private val controller = NupcarVehicleControlTest(application) { report, busy, pending ->
        mutableUiState.value = NupcarVehicleControlState(report, busy, pending)
    }
    fun testSeat() = controller.testSeat()
    fun testEnvironment() = controller.testEnvironment()
    fun retryRestore() = controller.retryRestore()
    fun cancel() = controller.cancel()
    override fun onCleared() { controller.close(); super.onCleared() }
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
    val profile: RestProfile = RestProfile.default(NupcarMode.NAP),
) {
    val progress: Float
        get() = if (totalMs <= 0L) 0f else
            (1f - remainingMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
}

/** Shorter choice for development and demo recordings only. */
private val NupcarMinuteChoices: List<Int> =
    (if (BuildConfig.DEBUG) listOf(1) else emptyList()) + listOf(10, 20, 30, 60)

class NupcarViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as NupcarApplication
    private val profiles = ProfileStore(application)
    private val controller get() = app.controller

    val session get() = controller.state
    val seat get() = controller.seat

    var state by mutableStateOf(
        profiles.load(NupcarMode.NAP).let {
            val duration = it.durationMinutes * 60_000L
            NupcarState(
                selectedMinutes = it.durationMinutes,
                remainingMs = duration,
                totalMs = duration,
                profile = it,
            )
        }
    )
        private set

    private var timer: CountDownTimer? = null
    private var deadlineMs = 0L
    private var generation = 0L
    private val alarmHandler = Handler(Looper.getMainLooper())
    private var alarmTask: Runnable? = null
    private var tone: ToneGenerator? = null

    fun selectMode(mode: NupcarMode) {
        if (state.phase != NupcarPhase.READY) return
        val profile = profiles.load(mode)
        val duration = profile.durationMinutes * 60_000L
        state = state.copy(
            mode = mode,
            selectedMinutes = profile.durationMinutes,
            remainingMs = duration,
            totalMs = duration,
            profile = profile,
        )
    }

    fun updateProfile(profile: RestProfile) {
        if (state.phase == NupcarPhase.READY) state = state.copy(profile = profile)
    }

    fun startSession() {
        if (state.phase != NupcarPhase.READY) return
        val profile = state.profile.copy(
            durationMinutes = state.selectedMinutes,
            wakeSoundEnabled = state.alarmEnabled,
        )
        profiles.save(profile)
        controller.start(profile)
        RestSessionService.start(app)
    }

    fun wakeNow() = controller.wakeNow()

    fun emergencyStop() = controller.emergencyStop()

    fun restoreSeat() {
        controller.restoreSeat()
        RestSessionService.start(app)
    }

    fun finishSession() = controller.reset()

    fun selectMinutes(minutes: Int) {
        if (state.phase != NupcarPhase.READY || minutes !in NupcarMinuteChoices) return
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NupcarScreen(
    state: NupcarState,
    session: RestState = RestState.Idle,
    seat: SeatView = SeatView(),
    onModeSelected: (NupcarMode) -> Unit = {},
    onMinutesSelected: (Int) -> Unit = {},
    onAlarmChanged: (Boolean) -> Unit = {},
    onProfileChanged: (RestProfile) -> Unit = {},
    onStart: () -> Unit = {},
    onWakeNow: () -> Unit = {},
    onEmergencyStop: () -> Unit = {},
    onRestoreSeat: () -> Unit = {},
    onSessionDone: () -> Unit = {},
    onPreview: () -> Unit = {},
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onFinish: () -> Unit = {},
    onOpenVehicleInspector: () -> Unit = {},
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
                    Text(
                        "눕카",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = if (BuildConfig.DEBUG && state.phase == NupcarPhase.READY && session is RestState.Idle) {
                            Modifier.combinedClickable(
                                onClick = {},
                                onLongClickLabel = "차량 기능 점검 열기",
                                onLongClick = onOpenVehicleInspector,
                            )
                        } else Modifier,
                    )
                    Text("쉬는 시간도, 내 취향대로.", color = NupcarMuted, fontSize = 18.sp)
                    if (BuildConfig.SIMULATED_VEHICLE) {
                        Text("시뮬레이션 모드: 실제 차량을 제어하지 않아요.", color = NupcarMint, fontSize = 15.sp)
                    }
                }

                val rightPanel: @Composable (Modifier) -> Unit = { panelModifier ->
                    if (session is RestState.Idle) {
                        SessionPanel(
                            state = state,
                            onStart = onStart,
                            onPreview = onPreview,
                            onPause = onPause,
                            onResume = onResume,
                            onFinish = onFinish,
                            modifier = panelModifier,
                        )
                    } else {
                        VehicleSessionPanel(
                            session = session,
                            seat = seat,
                            onWakeNow = onWakeNow,
                            onEmergencyStop = onEmergencyStop,
                            onRestoreSeat = onRestoreSeat,
                            onDone = onSessionDone,
                            modifier = panelModifier,
                        )
                    }
                }

                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        ModeSettings(
                            state = state,
                            editable = session is RestState.Idle,
                            onModeSelected = onModeSelected,
                            onMinutesSelected = onMinutesSelected,
                            onAlarmChanged = onAlarmChanged,
                            onProfileChanged = onProfileChanged,
                            modifier = Modifier.weight(1.1f),
                        )
                        rightPanel(Modifier.weight(1f))
                    }
                } else {
                    ModeSettings(
                        state = state,
                        editable = session is RestState.Idle,
                        onModeSelected = onModeSelected,
                        onMinutesSelected = onMinutesSelected,
                        onAlarmChanged = onAlarmChanged,
                        onProfileChanged = onProfileChanged,
                    )
                    rightPanel(Modifier)
                }

                Text(
                    "기어가 P일 때만 시작돼요. 시작 전에 시트 뒤 공간을 확인해 주세요. 언제든 즉시 중단할 수 있어요.",
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
    editable: Boolean,
    onModeSelected: (NupcarMode) -> Unit,
    onMinutesSelected: (Int) -> Unit,
    onAlarmChanged: (Boolean) -> Unit,
    onProfileChanged: (RestProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val canEdit = editable && state.phase == NupcarPhase.READY
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
                        enabled = canEdit,
                        onClick = { onModeSelected(mode) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(state.mode.description, color = NupcarMuted, fontSize = 16.sp)
            Spacer(Modifier.height(2.dp))
            Text("휴식 시간", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NupcarMinuteChoices.forEach { minutes ->
                    ChoiceButton(
                        text = minutes.toString() + "분",
                        selected = state.selectedMinutes == minutes,
                        enabled = canEdit,
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
                    Text("기상 사운드", fontSize = 18.sp)
                    Text("기기의 알람 음량으로 재생돼요.", color = NupcarMuted, fontSize = 13.sp)
                }
                Switch(
                    checked = state.alarmEnabled,
                    enabled = canEdit,
                    onCheckedChange = onAlarmChanged,
                )
            }
            RestDetailSettings(state.profile, canEdit, onProfileChanged)
        }
    }
}

@Composable
private fun RestDetailSettings(
    profile: RestProfile,
    enabled: Boolean,
    onChange: (RestProfile) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DetailSlider("등받이 눕힘", "${(profile.reclineRatio * 100).roundToInt()}%", profile.reclineRatio, 0f..1f, enabled) {
            onChange(profile.copy(reclineRatio = it))
        }
        DetailSlider("조명 밝기", "${(profile.ambientRatio * 100).roundToInt()}%", profile.ambientRatio, 0f..1f, enabled) {
            onChange(profile.copy(ambientRatio = it))
        }
        DetailSlider("실내 온도", "${profile.temperatureC.roundToInt()}°C", profile.temperatureC, 18f..28f, enabled) {
            onChange(profile.copy(temperatureC = it.roundToInt().toFloat()))
        }
        Text("사운드", fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RestSound.values().forEach { sound ->
                ChoiceButton(
                    text = sound.title,
                    selected = profile.sound == sound,
                    enabled = enabled,
                    onClick = { onChange(profile.copy(sound = sound)) },
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
                Text("호흡 가이드", fontSize = 18.sp)
                Text(
                    "화면의 원을 따라 5초 들이쉬고 5초 내쉬어요. 사운드도 호흡에 맞춰 커졌다 작아져요.",
                    color = NupcarMuted,
                    fontSize = 13.sp,
                )
            }
            Switch(
                checked = profile.breathingGuide,
                enabled = enabled,
                onCheckedChange = { onChange(profile.copy(breathingGuide = it)) },
            )
        }
        Text("기상 시 시트 속도", fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WakeSpeed.values().forEach { speed ->
                ChoiceButton(
                    text = speed.title,
                    selected = profile.wakeSpeed == speed,
                    enabled = enabled,
                    onClick = { onChange(profile.copy(wakeSpeed = speed)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DetailSlider(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 18.sp)
            Text(value, color = NupcarMint, fontSize = 18.sp)
        }
        Slider(value = current, onValueChange = onChange, valueRange = range, enabled = enabled)
    }
}

@Composable
private fun VehicleSessionPanel(
    session: RestState,
    seat: SeatView,
    onWakeNow: () -> Unit,
    onEmergencyStop: () -> Unit,
    onRestoreSeat: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            val showSeat = session is RestState.Preparing || session is RestState.Resting ||
                session is RestState.Waking || session is RestState.RestoringSeat ||
                session is RestState.Interrupted
            if (showSeat) SeatPicture(seat)
            when (session) {
                RestState.Idle -> Unit
                is RestState.Preparing -> {
                    SessionHeading("휴식 환경을 준비하고 있어요", "시트가 천천히 움직여요.")
                    EmergencyStopButton(onEmergencyStop)
                }
                is RestState.Resting -> {
                    SessionHeading("편안하게 쉬어가요", session.profile.mode.title)
                    if (session.profile.breathingGuide) {
                        BreathingGuide(session.soundStartedAtMillis) {
                            RestTimer(session.remainingMillis)
                        }
                    } else {
                        RestTimer(session.remainingMillis)
                    }
                    OutlinedButton(
                        onClick = onWakeNow,
                        modifier = Modifier.fillMaxWidth().height(66.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text("지금 일어나기", fontSize = 20.sp)
                    }
                    EmergencyStopButton(onEmergencyStop)
                }
                is RestState.Waking -> {
                    SessionHeading("휴식을 마무리하고 있어요", session.stage.title)
                    EmergencyStopButton(onEmergencyStop)
                }
                is RestState.RestoringSeat -> {
                    SessionHeading("시트를 되돌리고 있어요", session.message)
                    EmergencyStopButton(onEmergencyStop)
                }
                is RestState.Finished -> {
                    SessionHeading("휴식이 끝났어요", "시트와 차량 환경을 휴식 전 상태로 되돌렸어요.")
                    SessionWarnings(session.warnings)
                    SessionDoneButton(onDone)
                }
                is RestState.Interrupted -> {
                    SessionHeading("휴식이 중단되었어요", session.reason)
                    SessionWarnings(session.warnings)
                    if (session.canRestoreSeat) {
                        Button(
                            onClick = onRestoreSeat,
                            modifier = Modifier.fillMaxWidth().height(66.dp),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text("시트 되돌리기", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    SessionDoneButton(onDone)
                }
                is RestState.Rejected -> {
                    SessionHeading("시작하지 않았어요", session.reason)
                    SessionDoneButton(onDone)
                }
            }
        }
    }
}

/**
 * Side view of the seat. On the Pleos Connect emulator the backrest value never
 * changes, so the picture plays the commanded motion and says so.
 */
@Composable
private fun SeatPicture(seat: SeatView) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(modifier = Modifier.size(width = 240.dp, height = 150.dp)) {
            val unit = size.height / 10f
            val floorY = size.height - unit
            val hingeX = size.width * 0.62f
            val cushionTop = floorY - unit * 3.2f
            drawLine(NupcarMuted.copy(alpha = 0.4f), Offset(0f, floorY), Offset(size.width, floorY), unit * 0.25f, StrokeCap.Round)
            drawRoundRect(
                color = NupcarMint.copy(alpha = 0.55f),
                topLeft = Offset(hingeX - unit * 0.6f, cushionTop + unit * 1.1f),
                size = Size(unit * 1.2f, floorY - cushionTop - unit * 1.1f),
                cornerRadius = CornerRadius(unit * 0.3f),
            )
            drawRoundRect(
                color = NupcarMint,
                topLeft = Offset(hingeX - unit * 0.3f, cushionTop),
                size = Size(size.width * 0.34f, unit * 1.2f),
                cornerRadius = CornerRadius(unit * 0.6f),
            )
            // 0 recline leans the backrest 12° back from vertical, full recline lays it flat.
            val degrees = -(12f + seat.recline.coerceIn(0f, 1f) * 78f)
            val pivot = Offset(hingeX, cushionTop + unit * 0.6f)
            rotate(degrees, pivot) {
                val length = unit * 6f
                drawRoundRect(
                    color = NupcarMint,
                    topLeft = Offset(pivot.x - unit * 0.6f, pivot.y - length),
                    size = Size(unit * 1.2f, length),
                    cornerRadius = CornerRadius(unit * 0.6f),
                )
                drawRoundRect(
                    color = NupcarMint.copy(alpha = 0.8f),
                    topLeft = Offset(pivot.x - unit * 0.7f, pivot.y - length - unit * 1.7f),
                    size = Size(unit * 1.4f, unit * 1.4f),
                    cornerRadius = CornerRadius(unit * 0.5f),
                )
            }
        }
        Text(
            "등받이 눕힘 ${(seat.settingRatio * 100).roundToInt()}% (안전 범위 기준)",
            fontSize = 16.sp,
            color = NupcarMint,
        )
        Text(
            if (seat.simulated) {
                "시각화 화면이에요. 에뮬레이터에서는 등받이 값이 바뀌지 않아요" +
                    (seat.position?.let { " (실제 값 $it)" } ?: "")
            } else {
                "실제 시트 위치예요" + (seat.position?.let { " (값 $it)" } ?: "")
            },
            fontSize = 13.sp,
            color = NupcarMuted,
        )
    }
}

@Composable
private fun SessionHeading(title: String, detail: String) {
    Text(title, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
    Text(detail, color = NupcarMuted, fontSize = 17.sp, lineHeight = 25.sp)
}

@Composable
private fun SessionWarnings(warnings: List<String>) {
    warnings.forEach { Text(it, color = Color(0xFFFFC8AE), fontSize = 14.sp, lineHeight = 21.sp) }
}

@Composable
private fun SessionDoneButton(onDone: () -> Unit) {
    OutlinedButton(
        onClick = onDone,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Text("확인하고 돌아가기", fontSize = 18.sp)
    }
}

@Composable
private fun EmergencyStopButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(66.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD9423A), contentColor = Color.White),
    ) {
        Text("즉시 중단", fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RestTimer(remainingMillis: Long) {
    Text(
        formatNupcarTime(remainingMillis),
        fontSize = 64.sp,
        fontWeight = FontWeight.Light,
        color = NupcarMint,
    )
}

/**
 * A circle that grows on the in-breath and shrinks on the out-breath, with the matching
 * word. It reads the same [BreathCycle] as the breathing sound, counted from when the
 * sound started, so the screen and the sound stay in step.
 */
@Composable
private fun BreathingGuide(soundStartedAtMillis: Long, center: @Composable () -> Unit) {
    var elapsed by remember { mutableLongStateOf(System.currentTimeMillis() - soundStartedAtMillis) }
    LaunchedEffect(soundStartedAtMillis) {
        while (true) {
            withFrameMillis { elapsed = System.currentTimeMillis() - soundStartedAtMillis }
        }
    }
    val inhaling = BreathCycle.isInhaling(elapsed)
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(280.dp)
                .scale(0.7f + 0.3f * BreathCycle.level(elapsed))
                .background(Color(0xFF19343F), CircleShape),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (inhaling) "들이쉬세요" else "내쉬세요",
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (inhaling) NupcarMint else NupcarMuted,
            )
            center()
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
