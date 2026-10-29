package ai.pleos.playground.sample.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

private typealias IntSeatCapability = Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>
private typealias FloatSeatCapability = Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Float>>>
private typealias SeatAreaCapability = Pair<CapabilityConfig, List<SeatArea>>

/** Read-only runtime checks against the actual Vehicle 2.3.2 API. */
internal class NupcarVehicleProbe(context: Context, private val onReport: (String) -> Unit) {
    private val vehicle = Vehicle(context.applicationContext)
    private val handler = Handler(Looper.getMainLooper())
    private val lines = mutableListOf<String>()
    private val requests = mutableMapOf<Long, Runnable>()
    private var nextRequest = 0L
    private var closed = false
    private var initialized = false

    fun inspect() {
        if (closed || requests.isNotEmpty()) return
        lines.clear()
        emit("눕카 차량 기능 점검 / 운전석 ROW_1_LEFT")
        emit("지원 범위와 현재값을 읽습니다. 차량 설정은 변경하지 않습니다.")
        try {
            if (!initialized) {
                vehicle.initialize()
                initialized = true
            }
        } catch (e: Exception) {
            emit("SDK 초기화 실패: ${describe(e)}")
            return
        }

        val seat = vehicle.getSeat()
        val hvac = vehicle.getHVAC()
        val light = vehicle.getLight()

        call<SeatAreaCapability>("공조 전원 지원", { ok, fail ->
            hvac.checkHvacPowerCapabilities({ config, areas -> ok(config to areas) }, fail)
        }) { (config, areas) ->
            emit("[공조 전원] ${config.accessType}; 영역=${areaNames(areas)}")
            val area = areas.firstOrNull { SeatValue.ROW_1_LEFT in it.values }
            if (canRead(config) && area != null) call<Boolean?>("공조 현재 전원", { ok, fail ->
                hvac.getHvacPowerState(area, ok, fail)
            }) { emit("[공조 현재 전원] ${it?.let { value -> if (value) "ON" else "OFF" } ?: "현재값 없음"}") }
        }

        call<SeatAreaCapability>("시트 등받이 이동 지원", { ok, fail ->
            seat.checkMoveSeatBackrestAngleCapabilities({ config, areas ->
                ok(config to areas)
            }, fail)
        }) { (config, areas) ->
            emit("[시트 등받이 이동] ${config.accessType}; 영역=${areaNames(areas)}")
            emit("  이동은 BACKWARD / FORWARD / STOP 명령입니다.")
        }

        call<IntSeatCapability>("시트 등받이 위치 지원", { ok, fail ->
            seat.checkSeatBackrestAnglePositionCapabilities({ config, bounds ->
                ok(config to bounds)
            }, fail)
        }) { (config, bounds) ->
            val area = readableArea("시트 등받이 위치", config, bounds)
            if (area != null) call<Int?>("시트 등받이 현재 위치", { ok, fail ->
                seat.getCurrentSeatBackrestAnglePosition(area, ok, fail)
            }) { emit("[시트 등받이 현재 위치] ${it ?: "현재값 없음"} (SDK 위치 단위)") }
        }

        call<IntSeatCapability>("시트 앞뒤 위치 지원", { ok, fail ->
            seat.checkSeatForeAftPositionCapabilities({ config, bounds ->
                ok(config to bounds)
            }, fail)
        }) { (config, bounds) ->
            val area = readableArea("시트 앞뒤 위치", config, bounds)
            if (area != null) call<Int?>("시트 앞뒤 현재 위치", { ok, fail ->
                seat.getCurrentSeatForeAftPosition(area, ok, fail)
            }) { emit("[시트 앞뒤 현재 위치] ${it ?: "현재값 없음"} (SDK 위치 단위)") }
        }

        call<FloatSeatCapability>("공조 설정 온도 지원", { ok, fail ->
            hvac.checkHvacTemperatureCapabilities({ config, bounds, _ ->
                ok(config to bounds)
            }, fail)
        }) { (config, bounds) ->
            val area = readableArea("공조 설정 온도", config, bounds)
            if (area != null) call<Float?>("공조 현재 설정 온도", { ok, fail ->
                hvac.getHvacTemperature(area, TemperatureUnit.CELSIUS, ok, fail)
            }) { emit("[공조 현재 설정 온도] ${it?.let { value -> "$value °C" } ?: "현재값 없음"}") }
        }

        call<IntSeatCapability>("공조 풍량 지원", { ok, fail ->
            hvac.checkHvacFanSpeedCapabilities({ config, bounds -> ok(config to bounds) }, fail)
        }) { (config, bounds) ->
            val area = readableArea("공조 풍량", config, bounds)
            if (area != null) call<Int?>("공조 현재 풍량", { ok, fail ->
                hvac.getHvacFanSpeed(area, ok, fail)
            }) { emit("[공조 현재 풍량] ${it ?: "현재값 없음"}") }
        }

        call<IntSeatCapability>("앰비언트 밝기 지원", { ok, fail ->
            light.checkAmbientLightBrightnessCapabilities({ config, bounds ->
                ok(config to bounds)
            }, fail)
        }) { (config, bounds) ->
            val area = readableArea("앰비언트 밝기", config, bounds)
            if (area != null) call<Int?>("앰비언트 현재 밝기", { ok, fail ->
                light.getAmbientLightBrightness(area, ok, fail)
            }) { emit("[앰비언트 현재 밝기] ${it ?: "현재값 없음"}") }
        }

        call<SeatAreaCapability>("앰비언트 색상 지원", { ok, fail ->
            light.checkAmbientLightBrightnessColorCapabilities({ config, areas ->
                ok(config to areas)
            }, fail)
        }) { (config, areas) ->
            emit("[앰비언트 색상] ${config.accessType}; 영역=${areaNames(areas)}")
            val area = areas.firstOrNull { SeatValue.ROW_1_LEFT in it.values }
            if (canRead(config) && area != null) call<Int?>("앰비언트 현재 색상", { ok, fail ->
                light.getAmbientLightBrightnessColor(area, ok, fail)
            }) {
                val color = it?.let { value ->
                    "#" + Integer.toHexString(value).uppercase(Locale.ROOT).padStart(8, '0')
                } ?: "현재값 없음"
                emit("[앰비언트 현재 색상] $color (ARGB)")
            }
        }
    }

    private fun <T> readableArea(
        label: String,
        config: CapabilityConfig,
        bounds: Map<SeatArea, AreaBounds<T>>,
    ): SeatArea? {
        emit("[$label] ${config.accessType}")
        bounds.forEach { (area, range) ->
            emit("  ${areaNames(listOf(area))}: ${range.minValue} .. ${range.maxValue}")
        }
        val area = bounds.keys.firstOrNull { SeatValue.ROW_1_LEFT in it.values }
        if (area == null) emit("  운전석 영역이 보고되지 않았습니다.")
        return area?.takeIf { canRead(config) }
    }

    private fun canRead(config: CapabilityConfig): Boolean =
        config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE

    private fun areaNames(areas: List<SeatArea>): String = areas
        .flatMap { it.values.toList() }.map { it.name }.distinct().joinToString(", ")
        .ifBlank { "없음" }

    private fun <T> call(
        label: String,
        start: (onComplete: (T) -> Unit, onFailed: (Exception) -> Unit) -> Unit,
        onComplete: (T) -> Unit,
    ) {
        if (closed) return
        val id = ++nextRequest
        val timeout = Runnable {
            finish(id) { emit("[$label] 응답 시간 초과. 연결/권한/서비스 상태를 확인해 주세요.") }
        }
        requests[id] = timeout
        handler.postDelayed(timeout, 8_000L)
        val fail: (Exception) -> Unit = { e ->
            handler.post { finish(id) { emit("[$label] 실패: ${describe(e)}") } }
        }
        try {
            start({ value -> handler.post { finish(id) { onComplete(value) } } }, fail)
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun finish(id: Long, action: () -> Unit) {
        if (closed) return
        val timeout = requests.remove(id) ?: return
        handler.removeCallbacks(timeout)
        try {
            action()
        } catch (e: Exception) {
            emit("결과 처리 실패: ${describe(e)}")
        }
        if (requests.isEmpty()) emit("점검 완료. 변경 명령의 실제 반영 여부는 별도 테스트가 필요합니다.")
    }

    private fun emit(line: String) {
        if (closed) return
        lines += line
        Log.i("NupcarVehicleProbe", line)
        onReport(lines.joinToString("\n"))
    }

    private fun describe(e: Exception): String = "${e.javaClass.simpleName}: ${e.message ?: "상세 내용 없음"}"

    fun close() {
        if (closed) return
        closed = true
        requests.values.forEach { handler.removeCallbacks(it) }
        requests.clear()
        if (initialized) runCatching { vehicle.release() }
    }
}
