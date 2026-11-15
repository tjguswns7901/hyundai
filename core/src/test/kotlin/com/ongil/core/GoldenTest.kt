package com.ongil.core

import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.sin

/** 시나리오를 코드로 만들어 엔진에 넣고, 사람이 미리 정한 정답과 비교하는 테스트. 실행: main() */
class Sim(val cfg: OnGilConfig = OnGilConfig()) {
    val e = OnGilEngine(cfg)
    var t = 0.0
    val dt = 0.1
    var gear = Gear.D; var sig: TurnSignal? = TurnSignal.OFF; var belt: Boolean? = true; var limit: Double? = 60.0
    var steer: Double? = 0.0
    val ev = ArrayList<Event>()
    fun tick(v: Double) {
        val q = (v * 3.6).roundToLong() / 3.6          // 실제 센서처럼 1km/h 단위로 끊어서 입력
        ev += e.onSample(Sample((t * 1e9).toLong(), q, true, gear, steer, sig, belt, limit))
        t += dt
    }
    fun cruise(sec: Double, v: Double) { repeat((sec / dt).toInt()) { tick(v) } }
    fun ramp(sec: Double, v0: Double, a: Double) { repeat((sec / dt).toInt()) { tick(v0 + a * it * dt) } }
    /** 차선 변경: 앞바퀴각 A sin(2πt/T), 속도 v 일정 */
    fun laneChange(v: Double, signalOn: TurnSignal?, dir: Int = 1, T: Double = 3.0, A: Double = 1.05) {
        if (signalOn != null) sig = signalOn
        val n = (T / dt).toInt()
        for (i in 0 until n) { steer = dir * A * sin(2 * PI * i * dt / T); tick(v) }
        steer = 0.0
        cruise(1.5, v)
        sig = TurnSignal.OFF
    }
    fun count(type: EventType, lv: Level) = ev.count { it.type == type && it.level == lv }
}

var fails = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS " else "FAIL ") + name + if (!ok) "  -> $detail" else "")
    if (!ok) fails++
}

fun main() {
    val V = 60 / 3.6

    run { // 1 일정 속도
        val s = Sim(); s.cruise(60.0, V)
        check("1 일정속도: 이벤트 없음", s.ev.isEmpty(), s.ev.toString())
        check("1 일정속도: 점수 증가", s.e.score > 0.9, "${s.e.score}")
    }
    run { // 2 급가속 경고 → 감점
        val s = Sim(); s.cruise(5.0, 10.0); s.ramp(2.0, 10.0, 3.0); s.cruise(20.0, 16.0)
        check("2a 급가속 1회: 경고 1건", s.count(EventType.HARD_ACCEL, Level.WARNING) == 1 && s.ev.size == 1, s.ev.toString())
        s.ramp(2.0, 16.0, 3.0); s.cruise(5.0, 22.0)
        check("2b 급가속 2회: 감점 15", s.ev.any { it.level == Level.PENALTY && it.penalty == 15 }, s.ev.toString())
    }
    run { // 3 완만한 가속
        val s = Sim(); s.cruise(3.0, 10.0); s.ramp(6.0, 10.0, 1.0); s.cruise(3.0, 16.0)
        check("3 완만한 가속: 이벤트 없음", s.ev.isEmpty(), s.ev.toString())
    }
    run { // 4 후진/P/N 은 가속도 계산 안 함
        val s = Sim(); s.gear = Gear.R; s.cruise(2.0, 1.0); s.ramp(2.0, 1.0, 3.0); s.cruise(2.0, 7.0)
        check("4a 후진 급가속: 이벤트 없음", s.ev.isEmpty(), s.ev.toString())
        check("4b 후진: 그래프 accel=null, gear=R", s.e.accelLog.all { it.accelMps2 == null && it.gear == Gear.R })
        val p = Sim(); p.gear = Gear.P; p.cruise(30.0, 0.0)
        check("4c 주차: 점수 0 유지", p.e.score == 0.0, "${p.e.score}")
    }
    run { // 5 급제동/급정거
        for ((lim, expect) in listOf<Pair<Double?, String>>(60.0 to "PEN", 80.0 to "OBS", null to "UND")) {
            val s = Sim(); s.limit = lim; s.cruise(10.0, V); s.ramp(2.0, V, -5.0); s.cruise(5.0, V - 10.0)
            val ok = when (expect) {
                "PEN" -> s.ev.any { it.type == EventType.SUDDEN_STOP && it.level == Level.PENALTY && it.penalty == 30 }
                "OBS" -> s.ev.size == 1 && s.ev[0].level == Level.OBSERVED
                else -> s.ev.size == 1 && s.ev[0].level == Level.UNDETERMINED && s.e.undeterminedStops == 1
            }
            check("5 급제동 제한속도=$lim → $expect", ok, s.ev.toString())
        }
        val s = Sim(); s.limit = 60.0; s.cruise(5.0, V); s.ramp(4.0, V, -2.0); s.cruise(3.0, V - 8.0)
        check("5d 부드러운 감속(7km/h/s): 이벤트 없음", s.ev.isEmpty(), s.ev.toString())
    }
    run { // 6 차선 변경
        val s = Sim(); s.cruise(5.0, 20.0); s.laneChange(20.0, null)
        check("6a 깜빡이 없는 차선변경: 경고 1/3", s.count(EventType.LANE_CHANGE_NO_SIGNAL, Level.WARNING) == 1, s.ev.toString())
        val r = Sim(); r.cruise(5.0, 20.0); r.laneChange(20.0, TurnSignal.LEFT)
        check("6b 깜빡이 켠 차선변경: 이벤트 없음", r.ev.isEmpty(), r.ev.toString())
        val rr = Sim(); rr.cruise(5.0, 20.0); rr.sig = TurnSignal.LEFT; rr.cruise(2.0, 20.0); rr.laneChange(20.0, null, dir = -1)
        check("6c 반대쪽 깜빡이 후 오른쪽 차선변경: 경고", rr.count(EventType.LANE_CHANGE_NO_SIGNAL, Level.WARNING) == 1, rr.ev.toString())
        val n = Sim(); n.cruise(5.0, 20.0); n.sig = null; n.laneChange(20.0, null)
        check("6d 방향지시등 못 읽음: 판단 불가", n.ev.size == 1 && n.ev[0].level == Level.UNDETERMINED, n.ev.toString())
        val c = Sim(); c.cruise(5.0, 20.0); c.steer = 1.0; c.cruise(20.0, 20.0)
        check("6e 완만한 코너(1° 20초): 이벤트 없음", c.ev.isEmpty(), c.ev.toString())
        val l = Sim(); l.cruise(5.0, 8.0); l.steer = 10.0; l.cruise(3.0, 8.0); l.steer = 0.0; l.cruise(3.0, 8.0)
        check("6f 좌회전(10°): 차선변경 아님", l.ev.none { it.type == EventType.LANE_CHANGE_NO_SIGNAL }, l.ev.toString())
        val m = Sim(); m.cruise(5.0, 20.0)
        repeat(5) { m.laneChange(20.0, null); m.cruise(25.0, 20.0) }
        val pens = m.ev.filter { it.level == Level.PENALTY }.map { it.penalty }
        check("6g 3·4·5번째: 감점 45, 54, 65", pens == listOf(45, 54, 65), pens.toString())
        val w = Sim(); w.cruise(5.0, 20.0)
        repeat(3) { w.laneChange(20.0, null); w.cruise(20.0 * 60 / 1.0 / 20, 20.0) }  // 약 1분 간격 → 윈도우 안
        val x = Sim(); x.cruise(5.0, 20.0)
        repeat(3) { x.laneChange(20.0, null); x.cruise(700.0, 20.0) }  // 11분 이상 간격 → 20분 윈도우 밖
        check("6h 20분 넘게 퍼진 3회: 감점 없음", x.ev.none { it.level == Level.PENALTY }, x.ev.toString())
    }
    run { // 7 안전벨트
        val s = Sim(); s.belt = false; s.cruise(12.0, 50 / 3.6)
        check("7a 미착용 12초: 경고", s.count(EventType.SEATBELT, Level.WARNING) == 1, s.ev.toString())
        s.cruise(25.0, 50 / 3.6)
        check("7b 미착용 지속: 감점 20", s.ev.any { it.level == Level.PENALTY && it.penalty == 20 }, s.ev.toString())
        val p = Sim(); p.belt = false; p.gear = Gear.P; p.cruise(60.0, 0.0)
        check("7c 주차 중 미착용: 무시", p.ev.isEmpty())
    }
    run { // 8 점수
        val s = Sim(); s.cruise(600.0, V)
        check("8a 10분 안전운전: 약 20점", s.e.score in 19.0..21.0, "${s.e.score}")
        val a = Sim(); a.cruise(300.0, V); val before = a.e.score
        a.ramp(2.0, V, -5.0); a.cruise(2.0, V - 10.0)
        check("8b 급정거: 30점 감점 후 하한 0", a.e.score == 0.0 || a.e.score < before, "${a.e.score}")
        val w = Sim(); w.cruise(60.0, 10.0); w.ramp(2.0, 10.0, 3.0); w.cruise(2.0, 16.0)
        val s0 = w.e.score; w.cruise(30.0, 16.0)
        check("8c 경고 후 가점 일시중단", w.e.score == s0, "${s0} -> ${w.e.score}")
        val d = Sim(); d.cruise(60.0, V)
        val r1 = d.e.snapshot().scorePerMin; d.cruise(300.0, V)
        check("8d 증가량이 시간이 갈수록 커짐", d.e.snapshot().scorePerMin > r1, "$r1 -> ${d.e.snapshot().scorePerMin}")
    }
    run { // 9 데이터 품질
        val s = Sim(); s.cruise(5.0, 10.0); s.t += 3.0; s.cruise(5.0, 20.0)
        check("9a 3초 끊김 뒤 점프: 급가속 오탐 없음", s.ev.isEmpty(), s.ev.toString())
        val e = OnGilEngine()
        e.onSample(Sample(1_000_000_000, 10.0, true, Gear.D)); val r = e.onSample(Sample(900_000_000, 30.0, true, Gear.D))
        check("9b 시간 거꾸로: 무시", r.isEmpty())
        val b = Sim(); b.cruise(5.0, 10.0)
        check("9c 표시 단위 변환", Display.accel(1.0, SpeedUnit.KMH) == 3.6 && Display.speed(10.0, SpeedUnit.MPS) == 10.0)
    }
    run { // 10 긴 주행(30분 노이즈 포함) 예외·성능
        val s = Sim(); val rnd = java.util.Random(7)
        val t0 = System.nanoTime()
        repeat(18000) { s.tick(V + (rnd.nextDouble() - 0.5) * 0.6) }
        val ms = (System.nanoTime() - t0) / 1e6
        check("10 30분 노이즈 주행: 오탐 없음", s.ev.isEmpty(), s.ev.take(3).toString())
        println("   (18000샘플 처리 ${ms.toInt()}ms)")
    }
    println(if (fails == 0) "\n전체 통과" else "\n실패 $fails 건")
    if (fails > 0) System.exit(1)
}
