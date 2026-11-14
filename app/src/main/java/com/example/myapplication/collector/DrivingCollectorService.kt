package com.example.myapplication.collector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ongil.core.Event
import com.ongil.core.Gear
import com.ongil.core.OnGilEngine
import com.ongil.core.Sample as EngineSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class DrivingCollectorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collector: Collector? = null
    private var analysisJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SensorDebugLog.log("종료 요청 처리")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (collector != null) return START_STICKY

        SensorDebugLog.start()
        startAsForeground()
        val source = when (intent?.action) {
            ACTION_START_VEHICLE -> {
                SensorDebugLog.log("차량 에뮬레이터 수집 시작")
                CarPropertySensorSource(applicationContext)
            }
            ACTION_START_SCENARIO -> {
                val scenario = intent.getStringExtra(EXTRA_SCENARIO)
                    ?.let { runCatching { TestScenario.valueOf(it) }.getOrNull() }
                    ?: TestScenario.SAFE
                ScenarioSensorSource(scope, scenario)
            }
            ACTION_START_CUSTOM_SCENARIO -> {
                val script = intent.getStringExtra(EXTRA_CUSTOM_SCRIPT).orEmpty()
                val phases = try {
                    ScenarioScript.parse(script)
                } catch (e: Exception) {
                    SensorDebugLog.log("사용자 시나리오 오류: ${e.message}")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                ScenarioSensorSource(scope, customPhases = phases)
            }
            else -> ScenarioSensorSource(scope, TestScenario.SAFE)
        }
        analysisJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val engine = OnGilEngine()
            stream.collect { sample ->
                val generated = engine.onSample(sample.toEngineSample())
                SensorDebugLog.sample(sample, engine.score, generated)
                generated.forEach { SensorDebugLog.log("${it.level} ${it.message}${if (it.penalty > 0) " (-${it.penalty}점)" else ""}") }
            }
        }
        collector = Collector(source, scope, stream).also { it.start() }
        return START_STICKY
    }

    private fun Sample.toEngineSample(): EngineSample {
        fun good(channel: Channel) = !Quality.isMissing(quality, channel) && !Quality.isStale(quality, channel)
        val mappedGear = if (!good(Channel.GEAR)) Gear.UNKNOWN else when (gear) {
            4 -> Gear.P; 2 -> Gear.R; 1 -> Gear.N; 8 -> Gear.D; else -> Gear.UNKNOWN
        }
        return EngineSample(
            tNs = tMs * 1_000_000L,
            speedMps = speedMps?.toDouble()?.takeIf { good(Channel.SPEED) },
            speedValid = good(Channel.SPEED),
            gear = mappedGear,
            steeringDeg = steeringDeg?.toDouble()?.takeIf { good(Channel.STEERING) },
            turnSignal = null,
            seatbeltBuckled = null,
            speedLimitKmh = speedLimitKmh?.toDouble()?.takeIf { good(Channel.SPEED_LIMIT) },
        )
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "주행 기록", NotificationManager.IMPORTANCE_LOW))
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("온길 주행 기록")
            .setContentText("차량 센서 수집 중 · 주차 후 앱에서 로그 확인")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        collector?.stop()
        collector = null
        analysisJob?.cancel()
        SensorDebugLog.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "ongil_capture"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START_VEHICLE = "com.example.myapplication.START_VEHICLE"
        const val ACTION_START_SCENARIO = "com.example.myapplication.START_SCENARIO"
        const val ACTION_START_CUSTOM_SCENARIO = "com.example.myapplication.START_CUSTOM_SCENARIO"
        const val ACTION_STOP = "com.example.myapplication.STOP"
        private const val EXTRA_SCENARIO = "scenario"
        private const val EXTRA_CUSTOM_SCRIPT = "custom_script"

        private val stream = MutableSharedFlow<Sample>(
            extraBufferCapacity = 128,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        fun startVehicle(context: Context) {
            context.startForegroundService(
                Intent(context, DrivingCollectorService::class.java).setAction(ACTION_START_VEHICLE)
            )
        }

        fun startScenario(context: Context, scenario: TestScenario) {
            context.startForegroundService(
                Intent(context, DrivingCollectorService::class.java)
                    .setAction(ACTION_START_SCENARIO)
                    .putExtra(EXTRA_SCENARIO, scenario.name)
            )
        }

        fun startCustomScenario(context: Context, script: String) {
            context.startForegroundService(
                Intent(context, DrivingCollectorService::class.java)
                    .setAction(ACTION_START_CUSTOM_SCENARIO)
                    .putExtra(EXTRA_CUSTOM_SCRIPT, script)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DrivingCollectorService::class.java).setAction(ACTION_STOP))
        }
    }
}
