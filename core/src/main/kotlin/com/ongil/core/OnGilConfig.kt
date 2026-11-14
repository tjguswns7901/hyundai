package com.ongil.core

/**
 * 모든 기준값은 여기 한 곳에 모았습니다. 팀원들은 이 파일의 숫자만 고치면 됩니다.
 * 표시: [TEMP]=지우+Claude가 임시로 정한 값(팀 확정 필요), [PLAN]=통합기획안 근거,
 *       [MEET]=2026-11-14 회의 결정, [HW]=센서 특성상 정한 값
 * 내부 단위는 m/s, m/s². 화면 표시는 Display.kt 가 km/h 또는 m/s 로 바꿔 줍니다.
 */
data class OnGilConfig(
    // ── A. 데이터 품질 ──
    val maxGapSec: Double = 1.0,                 // [HW] 샘플 간격이 이보다 크면 가속도 계산을 새로 시작
    val accelWindowSec: Double = 0.5,            // [HW] 가속도 평활 창(속도가 1km/h 단위로 끊겨 들어와서 필요)

    // ── B. 급가속 (전진 D 기어에서만) ──
    val hardAccelMps2: Double = 2.5,             // [TEMP] ≈9 km/h/s 이상이면 급가속 후보
    val hardAccelMinSec: Double = 0.5,           // [TEMP] 이 시간 이상 이어져야 1건
    val hardAccelRearmMps2: Double = 1.5,        // [TEMP] 이 값 아래로 내려가야 다음 건 인정
    val hardAccelWarnWindowSec: Double = 600.0,  // [TEMP] 10분 안에
    val hardAccelPenaltyCount: Int = 2,          // [TEMP] 2번째 급가속부터 감점 (1번째는 경고)
    val hardAccelPenalty: Int = 15,              // [TEMP] 감점 근거는 감점_근거.md

    // ── C. 급제동 / 급정거 ──
    val hardBrakeMps2: Double = 15.0 / 3.6,      // [MEET] 15 km/h/s 이상 감속이면 급제동
    val hardBrakeMinSec: Double = 0.3,           // [TEMP]
    val hardBrakeRearmMps2: Double = 2.0,        // [TEMP] 감속이 이보다 약해져야 다음 건
    val hardBrakePenalty: Int = 30,              // [MEET] 급정거 감점(제한속도 조건 충족 시)
    val stopLimitMarginKmh: Double = 0.0,        // [MEET] 제한속도 이상(0)부터 조건 충족
    val stopLookbackSec: Double = 0.0,           // [TEMP] 0=급제동 직전 속도로만 판정. 900으로 하면 15분 되돌아보기
    val stopLookbackNeedSec: Double = 300.0,     // 되돌아보기 사용 시: 그 중 5분 이상 제한속도 이상이어야 함

    // ── D. 깜빡이 없는 차선 변경 (추정) ──
    val wheelbaseM: Double = 3.0,                // [TEMP] 휠베이스 가정. 차종마다 다름
    val laneMinSpeedMps: Double = 30.0 / 3.6,    // [TEMP] 이 속도 이상에서만 판정
    val laneStartSteerDeg: Double = 0.5,         // [TEMP] 앞바퀴 각이 이 이상이면 움직임 시작
    val laneEndSteerDeg: Double = 0.25,          // [TEMP] 이 아래가 quiet 시간 이어지면 끝
    val laneQuietSec: Double = 1.0,
    val laneMaxSec: Double = 10.0,               // 이보다 길면 차선 변경 아님(코너 등)
    val laneMinShiftM: Double = 2.0,             // [TEMP] 옆으로 움직인 거리 범위(차로폭 ≈3~3.5m)
    val laneMaxShiftM: Double = 4.5,
    val laneMaxEndHeadingDeg: Double = 3.0,      // 끝났을 때 차 방향이 거의 원래대로여야 함
    val laneMaxSteerDeg: Double = 6.0,           // 이보다 크게 틀면 좌우회전/유턴으로 봄
    val laneSignalLeadSec: Double = 3.0,         // [TEMP] 시작 3초 전부터 켜져 있으면 "켰다"로 인정
    val laneWindowSec: Double = 1200.0,          // [MEET] 20분
    val laneCountForPenalty: Int = 3,            // [MEET] 첫 감지 포함 3번째에 감점
    val laneBasePenalty: Int = 45,               // [MEET] 급정거(30)의 1.5배
    val laneWeight: Double = 1.2,                // [MEET] 반복 시 직전 감점×1.2 반올림
    val laneChainResetSec: Double = 1200.0,      // [TEMP] 이 시간 동안 새 건이 없으면 가중치 초기화

    // ── E. 지속형 경고(게이지) : 조건이 유지되는 초 단위 ──
    val sharpSteerRateDegPerSec: Double = 25.0,  // [TEMP] 급조향: 조향 변화율
    val sharpSteerMinSpeedMps: Double = 30.0 / 3.6,
    val lowSpeedMaxMps: Double = 20.0 / 3.6,     // [TEMP] 저속 큰 조향 속도 범위
    val lowSpeedMinMps: Double = 5.0 / 3.6,
    val lowSpeedBigSteerDeg: Double = 25.0,
    val seatbeltMinSpeedMps: Double = 10.0 / 3.6,// [TEMP] 이 속도 이상 주행 중 미착용
    val gaugeWarnSec: Double = 3.0,              // [TEMP] 유지 3초 → 경고
    val gaugePenaltySec: Double = 6.0,           // [TEMP] 유지 6초 → 감점
    val seatbeltWarnSec: Double = 10.0,
    val seatbeltPenaltySec: Double = 30.0,
    val gaugeDecayPerSec: Double = 1.0,          // 조건이 풀리면 게이지가 초당 이만큼 줄어듦
    val sharpSteerPenalty: Int = 10,             // [TEMP]
    val lowSpeedSteerPenalty: Int = 10,          // [TEMP]
    val seatbeltPenalty: Int = 20,               // [TEMP]

    // ── F. 점수(0에서 시작, 안전 운전 시간이 길수록 가속해서 증가) ──
    val scoreBasePerMin: Double = 1.0,           // [MEET] 시작 증가량(점/분)
    val scoreGrowthPerMin: Double = 0.2,         // [MEET] 1분 지날 때마다 증가량이 이만큼 커짐
    val scoreMaxPerMin: Double = 6.0,            // [TEMP] 증가량 상한
    val scoreMax: Double = 1000.0,               // [TEMP] 점수 상한
    val scoreMin: Double = 0.0,                  // [TEMP] 점수 하한(마이너스 없음)
    val accrualMinSpeedMps: Double = 1.0,        // [MEET] 정차/주차 중에는 안 오름
    val warnPauseSec: Double = 60.0,             // [MEET] 경고 시 가점 일시중단
    val penaltyPauseSec: Double = 120.0,         // [TEMP] 감점 후 중단
    val recordAccelLog: Boolean = true,          // [PLAN] 0단 관측: 가속도 그래프용 기록
    val steeringRatio: Double = 1.0              // 핸들각을 받는 경우 앞바퀴각 = 핸들각/이 값
)
