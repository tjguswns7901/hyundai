package com.example.myapplication.collector

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class TestScenario(val title: String, val expected: String) {
    SAFE("안전 주행", "급조작 없이 점수 증가 흐름을 확인합니다."),
    REPEATED_ACCEL("급가속 반복", "첫 급가속 경고와 반복 급가속 감점을 확인합니다."),
    SUDDEN_STOP("제한속도 초과 급제동", "제한속도 50km/h, 급제동 감점을 확인합니다."),
}

/** Deterministic 10Hz samples for exercising the exact same Collector and core engine path. */
class ScenarioSensorSource(
    private val scope: CoroutineScope,
    private val scenario: TestScenario? = null,
    private val customPhases: List<ScenarioPhase>? = null,
) : SensorSource {
    private var job: Job? = null

    override fun start(onValue: (Channel, Float) -> Unit) {
        if (job != null) return
        if (customPhases != null) {
            SensorDebugLog.log("사용자 시나리오 시작 · ${customPhases.size}개 구간")
            job = scope.launch { runCustom(customPhases, onValue) }
            return
        }
        val selected = requireNotNull(scenario) { "TestScenario or customPhases is required" }
        SensorDebugLog.log("시나리오 '${selected.title}' 시작 · ${selected.expected}")
        job = scope.launch {
            var tick = 0
            while (isActive) {
                val frame = frame(tick)
                onValue(Channel.GEAR, frame.gear.toFloat())
                onValue(Channel.SPEED, frame.speedMps)
                onValue(Channel.ACCELERATOR, frame.acceleratorPct)
                onValue(Channel.BRAKE, frame.brakePct)
                onValue(Channel.STEERING, frame.steeringDeg)
                onValue(Channel.SPEED_LIMIT, frame.speedLimitKmh)
                tick++
                if (tick == SCRIPT_TICKS) SensorDebugLog.log("시나리오 입력 완료 · 종료 버튼으로 결과 확인")
                delay(100L)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    private fun frame(tick: Int): Frame = when (scenario ?: TestScenario.SAFE) {
        TestScenario.SAFE -> safeFrame(tick)
        TestScenario.REPEATED_ACCEL -> accelFrame(tick)
        TestScenario.SUDDEN_STOP -> stopFrame(tick)
    }

    private suspend fun runCustom(phases: List<ScenarioPhase>, onValue: (Channel, Float) -> Unit) {
        var startSpeedKmh = 0f
        var emittedTicks = 0
        phases.forEachIndexed { index, phase ->
            val ticks = (phase.durationSec * 10).roundToInt().coerceAtLeast(1)
            repeat(ticks) { offset ->
                val fraction = (offset + 1).toFloat() / ticks
                val speedKmh = startSpeedKmh + (phase.endSpeedKmh - startSpeedKmh) * fraction
                publish(
                    onValue,
                    Frame(
                        gear = phase.gear,
                        speedMps = speedKmh / 3.6f,
                        acceleratorPct = phase.acceleratorPct,
                        brakePct = phase.brakePct,
                        steeringDeg = phase.steeringDeg,
                        speedLimitKmh = phase.speedLimitKmh ?: Float.NaN,
                    ),
                    phase.speedLimitKmh,
                )
                emittedTicks++
                delay(100L)
            }
            startSpeedKmh = phase.endSpeedKmh
            SensorDebugLog.log("사용자 시나리오 구간 ${index + 1}/${phases.size} 완료 · ${phase.durationSec}s, ${phase.endSpeedKmh}km/h")
        }
        SensorDebugLog.log("사용자 시나리오 입력 완료 · ${emittedTicks}개 입력 틱")
    }

    private fun publish(onValue: (Channel, Float) -> Unit, frame: Frame, speedLimitKmh: Float?) {
        onValue(Channel.GEAR, frame.gear.toFloat())
        onValue(Channel.SPEED, frame.speedMps)
        onValue(Channel.ACCELERATOR, frame.acceleratorPct)
        onValue(Channel.BRAKE, frame.brakePct)
        onValue(Channel.STEERING, frame.steeringDeg)
        if (speedLimitKmh != null) onValue(Channel.SPEED_LIMIT, speedLimitKmh)
    }

    private fun safeFrame(tick: Int): Frame {
        val speed = when {
            tick < 50 -> tick * 0.12f             // 0.12 m/s each 100ms: smooth 1.2 m/s²
            tick < 150 -> 6f
            tick < 210 -> (6f - (tick - 150) * 0.1f).coerceAtLeast(0f)
            else -> 0f
        }
        val gear = if (tick < 210) GEAR_D else GEAR_P
        return Frame(gear, speed, if (tick < 50) 18f else 0f, if (tick in 150..209) 12f else 0f, 0f, 50f)
    }

    private fun accelFrame(tick: Int): Frame {
        val rampOne = tick in 10..24
        val rampTwo = tick in 40..54
        val speed = when {
            tick < 10 -> 5f
            tick <= 24 -> 5f + (tick - 9) * 0.4f
            tick < 40 -> 11f
            tick <= 54 -> 11f + (tick - 39) * 0.4f
            tick < 75 -> 17f
            tick < 110 -> (17f - (tick - 75) * 0.5f).coerceAtLeast(0f)
            else -> 0f
        }
        val gear = if (tick < 110) GEAR_D else GEAR_P
        val accelerator = if (rampOne || rampTwo) 70f else 0f
        val brake = if (tick in 75..109) 25f else 0f
        return Frame(gear, speed, accelerator, brake, 0f, 50f)
    }

    private fun stopFrame(tick: Int): Frame {
        val speed = when {
            tick < 20 -> 20f                        // 72km/h, over the 50km/h limit
            tick < 50 -> (20f - (tick - 19) * 0.7f).coerceAtLeast(0f)
            else -> 0f
        }
        val gear = if (tick < 50) GEAR_D else GEAR_P
        return Frame(gear, speed, 0f, if (tick in 20..49) 85f else 0f, 0f, 50f)
    }

    private data class Frame(
        val gear: Int,
        val speedMps: Float,
        val acceleratorPct: Float,
        val brakePct: Float,
        val steeringDeg: Float,
        val speedLimitKmh: Float,
    )

    private companion object {
        const val SCRIPT_TICKS = 220
        const val GEAR_P = 4
        const val GEAR_D = 8
    }
}

data class ScenarioPhase(
    val durationSec: Double,
    val gear: Int,
    val endSpeedKmh: Float,
    val acceleratorPct: Float,
    val brakePct: Float,
    val steeringDeg: Float,
    val speedLimitKmh: Float?,
)

/** CSV format per row: seconds,gear,endSpeedKmh,acceleratorPct,brakePct,steeringDeg,speedLimitKmh */
object ScenarioScript {
    fun parse(text: String): List<ScenarioPhase> {
        val phases = text.lineSequence()
            .mapIndexedNotNull { index, raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@mapIndexedNotNull null
                val fields = line.split(',').map(String::trim)
                require(fields.size == 7) { "${index + 1}행: 쉼표로 구분한 값 7개가 필요합니다." }
                fun number(column: Int, label: String): Float =
                    fields[column].toFloatOrNull() ?: error("${index + 1}행: $label 숫자를 확인하세요.")
                val duration = fields[0].toDoubleOrNull()
                    ?: error("${index + 1}행: 구간 시간 숫자를 확인하세요.")
                require(duration in 0.1..120.0) { "${index + 1}행: 구간 시간은 0.1~120초여야 합니다." }
                val gear = when (fields[1].uppercase()) {
                    "P" -> 4; "R" -> 2; "N" -> 1; "D" -> 8
                    else -> error("${index + 1}행: 기어는 P/R/N/D 중 하나여야 합니다.")
                }
                val endSpeed = number(2, "도달 속도")
                val accelerator = number(3, "가속 페달")
                val brake = number(4, "브레이크")
                val steering = number(5, "조향각")
                val limit = fields[6].takeUnless { it.isBlank() || it == "-" }
                    ?.toFloatOrNull() ?: if (fields[6].isBlank() || fields[6] == "-") null
                    else error("${index + 1}행: 제한속도 숫자 또는 - 를 입력하세요.")
                require(endSpeed in 0f..250f) { "${index + 1}행: 속도는 0~250km/h여야 합니다." }
                require(accelerator in 0f..100f && brake in 0f..100f) { "${index + 1}행: 페달 값은 0~100%여야 합니다." }
                require(steering in -45f..45f) { "${index + 1}행: 조향각은 -45~45도여야 합니다." }
                require(limit == null || limit in 1f..200f) { "${index + 1}행: 제한속도는 1~200km/h 또는 - 여야 합니다." }
                ScenarioPhase(duration, gear, endSpeed, accelerator, brake, steering, limit)
            }.toList()
        require(phases.isNotEmpty()) { "시나리오 구간을 한 줄 이상 입력하세요." }
        require(phases.sumOf { it.durationSec } <= 600.0) { "전체 시나리오는 10분 이하여야 합니다." }
        return phases
    }
}
