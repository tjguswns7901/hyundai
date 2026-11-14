# 온길 :core

차량 데이터(`Sample`)를 시간 순서대로 넣으면 경고·감점(`Event`)과 점수가 나오는 분석 모듈이에요. 안드로이드와 무관한 순수 코틀린이에요.

## 사용법 (A·C·D용)
```kotlin
val engine = OnGilEngine()                 // 기준값은 OnGilConfig 로 바꿀 수 있음
val events = engine.onSample(Sample(
    tNs = timestampNs, speedMps = speed, speedValid = true,
    gear = Gear.D, steeringDeg = steer, turnSignal = TurnSignal.OFF,
    seatbeltBuckled = true, speedLimitKmh = 60.0))
engine.score          // 현재 점수
engine.snapshot()     // 화면용 (점수, 증가량, 가속도 등)
engine.accelLog       // 가속도 그래프용 (후진·P·N은 accel=null, gear로 구분)
engine.events         // 지금까지의 모든 이벤트
```
- 속도는 m/s, 값을 못 읽으면 null (방향지시등 null → "판단 불가"로 기록)
- 화면 표시 단위(km/h 또는 m/s)는 `Display`로 변환

## 누가 뭘 하나
- **A**: 차량 값을 `Sample`로 변환. 기어 매핑, 조향각 단위(앞바퀴각, 왼쪽 +), 제한속도 확인 필요
- **C·D**: `Event.level`(OBSERVED/WARNING/PENALTY/UNDETERMINED)과 `message`로 화면 구성
- **전원**: `OnGilConfig.kt`의 `[TEMP]` 값 검토

## 임시로 정한 가정 (확인 필요)
1. 급정거: 15분/5분 되돌아보기 대신 "급제동 직전 속도가 제한속도 이상"인 순간 판정 (`stopLookbackSec=900`으로 바꾸면 15분 되돌아보기 사용)
2. 급제동 기준 15km/h/s, 급가속 기준 2.5m/s²(약 9km/h/s) 등 `[TEMP]` 값 전부
3. 조향각은 앞바퀴 기준, 왼쪽 +. 핸들각을 받으면 `steeringRatio`로 환산
4. 휠베이스 3.0m 가정 (차선 변경 추정에 사용, 차종마다 다름)
5. 감점 후 1단 경고 상태로 재진입 (회의 6번)
6. 점수는 0 미만 없음, 1000 상한
7. 급제동이지만 제한속도 미만이면 감점 없이 기록만 함

## 테스트
`core/src/test/.../GoldenTest.kt`의 `main()` 실행. 가상 시나리오 31항목이 모두 통과한 상태. 실제 주행 데이터로는 아직 검증 안 됨.
