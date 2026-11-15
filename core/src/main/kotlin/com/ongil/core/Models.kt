package com.ongil.core

/** 기어. A(차량연동)가 AAOS 기어 값을 이 enum으로 바꿔서 넘겨줍니다. (P=4만 확인됨, 나머지는 A 확인 필요) */
enum class Gear { P, R, N, D, UNKNOWN }

/** 방향지시등. 값을 못 읽으면 Sample.turnSignal 에 null 을 넣어 주세요(→ 판단 불가 처리). */
enum class TurnSignal { OFF, LEFT, RIGHT }

/**
 * 차량에서 한 번 들어오는 데이터 묶음 (A → B).
 * 속도 단위는 m/s (AAOS PERF_VEHICLE_SPEED 그대로). 못 읽는 값은 null.
 * 조향각: 앞바퀴 기준 도(°), 왼쪽이 +. (핸들 각도를 주면 OnGilConfig.steeringRatio 로 환산)
 */
data class Sample(
    val tNs: Long,                    // 타임스탬프(나노초). 단조 증가해야 함
    val speedMps: Double?,            // 속도 m/s
    val speedValid: Boolean = true,   // 속도 status 가 정상인지
    val gear: Gear = Gear.UNKNOWN,
    val steeringDeg: Double? = null,
    val turnSignal: TurnSignal? = null,
    val seatbeltBuckled: Boolean? = null,
    val speedLimitKmh: Double? = null // 내비 제한속도 km/h (못 받으면 null)
)

enum class EventType { HARD_ACCEL, HARD_BRAKE, SUDDEN_STOP, LANE_CHANGE_NO_SIGNAL, SHARP_STEERING, LOW_SPEED_BIG_STEER, SEATBELT }

/** OBSERVED=0단(기록만) / WARNING=1단 경고 / PENALTY=2단 감점 / UNDETERMINED=판단 불가(기록만) */
enum class Level { OBSERVED, WARNING, PENALTY, UNDETERMINED }

data class Event(
    val tSec: Double,
    val type: EventType,
    val level: Level,
    val penalty: Int = 0,     // 실제 깎인 점수(양수). PENALTY 일 때만
    val message: String
)

/** 가속도 그래프용 한 점. 후진/P/N 은 accel=null 이고 gear 로 구분 */
data class AccelPoint(val tSec: Double, val accelMps2: Double?, val gear: Gear, val speedMps: Double?)

/** 화면 표시용 한 장면 */
data class Snapshot(
    val tSec: Double,
    val score: Double,
    val accruing: Boolean,
    val scorePerMin: Double,
    val pausedUntilSec: Double,
    val accelMps2: Double?
)
