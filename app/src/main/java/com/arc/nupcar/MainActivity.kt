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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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
import com.arc.nupcar.ui.theme.NupcarTypography
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
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
                        fontSize = 17.sp,
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
                    Text("Pleos 에뮬레이터용입니다. 원래 값을 읽은 후 변경하고, 재조회로 복원을 확인합니다.", fontSize = 16.sp)
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
                            fontSize = 16.sp,
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
    // One soft, rounded-looking face everywhere, and every sp a little larger for the car screen.
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * 1.12f)) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = NupcarMint,
                onPrimary = NupcarBackground,
                background = NupcarBackground,
                onBackground = Color(0xFFEAF5F5),
                surface = NupcarPanel,
                onSurface = Color(0xFFEAF5F5),
            ),
            typography = NupcarTypography,
            content = content,
        )
    }
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
    var tab by rememberSaveable { mutableStateOf<SettingTab?>(SettingTab.TIME) }
    var modeMenuOpen by rememberSaveable { mutableStateOf(false) }
    val idle = session is RestState.Idle
    val previewing = state.phase != NupcarPhase.READY
    val canEdit = idle && !previewing
    val sessionProfile = when (session) {
        is RestState.Preparing -> session.profile
        is RestState.Resting -> session.profile
        is RestState.Waking -> session.profile
        else -> null
    }
    val sceneMode = sessionProfile?.mode ?: state.mode
    val sceneLight = when (session) {
        // The cabin brightens while waking, as the real ambient light does.
        is RestState.Waking -> WAKE_SCENE_LIGHT
        else -> (sessionProfile ?: state.profile).ambientRatio
    }

    Surface(modifier = Modifier.fillMaxSize(), color = NupcarBackground) {
        Box(modifier = Modifier.fillMaxSize()) {
            SceneBackground(mode = sceneMode, light = sceneLight)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(28.dp),
            ) {
                // Top left: the app and the current mode, which opens the mode list.
                Column(
                    modifier = Modifier.align(Alignment.TopStart),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ModeChip(
                        mode = state.mode,
                        enabled = canEdit,
                        open = modeMenuOpen,
                        onClick = { modeMenuOpen = !modeMenuOpen },
                        onLongClick = {
                            if (BuildConfig.DEBUG && canEdit) onOpenVehicleInspector()
                        },
                    )
                    AnimatedVisibility(
                        visible = modeMenuOpen && canEdit,
                        enter = fadeIn(tween(200)) + slideInVertically(tween(240)) { -it / 6 },
                        exit = fadeOut(tween(150)),
                    ) {
                        ModeMenu(selected = state.mode) {
                            onModeSelected(it)
                            modeMenuOpen = false
                        }
                    }
                }

                Text(
                    sceneHint(session, canEdit),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp),
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 17.sp,
                )

                // Top right: start, or what the session is doing.
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    if (canEdit) {
                        StartChip(onStart = onStart, onPreview = onPreview)
                    } else if (!idle) {
                        GlassChip { Text(sessionBadge(session), fontSize = 16.sp, color = NupcarMint) }
                    }
                }

                // The scene: the mode and the seat, or the running session.
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        // Idle leaves room for the setting panel; a session keeps the timer in the middle.
                        .padding(bottom = if (idle && !previewing) 260.dp else 60.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        previewing -> SessionPanel(
                            state = state,
                            onStart = onStart,
                            onPreview = onPreview,
                            onPause = onPause,
                            onResume = onResume,
                            onFinish = onFinish,
                            modifier = Modifier.width(560.dp),
                        )
                        idle -> IdleScene(state)
                        else -> SessionScene(session)
                    }
                }

                if (!idle && sessionShowsSeat(session)) {
                    SeatCorner(seat, Modifier.align(Alignment.BottomStart))
                }

                // Bottom: the chosen tab's controls rise above the floating bar.
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (idle && !previewing) {
                        AnimatedContent(
                            targetState = tab,
                            transitionSpec = {
                                (slideInVertically(tween(320)) { it / 4 } + fadeIn(tween(320))) togetherWith
                                    fadeOut(tween(140))
                            },
                            label = "setting-panel",
                        ) { current ->
                            if (current != null) {
                                Box(
                                    modifier = Modifier
                                        .width(980.dp)
                                        .glass(RoundedCornerShape(28.dp))
                                        .padding(horizontal = 36.dp, vertical = 28.dp),
                                ) {
                                    when (current) {
                                        SettingTab.TIME -> TimePanel(state, canEdit, onMinutesSelected)
                                        SettingTab.SEAT -> SeatPanel(state.profile, canEdit, onProfileChanged)
                                        SettingTab.LIGHT -> LightPanel(state.profile, canEdit, onProfileChanged)
                                        SettingTab.CLIMATE -> ClimatePanel(state.profile, canEdit, onProfileChanged)
                                        SettingTab.SOUND -> SoundPanel(state, canEdit, onAlarmChanged, onProfileChanged)
                                    }
                                }
                            } else {
                                Spacer(Modifier.height(0.dp))
                            }
                        }
                        SettingBar(selected = tab, state = state) { tab = if (tab == it) null else it }
                    } else if (!idle) {
                        if (session is RestState.Resting && session.profile.quietWake) {
                            Box(
                                modifier = Modifier
                                    .width(620.dp)
                                    .glass(RoundedCornerShape(28.dp))
                                    .padding(24.dp),
                            ) { VideoAppShortcuts() }
                        }
                        SessionBar(session, onWakeNow, onEmergencyStop, onRestoreSeat, onSessionDone)
                    }
                }
            }
        }
    }
}

/** How bright the scene gets while waking, matching the light the wake-up ramps to. */
private const val WAKE_SCENE_LIGHT = 0.7f

private fun sceneHint(session: RestState, canEdit: Boolean): String = when {
    canEdit -> "기어가 P일 때만 시작돼요 · 아래에서 항목을 고르면 바로 위에서 조정해요"
    session is RestState.Idle -> ""
    session is RestState.Finished || session is RestState.Rejected || session is RestState.Interrupted -> ""
    else -> "기어가 P에서 바뀌면 시트가 바로 멈춰요"
}

private fun sessionBadge(session: RestState): String = when (session) {
    is RestState.Preparing -> "준비 중"
    is RestState.Resting -> if (session.profile.quietWake) "시청 중" else "휴식 중"
    is RestState.Waking -> "마무리 중"
    is RestState.RestoringSeat -> "시트 복원 중"
    is RestState.Finished -> "완료"
    is RestState.Interrupted -> "중단됨"
    is RestState.Rejected -> "시작 안 함"
    RestState.Idle -> ""
}

/** Translucent panel over the scene, with a hairline edge. */
private fun Modifier.glass(shape: Shape): Modifier = this
    .clip(shape)
    .background(Color(0xFF07131B).copy(alpha = 0.62f))
    .border(1.dp, Color.White.copy(alpha = 0.14f), shape)

@Composable
private fun GlassChip(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .glass(RoundedCornerShape(50))
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}

/**
 * Full-screen backdrop for the scene: a sky per mode (night for a nap, dawn for meditation,
 * a dim theatre for video) and a warm glow from above that follows the cabin light.
 */
@Composable
private fun SceneBackground(mode: NupcarMode, light: Float) {
    val colors = when (mode) {
        NupcarMode.NAP -> listOf(Color(0xFF050A19), Color(0xFF0F1D3F), Color(0xFF26386A))
        NupcarMode.MEDITATION -> listOf(Color(0xFF071820), Color(0xFF1C4048), Color(0xFFB28A6E))
        NupcarMode.VIDEO -> listOf(Color(0xFF030307), Color(0xFF110C22), Color(0xFF3A2470))
    }
    val top by animateColorAsState(colors[0], tween(900), label = "sky-top")
    val middle by animateColorAsState(colors[1], tween(900), label = "sky-middle")
    val horizon by animateColorAsState(colors[2], tween(900), label = "sky-horizon")
    val glow by animateFloatAsState(light.coerceIn(0f, 1f), tween(900), label = "cabin-light")
    val starAlpha by animateFloatAsState(if (mode == NupcarMode.MEDITATION) 0.15f else 0.7f, tween(900), label = "stars")
    val stars = remember {
        val random = kotlin.random.Random(7)
        List(90) { Triple(random.nextFloat(), random.nextFloat() * 0.6f, 0.6f + random.nextFloat() * 1.6f) }
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(top, middle, horizon)))
        drawRect(
            Brush.radialGradient(
                listOf(horizon.copy(alpha = 0.55f), Color.Transparent),
                center = Offset(size.width / 2f, size.height * 1.08f),
                radius = size.width * 0.65f,
            )
        )
        stars.forEach { (x, y, r) ->
            drawCircle(Color.White.copy(alpha = starAlpha * (0.35f + r / 4f)), r, Offset(x * size.width, y * size.height))
        }
        drawRect(
            Brush.radialGradient(
                listOf(Color(0xFFFFE2A8).copy(alpha = 0.04f + 0.32f * glow), Color.Transparent),
                center = Offset(size.width / 2f, 0f),
                radius = size.height * 0.95f,
            )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModeChip(
    mode: NupcarMode,
    enabled: Boolean,
    open: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .glass(RoundedCornerShape(50))
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("눕카", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.size(width = 1.dp, height = 20.dp).background(Color.White.copy(alpha = 0.25f)))
        Text(mode.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (enabled) Text(if (open) "⌃" else "⌄", fontSize = 18.sp, color = NupcarMuted)
    }
}

@Composable
private fun ModeMenu(selected: NupcarMode, onSelected: (NupcarMode) -> Unit) {
    Column(
        modifier = Modifier
            .width(360.dp)
            .glass(RoundedCornerShape(22.dp))
            .padding(8.dp),
    ) {
        NupcarMode.values().forEach { mode ->
            val chosen = mode == selected
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (chosen) NupcarMint.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelected(mode) }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(mode.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (chosen) NupcarMint else Color.White)
                Text(mode.description, fontSize = 17.sp, color = NupcarMuted)
            }
        }
    }
}

@Composable
private fun StartChip(onStart: () -> Unit, onPreview: () -> Unit) {
    Row(
        modifier = Modifier
            .glass(RoundedCornerShape(50))
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TextButton(onClick = onPreview, shape = RoundedCornerShape(50)) {
            Text("알림 10초 체험", fontSize = 17.sp, color = NupcarMuted)
        }
        Button(
            onClick = onStart,
            shape = RoundedCornerShape(50),
            modifier = Modifier.height(52.dp),
            contentPadding = PaddingValues(horizontal = 30.dp),
        ) {
            Text("쉬기 시작", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun IdleScene(state: NupcarState) {
    val profile = state.profile
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(state.mode.title, fontSize = 52.sp, fontWeight = FontWeight.Light)
        Text(state.mode.description, fontSize = 17.sp, color = Color.White.copy(alpha = 0.72f))
        SeatDrawing(
            recline = profile.reclineRatio.coerceIn(0f, 1f) * MAX_RECLINE_SHARE,
            modifier = Modifier.padding(top = 12.dp).size(width = 380.dp, height = 240.dp),
        )
        Text(
            listOf(
                formatMinutes(state.selectedMinutes),
                "등받이 ${(profile.reclineRatio * 100).roundToInt()}%",
                "조명 ${(profile.ambientRatio * 100).roundToInt()}%",
                "${profile.temperatureC.roundToInt()}°C",
            ).joinToString("  ·  "),
            fontSize = 16.sp,
            color = Color.White.copy(alpha = 0.7f),
        )
    }
}

/** Whether the session screen shows the seat: whenever the seat may be away from where it started. */
private fun sessionShowsSeat(session: RestState): Boolean =
    session is RestState.Preparing || session is RestState.Resting || session is RestState.Waking ||
        session is RestState.RestoringSeat || session is RestState.Interrupted

/** The running session in the middle of the scene: what is happening and, while resting, a large timer. */
@Composable
private fun SessionScene(session: RestState) {
    Column(
        modifier = Modifier.width(760.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (session) {
            RestState.Idle -> Unit
            is RestState.Preparing -> SessionHeading("휴식 환경을 준비하고 있어요", "시트가 천천히 움직여요.")
            is RestState.Resting -> {
                val video = session.profile.quietWake
                SessionHeading(
                    if (video) "편한 자세로 영상을 즐기세요" else "편안하게 쉬어가요",
                    if (video) "다른 앱으로 가도 시간이 끝나면 알려 드려요." else session.profile.mode.title,
                )
                if (session.profile.breathingGuide) {
                    BreathingGuide(session.soundStartedAtMillis) { RestTimer(session.remainingMillis) }
                } else {
                    Text(
                        formatNupcarTime(session.remainingMillis),
                        fontSize = 150.sp,
                        fontWeight = FontWeight.ExtraLight,
                        color = NupcarMint,
                    )
                }
            }
            is RestState.Waking -> SessionHeading(
                if (session.profile.quietWake) "시청을 마무리하고 있어요" else "휴식을 마무리하고 있어요",
                session.stage.title,
            )
            is RestState.RestoringSeat -> SessionHeading("시트를 되돌리고 있어요", session.message)
            is RestState.Finished -> {
                SessionHeading("휴식이 끝났어요", "시트와 차량 환경을 휴식 전 상태로 되돌렸어요.")
                SessionWarnings(session.warnings)
            }
            is RestState.Interrupted -> {
                SessionHeading("휴식이 중단되었어요", session.reason)
                if (session.canRestoreSeat && !session.restoreFailed) {
                    Text(
                        "시트를 휴식 전 위치로 되돌린 뒤 처음 화면으로 돌아가요.",
                        fontSize = 17.sp,
                        color = NupcarMint,
                    )
                }
                SessionWarnings(session.warnings)
            }
            is RestState.Rejected -> SessionHeading("시작하지 않았어요", session.reason)
        }
    }
}

/** A small view of the seat for the corner of the session screen, so the timer keeps the middle. */
@Composable
private fun SeatCorner(seat: SeatView, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .glass(RoundedCornerShape(24.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SeatDrawing(seat.recline, Modifier.size(width = 200.dp, height = 126.dp))
        Text("등받이 ${(seat.settingRatio * 100).roundToInt()}% (안전 범위 기준)", fontSize = 17.sp, color = NupcarMint)
        Text(
            if (seat.simulated) "시각화 화면 · 에뮬레이터 값 고정" else "실제 시트 위치",
            fontSize = 17.sp,
            color = Color.White.copy(alpha = 0.6f),
        )
    }
}

/** The floating bar of setting tabs; tapping the open tab again closes its panel. */
@Composable
private fun SettingBar(selected: SettingTab?, state: NupcarState, onSelect: (SettingTab) -> Unit) {
    Row(
        modifier = Modifier
            .glass(RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SettingTab.values().forEach { item ->
            val chosen = item == selected
            Column(
                modifier = Modifier
                    .width(124.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (chosen) NupcarMint.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(item) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TabIcon(item, if (chosen) NupcarMint else Color.White, Modifier.size(28.dp))
                Text(item.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (chosen) NupcarMint else Color.White)
                Text(tabValue(item, state), fontSize = 17.sp, maxLines = 1, color = NupcarMuted)
            }
        }
    }
}

/** Session actions in the same floating bar shape as the setting tabs. */
@Composable
private fun SessionBar(
    session: RestState,
    onWakeNow: () -> Unit,
    onEmergencyStop: () -> Unit,
    onRestoreSeat: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier
            .glass(RoundedCornerShape(50))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val pill = Modifier.height(60.dp)
        val moving = session is RestState.Preparing || session is RestState.Resting ||
            session is RestState.Waking || session is RestState.RestoringSeat
        if (session is RestState.Resting) {
            OutlinedButton(onClick = onWakeNow, shape = RoundedCornerShape(50), modifier = pill.width(240.dp)) {
                Text(if (session.profile.quietWake) "시청 끝내기" else "지금 일어나기", fontSize = 19.sp)
            }
        }
        if (moving) {
            Button(
                onClick = onEmergencyStop,
                shape = RoundedCornerShape(50),
                modifier = pill.width(240.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD9483F), contentColor = Color.White),
            ) { Text("즉시 중단", fontSize = 19.sp, fontWeight = FontWeight.Bold) }
        }
        if (session is RestState.Interrupted && session.canRestoreSeat) {
            Button(onClick = onRestoreSeat, shape = RoundedCornerShape(50), modifier = pill.width(260.dp)) {
                Text(if (session.restoreFailed) "다시 시도" else "시트 되돌리기", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
        }
        // A reclined seat must be restored before going back; leaving it as it is is offered
        // only once restoring has failed, so the screen can never trap the user.
        val interruptedCanLeave = session is RestState.Interrupted &&
            (!session.canRestoreSeat || session.restoreFailed)
        if (session is RestState.Finished || session is RestState.Rejected || interruptedCanLeave) {
            OutlinedButton(onClick = onDone, shape = RoundedCornerShape(50), modifier = pill.width(300.dp)) {
                Text(
                    if (session is RestState.Interrupted && session.canRestoreSeat) "시트는 그대로 두고 돌아가기"
                    else "확인하고 돌아가기",
                    fontSize = 19.sp,
                )
            }
        }
    }
}

/** Simple line icons drawn in code, so the bar needs no icon library or image files. */
@Composable
private fun TabIcon(tab: SettingTab, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val stroke = Stroke(width = w * 0.08f, cap = StrokeCap.Round)
        when (tab) {
            SettingTab.TIME -> {
                drawCircle(color, w * 0.4f, center, style = stroke)
                drawLine(color, center, Offset(center.x, w * 0.24f), stroke.width, StrokeCap.Round)
                drawLine(color, center, Offset(w * 0.68f, center.y), stroke.width, StrokeCap.Round)
            }
            SettingTab.SEAT -> {
                drawLine(color, Offset(w * 0.3f, w * 0.68f), Offset(w * 0.86f, w * 0.68f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.3f, w * 0.68f), Offset(w * 0.16f, w * 0.18f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.5f, w * 0.68f), Offset(w * 0.5f, w * 0.9f), stroke.width, StrokeCap.Round)
            }
            SettingTab.LIGHT -> {
                drawCircle(color, w * 0.18f, center, style = stroke)
                repeat(8) { i ->
                    val a = i * PI.toFloat() / 4f
                    val inner = w * 0.3f
                    val outer = w * 0.44f
                    drawLine(
                        color,
                        Offset(center.x + inner * cos(a), center.y + inner * sin(a)),
                        Offset(center.x + outer * cos(a), center.y + outer * sin(a)),
                        stroke.width,
                        StrokeCap.Round,
                    )
                }
            }
            SettingTab.CLIMATE -> {
                drawRoundRect(
                    color,
                    topLeft = Offset(w * 0.4f, w * 0.08f),
                    size = Size(w * 0.2f, w * 0.56f),
                    cornerRadius = CornerRadius(w * 0.1f),
                    style = stroke,
                )
                drawCircle(color, w * 0.17f, Offset(center.x, w * 0.76f))
            }
            SettingTab.SOUND -> {
                val speaker = Path().apply {
                    moveTo(w * 0.1f, w * 0.38f)
                    lineTo(w * 0.28f, w * 0.38f)
                    lineTo(w * 0.48f, w * 0.18f)
                    lineTo(w * 0.48f, w * 0.82f)
                    lineTo(w * 0.28f, w * 0.62f)
                    lineTo(w * 0.1f, w * 0.62f)
                    close()
                }
                drawPath(speaker, color, style = stroke)
                drawArc(color, -45f, 90f, false, Offset(w * 0.42f, w * 0.3f), Size(w * 0.3f, w * 0.4f), style = stroke)
                drawArc(color, -50f, 100f, false, Offset(w * 0.4f, w * 0.14f), Size(w * 0.5f, w * 0.72f), style = stroke)
            }
        }
    }
}

/** The setting tabs on the floating bar; the chosen one opens its panel above the bar. */
private enum class SettingTab(val title: String) {
    TIME("시간"),
    SEAT("시트"),
    LIGHT("조명"),
    CLIMATE("온도"),
    SOUND("사운드"),
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
private fun PanelTitle(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(detail, fontSize = 17.sp, color = NupcarMuted, lineHeight = 21.sp)
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
        val current = state.selectedMinutes
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { onMinutesSelected((current - 10).coerceAtLeast(MIN_REST_MINUTES)) },
                enabled = enabled && current > MIN_REST_MINUTES,
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier.width(104.dp).height(72.dp),
            ) { Text("−10분", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
            Box(Modifier.weight(1f)) {
                TimeWheel(totalMinutes = current, enabled = enabled, onChange = onMinutesSelected)
            }
            OutlinedButton(
                onClick = { onMinutesSelected((current + 10).coerceAtMost(MAX_REST_MINUTES)) },
                enabled = enabled && current < MAX_REST_MINUTES,
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier.width(104.dp).height(72.dp),
            ) { Text("+10분", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
    }
}

@Composable
private fun SeatPanel(profile: RestProfile, enabled: Boolean, onChange: (RestProfile) -> Unit) {
    // The seat in the scene above follows this setting, so the panel holds only the controls.
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelTitle("등받이", "안전 범위(최대 70%) 안에서 얼마나 눕힐지 정해요. 위의 그림이 바로 따라 움직여요.")
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

@Composable
private fun LightPanel(profile: RestProfile, enabled: Boolean, onChange: (RestProfile) -> Unit) {
    // The glow at the top of the scene follows this setting.
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelTitle("실내 조명", "쉬는 동안의 앰비언트 조명 밝기예요. 화면 위쪽 빛이 함께 바뀌고, 끝날 때는 서서히 밝아져요.")
        DetailSlider("밝기", "${(profile.ambientRatio * 100).roundToInt()}%", profile.ambientRatio, 0f..1f, enabled) {
            onChange(profile.copy(ambientRatio = it))
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
            Text(detail, color = NupcarMuted, fontSize = 16.sp)
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
    warnings.forEach { Text(it, color = Color(0xFFFFC8AE), fontSize = 17.sp, lineHeight = 21.sp) }
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
        fontSize = 96.sp,
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
                .size(440.dp)
                .scale(0.7f + 0.3f * BreathCycle.level(elapsed))
                .background(Color(0xFF19343F), CircleShape),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (inhaling) "들이쉬세요" else "내쉬세요",
                fontSize = 30.sp,
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
                Text(it, color = Color(0xFFFFC8AE), fontSize = 17.sp, lineHeight = 21.sp)
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
                    fontSize = 17.sp,
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
