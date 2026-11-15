package com.example.myapplication.collector

import android.os.SystemClock
import com.ongil.core.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

enum class Channel { SPEED, GEAR, ACCELERATOR, BRAKE, STEERING, SPEED_LIMIT }

object Quality {
    fun stale(channel: Channel) = 1 shl channel.ordinal
    fun missing(channel: Channel) = 1 shl (8 + channel.ordinal)
    fun isStale(flags: Int, channel: Channel) = flags and stale(channel) != 0
    fun isMissing(flags: Int, channel: Channel) = flags and missing(channel) != 0
}

data class Sample(
    val tMs: Long,
    val speedMps: Float?,
    val gear: Int?,
    val acceleratorPct: Float?,
    val brakePct: Float?,
    val steeringDeg: Float?,
    val quality: Int,
    val speedLimitKmh: Float? = null,
)

interface SensorSource {
    fun start(onValue: (Channel, Float) -> Unit)
    fun stop()
}

data class DebugState(
    val active: Boolean = false,
    val sampleCount: Long = 0,
    val latest: Sample? = null,
    val score: Double = 0.0,
    val logs: List<String> = emptyList(),
    val events: List<Event> = emptyList(),
)

object SensorDebugLog {
    private val mutable = kotlinx.coroutines.flow.MutableStateFlow(DebugState())
    val state = mutable

    @Synchronized fun start() {
        mutable.value = DebugState(active = true, logs = listOf(line("기록 세션 시작 요청")))
    }

    @Synchronized fun log(message: String) {
        val old = mutable.value
        mutable.value = old.copy(logs = (old.logs + line(message)).takeLast(120))
    }

    @Synchronized fun sample(sample: Sample, score: Double, newEvents: List<Event>) {
        val old = mutable.value
        val count = old.sampleCount + 1
        val logs = if (count == 1L || count % 10L == 0L) {
            (old.logs + line("#${count} ${sample.compact()}")).takeLast(120)
        } else old.logs
        mutable.value = old.copy(
            active = true,
            sampleCount = count,
            latest = sample,
            score = score,
            logs = logs,
            events = old.events + newEvents,
        )
    }

    @Synchronized fun stop() {
        val old = mutable.value
        mutable.value = old.copy(active = false, logs = (old.logs + line("기록 종료")).takeLast(120))
    }

    private fun line(message: String) = "${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.KOREA).format(java.util.Date())}  $message"
}

private fun Sample.compact(): String =
    "gear=${gear?.let(::gearName) ?: "null"}, speed=${speedMps?.let { "%.2f m/s".format(it) } ?: "null"}, " +
        "accel=${acceleratorPct?.let { "%.1f%%".format(it) } ?: "null"}, " +
        "brake=${brakePct?.let { "%.1f%%".format(it) } ?: "null"}, " +
        "steer=${steeringDeg?.let { "%.2f°".format(it) } ?: "null"}, " +
        "limit=${speedLimitKmh?.let { "%.0f km/h".format(it) } ?: "null"}, quality=0x${quality.toString(16)}"

fun gearName(value: Int): String = when (value) {
    4 -> "P"; 2 -> "R"; 1 -> "N"; 8 -> "D"; else -> "?($value)"
}

class Collector(
    private val source: SensorSource,
    private val scope: CoroutineScope,
    private val out: MutableSharedFlow<Sample>,
    private val periodMs: Long = 100L,
    private val staleAfterMs: Long = 500L,
) {
    private data class Reading(val value: Float, val timeMs: Long)
    private val values = Array(Channel.entries.size) { AtomicReference<Reading?>(null) }
    private var job: Job? = null

    fun start() {
        if (job != null) return
        val start = SystemClock.elapsedRealtime()
        source.start { channel, value ->
            if (value.isFinite()) values[channel.ordinal].set(Reading(value, SystemClock.elapsedRealtime()))
        }
        job = scope.launch(Dispatchers.Default) {
            var tick = 0L
            while (isActive) {
                val target = start + tick * periodMs
                val now = SystemClock.elapsedRealtime()
                if (now < target) delay(target - now)
                val elapsed = SystemClock.elapsedRealtime()
                var quality = 0
                fun read(channel: Channel): Float? {
                    val reading = values[channel.ordinal].get()
                    if (reading == null) {
                        quality = quality or Quality.missing(channel)
                        return null
                    }
                    if (elapsed - reading.timeMs > staleAfterMs) quality = quality or Quality.stale(channel)
                    return reading.value
                }
                out.emit(Sample(
                    tMs = tick * periodMs,
                    speedMps = read(Channel.SPEED), gear = read(Channel.GEAR)?.toInt(),
                    acceleratorPct = read(Channel.ACCELERATOR), brakePct = read(Channel.BRAKE),
                    steeringDeg = read(Channel.STEERING), quality = quality,
                    speedLimitKmh = read(Channel.SPEED_LIMIT),
                ))
                tick++
            }
        }
    }

    fun stop() {
        source.stop()
        job?.cancel()
        job = null
        values.forEach { it.set(null) }
    }
}
