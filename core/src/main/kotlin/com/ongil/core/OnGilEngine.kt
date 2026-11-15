package com.ongil.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * 온길 :core 엔진. Sample 을 시간 순서대로 onSample() 에 넣으면 이벤트(경고/감점)가 나오고
 * score / accelLog 로 점수와 그래프 데이터를 읽을 수 있습니다. 안드로이드 의존성 없음(순수 코틀린).
 */
class OnGilEngine(val cfg: OnGilConfig = OnGilConfig()) {

    var score = 0.0; private set
    val events = ArrayList<Event>()
    val accelLog = ArrayList<AccelPoint>()
    var undeterminedStops = 0; private set   // 제한속도를 몰라 판단 못한 급정거 수

    // 시간
    private var t0Ns = Long.MIN_VALUE
    private var lastT = Double.NaN
    private var nowSec = 0.0

    // 가속도 계산용 (t, v) 버퍼
    private val buf = ArrayDeque<Pair<Double, Double>>()
    private var lastAccel: Double? = null

    // 급가속/급제동 상태
    private var accelHighSince = Double.NaN; private var accelArmed = true
    private var brakeHighSince = Double.NaN; private var brakeArmed = true
    private val hardAccelTimes = ArrayList<Double>()
    private var brakeStartSpeed = 0.0
    private val speedHist = ArrayDeque<Triple<Double, Double, Double?>>() // t, v, limitKmh (되돌아보기용)

    // 차선 변경 추정
    private var laneActive = false
    private var laneStart = 0.0; private var psi = 0.0; private var y = 0.0
    private var laneQuiet = 0.0; private var laneMaxSteer = 0.0
    private var laneSignalUnknown = false
    private var lastLeftOn = -1e9; private var lastRightOn = -1e9
    private val laneTimes = ArrayList<Double>()
    private var laneWeightN = 0; private var lastLanePenalty = 0; private var lastLaneDetect = -1e9
    private var prevSteer: Double? = null

    // 지속형 게이지
    private class Gauge(val type: EventType, val warn: Double, val pen: Double, val points: Int, val msg: String) {
        var v = 0.0; var warned = false
    }
    private val gSharp = Gauge(EventType.SHARP_STEERING, cfg.gaugeWarnSec, cfg.gaugePenaltySec, cfg.sharpSteerPenalty, "급조향")
    private val gLow = Gauge(EventType.LOW_SPEED_BIG_STEER, cfg.gaugeWarnSec, cfg.gaugePenaltySec, cfg.lowSpeedSteerPenalty, "저속 큰 조향")
    private val gBelt = Gauge(EventType.SEATBELT, cfg.seatbeltWarnSec, cfg.seatbeltPenaltySec, cfg.seatbeltPenalty, "안전벨트 미착용")

    // 점수
    private var growthMin = 0.0
    private var pausedUntil = -1.0
    private var accruing = false
    private var curRate = 0.0

    fun snapshot() = Snapshot(nowSec, score, accruing, curRate, pausedUntil, lastAccel)

    fun onSample(s: Sample): List<Event> {
        val out = ArrayList<Event>()
        if (t0Ns == Long.MIN_VALUE) t0Ns = s.tNs
        val t = (s.tNs - t0Ns) / 1e9
        if (!lastT.isNaN() && t <= lastT) return out          // 시간 거꾸로/중복은 무시
        val dt = if (lastT.isNaN()) 0.0 else t - lastT
        val gap = dt > cfg.maxGapSec
        lastT = t; nowSec = t

        val v: Double? = if (s.speedValid) s.speedMps else null
        val driving = s.gear == Gear.D && v != null
        if (gap || !driving) { buf.clear(); resetAccelStates(); abortLane(); lastAccel = null }

        // ── 0단 관측: 가속도 (전진 D 기어에서만) ──
        var a: Double? = null
        if (driving) {
            buf.addLast(t to v!!)
            while (buf.size > 2 && t - buf[1].first >= cfg.accelWindowSec) buf.removeFirst()
            val ref = buf.first()
            if (t - ref.first >= cfg.accelWindowSec * 0.8) a = (v - ref.second) / (t - ref.first)
        }
        lastAccel = a
        if (cfg.recordAccelLog) accelLog.add(AccelPoint(t, a, s.gear, v))

        // 되돌아보기 기록
        if (v != null) {
            speedHist.addLast(Triple(t, v, s.speedLimitKmh))
            while (speedHist.isNotEmpty() && t - speedHist.first().first > max(cfg.stopLookbackSec, 10.0)) speedHist.removeFirst()
        }

        if (a != null) { detectAccel(t, a, out); detectBrake(t, a, v!!, s.speedLimitKmh, out) }
        if (driving) {
            detectLane(t, dt, v!!, s, out)
            detectGauges(t, dt, v, s, out)
        } else {
            decayGauges(dt)
        }
        prevSteer = s.steeringDeg?.div(cfg.steeringRatio)

        updateScore(t, dt, s, driving, v)
        events.addAll(out)
        return out
    }

    // ───────── 급가속 ─────────
    private fun detectAccel(t: Double, a: Double, out: MutableList<Event>) {
        if (a >= cfg.hardAccelMps2) {
            if (accelHighSince.isNaN()) accelHighSince = t
            if (accelArmed && t - accelHighSince >= cfg.hardAccelMinSec) {
                accelArmed = false
                hardAccelTimes.removeAll { t - it > cfg.hardAccelWarnWindowSec }
                hardAccelTimes.add(t)
                if (hardAccelTimes.size >= cfg.hardAccelPenaltyCount) {
                    hardAccelTimes.clear(); hardAccelTimes.add(t)       // 감점 후 1단 경고 상태로 재진입 [MEET]
                    penalize(t, EventType.HARD_ACCEL, cfg.hardAccelPenalty, "급가속 반복 감지", out)
                } else warn(t, EventType.HARD_ACCEL, "급가속 감지 (경고)", out)
            }
        } else {
            accelHighSince = Double.NaN
            if (a < cfg.hardAccelRearmMps2) accelArmed = true
        }
    }

    // ───────── 급제동 / 급정거 ─────────
    private fun detectBrake(t: Double, a: Double, v: Double, limit: Double?, out: MutableList<Event>) {
        if (-a >= cfg.hardBrakeMps2) {
            if (brakeHighSince.isNaN()) {
                brakeHighSince = t
                brakeStartSpeed = buf.firstOrNull()?.second ?: v   // 감속 시작 무렵 속도
            }
            if (brakeArmed && t - brakeHighSince >= cfg.hardBrakeMinSec) {
                brakeArmed = false
                judgeSuddenStop(t, brakeStartSpeed, limit, out)
            }
        } else {
            brakeHighSince = Double.NaN
            if (-a < cfg.hardBrakeRearmMps2) brakeArmed = true
        }
    }

    private fun judgeSuddenStop(t: Double, v0: Double, limit: Double?, out: MutableList<Event>) {
        if (limit == null) {           // 제한속도를 못 받으면 판단 불가로 기록 [MEET]
            undeterminedStops++
            out.add(Event(t, EventType.HARD_BRAKE, Level.UNDETERMINED, 0, "급제동 감지, 제한속도 정보 없음 → 판단 불가"))
            return
        }
        val thr = limit - cfg.stopLimitMarginKmh
        val ok = if (cfg.stopLookbackSec <= 0.0) v0 * 3.6 >= thr else {
            var sum = 0.0; var prev = Double.NaN
            for ((ht, hv, hl) in speedHist) {
                if (!prev.isNaN() && hl != null && hv * 3.6 >= hl - cfg.stopLimitMarginKmh) sum += ht - prev
                prev = ht
            }
            sum >= cfg.stopLookbackNeedSec
        }
        if (ok) penalize(t, EventType.SUDDEN_STOP, cfg.hardBrakePenalty, "제한속도 수준 주행 중 급정거", out)
        else out.add(Event(t, EventType.HARD_BRAKE, Level.OBSERVED, 0, "급제동 감지(제한속도 미만 주행 → 감점 없음)"))
    }

    // ───────── 깜빡이 없는 차선 변경 ─────────
    private fun abortLane() { laneActive = false; psi = 0.0; y = 0.0; laneQuiet = 0.0; laneSignalUnknown = false }

    private fun detectLane(t: Double, dt: Double, v: Double, s: Sample, out: MutableList<Event>) {
        when (s.turnSignal) {
            TurnSignal.LEFT -> lastLeftOn = t
            TurnSignal.RIGHT -> lastRightOn = t
            else -> {}
        }
        val steer = s.steeringDeg?.div(cfg.steeringRatio)
        if (steer == null || v < cfg.laneMinSpeedMps) { abortLane(); return }
        if (!laneActive) {
            if (abs(steer) >= cfg.laneStartSteerDeg) {
                laneActive = true; laneStart = t; psi = 0.0; y = 0.0; laneQuiet = 0.0
                laneMaxSteer = abs(steer); laneSignalUnknown = s.turnSignal == null
            }
            return
        }
        if (s.turnSignal == null) laneSignalUnknown = true
        laneMaxSteer = max(laneMaxSteer, abs(steer))
        val d = Math.toRadians(steer)
        psi += v / cfg.wheelbaseM * tan(d) * dt        // 자전거 모델: 왼쪽(+)으로 틀면 방향각 증가
        y += v * sin(psi) * dt
        laneQuiet = if (abs(steer) < cfg.laneEndSteerDeg) laneQuiet + dt else 0.0
        if (t - laneStart > cfg.laneMaxSec) { abortLane(); return }
        if (laneQuiet >= cfg.laneQuietSec) {
            val shift = abs(y); val head = abs(Math.toDegrees(psi))
            val isLane = shift in cfg.laneMinShiftM..cfg.laneMaxShiftM &&
                    head <= cfg.laneMaxEndHeadingDeg && laneMaxSteer <= cfg.laneMaxSteerDeg
            val side = if (y > 0) lastLeftOn else lastRightOn
            val signaled = side >= laneStart - cfg.laneSignalLeadSec
            val unknown = laneSignalUnknown
            abortLane()
            if (isLane) {
                if (unknown) out.add(Event(t, EventType.LANE_CHANGE_NO_SIGNAL, Level.UNDETERMINED, 0,
                    "차선 변경 추정, 방향지시등 값을 못 읽어 판단 불가"))
                else if (!signaled) onUnsignaledLane(t, out)
            }
        }
    }

    private fun onUnsignaledLane(t: Double, out: MutableList<Event>) {
        if (t - lastLaneDetect > cfg.laneChainResetSec) { laneWeightN = 0; lastLanePenalty = 0 }
        lastLaneDetect = t
        laneTimes.removeAll { t - it > cfg.laneWindowSec }
        laneTimes.add(t)
        if (laneTimes.size >= cfg.laneCountForPenalty) {
            val p = if (laneWeightN == 0) cfg.laneBasePenalty else (lastLanePenalty * cfg.laneWeight).roundToInt()
            laneWeightN++; lastLanePenalty = p
            penalize(t, EventType.LANE_CHANGE_NO_SIGNAL, p, "깜빡이 없는 차선 변경 ${laneTimes.size}회(20분 내)", out)
        } else warn(t, EventType.LANE_CHANGE_NO_SIGNAL, "깜빡이 없는 차선 변경 추정 (${laneTimes.size}/${cfg.laneCountForPenalty})", out)
    }

    // ───────── 지속형 게이지: 급조향 / 저속 큰 조향 / 안전벨트 ─────────
    private fun detectGauges(t: Double, dt: Double, v: Double, s: Sample, out: MutableList<Event>) {
        val steer = s.steeringDeg?.div(cfg.steeringRatio)
        val rate = if (steer != null && prevSteer != null && dt > 0) abs(steer - prevSteer!!) / dt else 0.0
        feed(gSharp, steer != null && v >= cfg.sharpSteerMinSpeedMps && rate >= cfg.sharpSteerRateDegPerSec, dt, t, out)
        feed(gLow, steer != null && v in cfg.lowSpeedMinMps..cfg.lowSpeedMaxMps && abs(steer) >= cfg.lowSpeedBigSteerDeg, dt, t, out)
        feed(gBelt, s.seatbeltBuckled == false && v >= cfg.seatbeltMinSpeedMps, dt, t, out)
    }

    private fun decayGauges(dt: Double) { for (g in listOf(gSharp, gLow, gBelt)) decay(g, dt) }
    private fun decay(g: Gauge, dt: Double) { g.v = max(0.0, g.v - cfg.gaugeDecayPerSec * dt); if (g.v < g.warn) g.warned = false }

    private fun feed(g: Gauge, active: Boolean, dt: Double, t: Double, out: MutableList<Event>) {
        if (!active) { decay(g, dt); return }
        g.v += dt
        if (g.v >= g.pen) {
            g.v = g.warn; g.warned = true                       // 감점 후 1단 경고 상태로 재진입 [MEET]
            penalize(t, g.type, g.points, "${g.msg} 지속", out)
        } else if (g.v >= g.warn && !g.warned) {
            g.warned = true; warn(t, g.type, "${g.msg} 경고", out)
        }
    }

    // ───────── 경고/감점/점수 ─────────
    private fun warn(t: Double, type: EventType, msg: String, out: MutableList<Event>) {
        pausedUntil = max(pausedUntil, t + cfg.warnPauseSec)
        out.add(Event(t, type, Level.WARNING, 0, msg))
    }

    private fun penalize(t: Double, type: EventType, points: Int, msg: String, out: MutableList<Event>) {
        val before = score
        score = max(cfg.scoreMin, score - points)
        growthMin = 0.0                                        // 증가량 초기화 [MEET]
        pausedUntil = max(pausedUntil, t + cfg.penaltyPauseSec)
        out.add(Event(t, type, Level.PENALTY, points, "$msg (-${points}점, 실제 ${(before - score).roundToInt()}점 하락)"))
    }

    private fun updateScore(t: Double, dt: Double, s: Sample, driving: Boolean, v: Double?) {
        accruing = driving && v!! >= cfg.accrualMinSpeedMps && t >= pausedUntil && !s.gear.let { it == Gear.R }
        curRate = min(cfg.scoreMaxPerMin, cfg.scoreBasePerMin + cfg.scoreGrowthPerMin * growthMin)
        if (!accruing || dt <= 0 || dt > cfg.maxGapSec) return
        score = min(cfg.scoreMax, score + curRate * dt / 60.0)
        growthMin += dt / 60.0
    }

    private fun resetAccelStates() {
        accelHighSince = Double.NaN; brakeHighSince = Double.NaN
        accelArmed = true; brakeArmed = true
    }
}
