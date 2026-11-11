package com.arc.nupcar

import com.arc.nupcar.explorer.VehicleDomain
import com.arc.nupcar.session.BreathCycle
import com.arc.nupcar.session.MAX_RECLINE_SHARE
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Brush
import com.arc.nupcar.session.MAX_REST_MINUTES
import com.arc.nupcar.session.MIN_REST_MINUTES
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
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filter
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
            // The video mode's end notice needs this permission; the session starts either way.
            val notificationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { nupcar.startSession() }
            val startSession = {
                val needsPermission = nupcar.state.profile.quietWake &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (needsPermission) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    nupcar.startSession()
                }
            }
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
                    onStart = startSession,
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
            wakeSoundEnabled = state.alarmEnabled && !state.profile.quietWake,
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
        if (state.phase != NupcarPhase.READY || minutes !in MIN_REST_MINUTES..MAX_REST_MINUTES) return
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

/** The settings tabs along the bottom of the settings card; the chosen one opens above them. */
private enum class SettingTab(val title: String) {
    TIME("시간"),
    SEAT("시트"),
    LIGHT("조명"),
    CLIMATE("온도"),
    SOUND("사운드"),
}

private val NupcarPanelDeep = Color(0xFF0B1E27)

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
    var tab by rememberSaveable { mutableStateOf(SettingTab.TIME) }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = NupcarPanel),
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
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

            // The chosen tab's controls rise into this area from the tab bar below.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(NupcarPanelDeep)
                    .padding(horizontal = 32.dp, vertical = 28.dp),
            ) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        (slideInVertically(tween(340)) { it / 5 } + fadeIn(tween(340))) togetherWith
                            fadeOut(tween(140))
                    },
                    label = "setting-panel",
                ) { current ->
                    when (current) {
                        SettingTab.TIME -> TimePanel(state, canEdit, onMinutesSelected)
                        SettingTab.SEAT -> SeatPanel(state.profile, canEdit, onProfileChanged)
                        SettingTab.LIGHT -> LightPanel(state.profile, canEdit, onProfileChanged)
                        SettingTab.CLIMATE -> ClimatePanel(state.profile, canEdit, onProfileChanged)
                        SettingTab.SOUND -> SoundPanel(state, canEdit, onAlarmChanged, onProfileChanged)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingTab.values().forEach { item ->
                    SettingTabButton(
                        title = item.title,
                        value = tabValue(item, state),
                        selected = item == tab,
                        onClick = { tab = item },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun tabValue(tab: SettingTab, state: NupcarState): String {
    val profile = state.profile
    return when (tab) {
        SettingTab.TIME -> formatMinutes(state.selectedMinutes)
        SettingTab.SEAT -> "${(profile.reclineRatio * 100).roundToInt()}%"
        SettingTab.LIGHT -> "${(profile.ambientRatio * 100).roundToInt()}%"
        SettingTab.CLIMATE -> "${profile.temperatureC.roundToInt()}°C"
        SettingTab.SOUND -> when {
            profile.quietWake -> "알림"
            profile.breathingGuide -> profile.sound.title + " · 호흡"
            else -> profile.sound.title
        }
    }
}

@Composable
private fun SettingTabButton(
    title: String,
    value: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) NupcarMint else Color(0xFF19343F))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            title,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) NupcarBackground else Color(0xFFEAF5F5),
        )
        Text(
            value,
            fontSize = 14.sp,
            maxLines = 1,
            color = if (selected) NupcarBackground.copy(alpha = 0.75f) else NupcarMuted,
        )
    }
}

@Composable
private fun PanelTitle(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(detail, fontSize = 15.sp, color = NupcarMuted, lineHeight = 21.sp)
    }
}

@Composable
private fun TimePanel(state: NupcarState, enabled: Boolean, onMinutesSelected: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PanelTitle(
                if (state.profile.quietWake) "시청 시간" else "휴식 시간",
                "휠을 위아래로 돌려 1분 단위로 맞춰요.",
            )
            Text(formatMinutes(state.selectedMinutes), fontSize = 40.sp, fontWeight = FontWeight.Light, color = NupcarMint)
        }
        TimeWheel(totalMinutes = state.selectedMinutes, enabled = enabled, onChange = onMinutesSelected)
    }
}

@Composable
private fun SeatPanel(profile: RestProfile, enabled: Boolean, onChange: (RestProfile) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Preview of the backrest at the chosen recline, on the same safe-range scale.
        SeatDrawing(
            recline = profile.reclineRatio.coerceIn(0f, 1f) * MAX_RECLINE_SHARE,
            modifier = Modifier.size(width = 300.dp, height = 190.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PanelTitle("등받이", "안전 범위(최대 70%) 안에서 얼마나 눕힐지 정해요.")
            DetailSlider("눕힘", "${(profile.reclineRatio * 100).roundToInt()}%", profile.reclineRatio, 0f..1f, enabled) {
                onChange(profile.copy(reclineRatio = it))
            }
            Text(if (profile.quietWake) "종료 시 시트 속도" else "기상 시 시트 속도", fontSize = 17.sp)
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
}

@Composable
private fun LightPanel(profile: RestProfile, enabled: Boolean, onChange: (RestProfile) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A soft glow whose strength follows the ambient light setting.
        val glow = 0.12f + 0.88f * profile.ambientRatio.coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .size(220.dp)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFFFFE2A8).copy(alpha = glow), Color(0xFFFFE2A8).copy(alpha = 0f))
                    ),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("${(profile.ambientRatio * 100).roundToInt()}%", fontSize = 36.sp, fontWeight = FontWeight.Light)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PanelTitle("실내 조명", "쉬는 동안의 앰비언트 조명 밝기예요. 끝날 때는 서서히 밝아져요.")
            DetailSlider("밝기", "${(profile.ambientRatio * 100).roundToInt()}%", profile.ambientRatio, 0f..1f, enabled) {
                onChange(profile.copy(ambientRatio = it))
            }
        }
    }
}

@Composable
private fun ClimatePanel(profile: RestProfile, enabled: Boolean, onChange: (RestProfile) -> Unit) {
    val temperature = profile.temperatureC.roundToInt()
    val set = { value: Int -> onChange(profile.copy(temperatureC = value.coerceIn(18, 28).toFloat())) }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        PanelTitle("실내 온도", "쉬는 동안 맞출 공조 온도예요. 끝나면 원래 온도로 돌아가요.")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { set(temperature - 1) },
                enabled = enabled && temperature > 18,
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
            ) { Text("−", fontSize = 30.sp) }
            Text(
                "$temperature°C",
                fontSize = 64.sp,
                fontWeight = FontWeight.Light,
                color = NupcarMint,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
            OutlinedButton(
                onClick = { set(temperature + 1) },
                enabled = enabled && temperature < 28,
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
            ) { Text("+", fontSize = 30.sp) }
        }
        Slider(
            value = profile.temperatureC,
            onValueChange = { set(it.roundToInt()) },
            valueRange = 18f..28f,
            enabled = enabled,
        )
    }
}

@Composable
private fun SoundPanel(
    state: NupcarState,
    enabled: Boolean,
    onAlarmChanged: (Boolean) -> Unit,
    onChange: (RestProfile) -> Unit,
) {
    val profile = state.profile
    if (profile.quietWake) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PanelTitle("사운드", "영상 시청 중에는 눕카 사운드를 끄고, 영상 앱의 소리를 그대로 들어요.")
            Text(
                "시청 시간이 끝나면 영상 소리를 방해하지 않도록 기상 사운드 대신 알림으로 알려 드리고, 조명을 밝힌 뒤 등받이를 세워요.",
                fontSize = 16.sp,
                color = NupcarMuted,
                lineHeight = 23.sp,
            )
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PanelTitle("사운드", "쉬는 동안 들을 소리와 끝날 때의 알림을 정해요.")
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
        SettingSwitch(
            title = "호흡 가이드",
            detail = "화면의 원을 따라 5초 들이쉬고 5초 내쉬어요. 사운드도 호흡에 맞춰 커졌다 작아져요.",
            checked = profile.breathingGuide,
            enabled = enabled,
            onChange = { onChange(profile.copy(breathingGuide = it)) },
        )
        SettingSwitch(
            title = "기상 사운드",
            detail = "기기의 알람 음량으로 재생돼요.",
            checked = state.alarmEnabled,
            enabled = enabled,
            onChange = onAlarmChanged,
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp)
            Text(detail, color = NupcarMuted, fontSize = 13.sp)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
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
                    val video = session.profile.quietWake
                    SessionHeading(
                        if (video) "편한 자세로 영상을 즐기세요" else "편안하게 쉬어가요",
                        if (video) "다른 앱으로 가도 시간이 끝나면 알려 드려요." else session.profile.mode.title,
                    )
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
                        Text(if (video) "시청 끝내기" else "지금 일어나기", fontSize = 20.sp)
                    }
                    if (video) VideoAppShortcuts()
                    EmergencyStopButton(onEmergencyStop)
                }
                is RestState.Waking -> {
                    SessionHeading(
                        if (session.profile.quietWake) "시청을 마무리하고 있어요" else "휴식을 마무리하고 있어요",
                        session.stage.title,
                    )
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
        SeatDrawing(seat.recline, Modifier.size(width = 240.dp, height = 150.dp))
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

/** Side view of the seat; [recline] is 0 upright to 1 lying flat over the whole range. */
@Composable
private fun SeatDrawing(recline: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
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
        val degrees = -(12f + recline.coerceIn(0f, 1f) * 78f)
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
                    state.mode.title + " · " + formatMinutes(state.selectedMinutes),
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
    val hours = seconds / 3600L
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, seconds % 3600L / 60L, seconds % 60L)
    } else {
        String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
    }
}

/** "1시간 30분", "45분", "2시간". */
private fun formatMinutes(totalMinutes: Int): String {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0 -> "${minutes}분"
        minutes == 0 -> "${hours}시간"
        else -> "${hours}시간 ${minutes}분"
    }
}

private val WheelItemHeight = 48.dp
private const val WheelVisibleItems = 3

/**
 * Hours and minutes picked on two scrolling wheels, like a phone timer. Any length from
 * [MIN_REST_MINUTES] to [MAX_REST_MINUTES] in one-minute steps; 0 hours 0 minutes becomes 1 minute.
 */
@Composable
private fun TimeWheel(totalMinutes: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val emit = { h: Int, m: Int ->
        onChange((h * 60 + m).coerceIn(MIN_REST_MINUTES, MAX_REST_MINUTES))
    }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        // Band behind the selected row.
        Box(
            Modifier
                .fillMaxWidth()
                .height(WheelItemHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF19343F)),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WheelColumn(count = MAX_REST_MINUTES / 60 + 1, selected = hours, enabled = enabled) { emit(it, minutes) }
            Text("시간", fontSize = 18.sp, color = NupcarMuted)
            Spacer(Modifier.size(20.dp))
            WheelColumn(count = 60, selected = minutes, enabled = enabled) { emit(hours, it) }
            Text("분", fontSize = 18.sp, color = NupcarMuted)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WheelColumn(count: Int, selected: Int, enabled: Boolean, onSelected: (Int) -> Unit) {
    // A blank row above and below lets the first and last values reach the middle row, so the
    // value in the middle is simply the first visible index once the list has snapped.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selected)
    val currentOnSelected by rememberUpdatedState(onSelected)
    val currentSelected by rememberUpdatedState(selected)

    // Follow outside changes, such as switching to a mode with a different saved time.
    LaunchedEffect(selected) {
        if (!listState.isScrollInProgress && listState.firstVisibleItemIndex != selected) {
            listState.scrollToItem(selected)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .collect {
                val centered = listState.firstVisibleItemIndex.coerceIn(0, count - 1)
                if (centered != currentSelected) currentOnSelected(centered)
            }
    }
    val centered by remember {
        derivedStateOf {
            val offsetRows = if (listState.firstVisibleItemScrollOffset > 0) {
                (listState.firstVisibleItemScrollOffset.toFloat() /
                    (listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 1)).roundToInt()
            } else 0
            listState.firstVisibleItemIndex + offsetRows
        }
    }
    LazyColumn(
        state = listState,
        flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
        userScrollEnabled = enabled,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.size(width = 72.dp, height = WheelItemHeight * WheelVisibleItems),
    ) {
        item { Spacer(Modifier.height(WheelItemHeight)) }
        items(count) { value ->
            Box(Modifier.height(WheelItemHeight).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    value.toString().padStart(2, '0'),
                    fontSize = if (value == centered) 30.sp else 22.sp,
                    fontWeight = if (value == centered) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        !enabled -> NupcarMuted
                        value == centered -> NupcarMint
                        else -> NupcarMuted.copy(alpha = 0.6f)
                    },
                )
            }
        }
        item { Spacer(Modifier.height(WheelItemHeight)) }
    }
}

/**
 * Shortcuts to the installed apps that can play video, so the user can start watching in
 * one tap. The session keeps running in the background while another app is open.
 */
@Composable
private fun VideoAppShortcuts() {
    val context = LocalContext.current
    val apps = remember {
        val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/external/video/media/1"), "video/*")
        context.packageManager.queryIntentActivities(probe, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .mapNotNull { pkg ->
                val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
                val label = runCatching {
                    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
                label to launch
            }
            .take(3)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text("영상 앱 열기", fontSize = 16.sp, color = NupcarMuted)
        if (apps.isEmpty()) {
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("홈 화면에서 앱 고르기", fontSize = 18.sp) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                apps.forEach { (label, launch) ->
                    OutlinedButton(
                        onClick = { runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text(label, fontSize = 18.sp, maxLines = 1) }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 1280, heightDp = 720)
@Composable
private fun NupcarPreview() {
    NupcarTheme {
        NupcarScreen(state = NupcarState())
    }
}
