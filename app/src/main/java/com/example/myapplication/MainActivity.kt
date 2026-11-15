package com.example.myapplication

import android.car.Car
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.collector.DebugState
import com.example.myapplication.collector.DrivingCollectorService
import com.example.myapplication.collector.Quality
import com.example.myapplication.collector.Sample
import com.example.myapplication.collector.SensorDebugLog
import com.example.myapplication.collector.ScenarioScript
import com.example.myapplication.collector.TestScenario
import com.example.myapplication.collector.gearName
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var values: TextView
    private lateinit var report: TextView
    private lateinit var logs: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var customScript: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 24, 36, 24)
        }
        root.addView(TextView(this).apply { text = "온길 차량 입력 진단"; textSize = 25f })
        root.addView(TextView(this).apply {
            text = "P에서 기록을 시작한 뒤 Virtual Console의 Motion 값을 바꾸세요. 주행 중 화면은 차량 UX 제한을 따릅니다."
            textSize = 15f
            setPadding(0, 8, 0, 12)
        })
        root.addView(Button(this).apply {
            text = "차량 기록 시작"
            setOnClickListener { requestVehiclePermissions() }
        })
        addScenarioButton(root, TestScenario.SAFE)
        addScenarioButton(root, TestScenario.REPEATED_ACCEL)
        addScenarioButton(root, TestScenario.SUDDEN_STOP)
        root.addView(TextView(this).apply {
            text = "직접 시나리오 작성"
            textSize = 18f
            setPadding(0, 12, 0, 4)
        })
        root.addView(TextView(this).apply {
            text = "한 줄에 구간 하나: 초,기어,도달속도(km/h),가속페달(%),브레이크(%),조향각(°),제한속도(km/h). " +
                "속도는 구간 동안 선형으로 변합니다. 제한속도 미상은 - 로 입력하세요."
            textSize = 13f
        })
        customScript = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = android.view.Gravity.TOP
            minLines = 4
            maxLines = 7
            setText(
                "# 초,기어,도달속도,가속페달,브레이크,조향각,제한속도\n" +
                    "2,D,0,0,0,0,50\n" +
                    "3,D,20,25,0,0,50\n" +
                    "2,D,60,75,0,0,50\n" +
                    "1,D,0,0,85,0,50"
            )
        }
        root.addView(customScript, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (168 * resources.displayMetrics.density).toInt(),
        ))
        root.addView(Button(this).apply {
            text = "직접 작성한 시나리오 실행"
            setOnClickListener { startCustomScenario() }
        })
        root.addView(Button(this).apply {
            text = "기록 종료 및 감점 리포트"
            setOnClickListener { stopAndShowReport() }
        })
        status = TextView(this).apply { textSize = 15f; setPadding(0, 8, 0, 8) }
        root.addView(status)
        values = TextView(this).apply { textSize = 16f; setPadding(0, 8, 0, 8) }
        root.addView(values)
        report = TextView(this).apply { textSize = 15f; setPadding(0, 8, 0, 12) }
        root.addView(report)
        root.addView(TextView(this).apply { text = "최근 센서 로그"; textSize = 18f })
        logs = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(8, 8, 8, 8)
        }
        logScroll = ScrollView(this).apply {
            addView(logs)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        root.addView(logScroll)
        setContentView(root)

        lifecycleScope.launch {
            SensorDebugLog.state.collect(::renderState)
        }
    }

    private fun requestVehiclePermissions() {
        val requested = arrayOf(
            Car.PERMISSION_SPEED,
            Car.PERMISSION_READ_CAR_PEDALS,
            Car.PERMISSION_READ_STEERING_STATE_3P,
        )
        val missing = requested.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        } else startCapture()
    }

    @Deprecated("Migrate to Activity Result API with the Compose screen")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startCapture()
        else status.text = "권한이 허용되지 않았습니다. 센서 로그에서 권한 오류를 확인하세요."
    }

    private fun startCapture() {
        report.text = ""
        DrivingCollectorService.startVehicle(this)
        status.text = "기록 시작 요청을 보냈습니다. Service/Car 연결 및 속성별 결과를 아래 로그에서 확인하세요."
    }

    private fun addScenarioButton(root: LinearLayout, scenario: TestScenario) {
        root.addView(Button(this).apply {
            text = "시나리오 실행: ${scenario.title}"
            setOnClickListener {
                report.text = ""
                DrivingCollectorService.startScenario(this@MainActivity, scenario)
                status.text = "${scenario.expected} 센서 로그에서 시나리오값과 이벤트를 확인하세요."
            }
        })
    }

    private fun startCustomScenario() {
        val script = customScript.text.toString()
        try {
            val phases = ScenarioScript.parse(script)
            report.text = ""
            DrivingCollectorService.startCustomScenario(this, script)
            status.text = "사용자 시나리오 ${phases.size}개 구간 실행 요청 · 첫 구간 속도는 0km/h에서 시작합니다."
        } catch (e: Exception) {
            status.text = "시나리오 입력 오류: ${e.message}"
        }
    }

    private fun stopAndShowReport() {
        val snapshot = SensorDebugLog.state.value
        DrivingCollectorService.stop(this)
        status.text = "기록 종료 요청 · 수집 샘플 ${snapshot.sampleCount}개"
        val penalties = snapshot.events.filter { it.level.name == "PENALTY" }
        report.text = buildString {
            appendLine("이번 주행 점수 ${String.format(Locale.US, "%.1f", snapshot.score)}점")
            if (penalties.isEmpty()) appendLine("감점 이벤트 없음")
            else {
                appendLine("감점 요인")
                penalties.forEach { appendLine("• ${it.message} (-${it.penalty}점)") }
            }
            appendLine("※ 경고·판단불가 이벤트와 입력 누락은 센서 로그를 확인하세요.")
        }
    }

    private fun renderState(state: DebugState) {
        status.text = when {
            state.active -> "기록 중 · 샘플 ${state.sampleCount}개 · 점수 ${String.format(Locale.US, "%.1f", state.score)}점"
            state.sampleCount > 0 -> "기록 종료 · 샘플 ${state.sampleCount}개"
            else -> "대기 중 · 차량 기록 시작을 누르세요"
        }
        state.latest?.let(::renderSample) ?: run { values.text = "센서 샘플 대기 중" }
        logs.text = state.logs.joinToString("\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun renderSample(sample: Sample) {
        fun number(value: Float?, unit: String) = value?.let { "${String.format(Locale.US, "%.2f", it)}$unit" } ?: "null"
        val speed = sample.speedMps?.let { String.format(Locale.US, "%.1f km/h", it * 3.6f) } ?: "null"
        val gear = sample.gear?.let(::gearName) ?: "null"
        fun quality(channel: com.example.myapplication.collector.Channel) = when {
            Quality.isMissing(sample.quality, channel) -> "missing"
            Quality.isStale(sample.quality, channel) -> "stale"
            else -> "ok"
        }
        values.text = buildString {
            appendLine("실시간 입력값 (t=${sample.tMs}ms)")
            appendLine("기어 $gear [${quality(com.example.myapplication.collector.Channel.GEAR)}] · 속도 $speed [${quality(com.example.myapplication.collector.Channel.SPEED)}]")
            appendLine("가속 페달 ${number(sample.acceleratorPct, "%")} [${quality(com.example.myapplication.collector.Channel.ACCELERATOR)}]")
            appendLine("브레이크 ${number(sample.brakePct, "%")} [${quality(com.example.myapplication.collector.Channel.BRAKE)}]")
            appendLine("조향각 ${number(sample.steeringDeg, "°")} [${quality(com.example.myapplication.collector.Channel.STEERING)}]")
            appendLine("제한속도 ${number(sample.speedLimitKmh, " km/h")} [${quality(com.example.myapplication.collector.Channel.SPEED_LIMIT)}]")
            append("quality=0x${sample.quality.toString(16)}")
        }
    }

    private companion object { const val REQUEST_PERMISSIONS = 7001 }
}
