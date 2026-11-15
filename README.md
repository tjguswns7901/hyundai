# 온길 (OnGil)

차량(AAOS · Pleos Connect)에서 속도·기어·페달·조향각을 읽어 급가속, 급정거, 깜빡이 없는 차선 변경 같은 운전 습관을 찾아내고 경고·감점·점수를 매기는 안드로이드 앱이에요.
지금 앱 화면은 **차량 입력 진단·테스트용**이에요. 실제 사용자 화면은 아직 없어요.

## 모듈

| 모듈 | 내용 |
|---|---|
| `app` | 안드로이드 앱. 차량 데이터 수집, 포그라운드 서비스, 진단 화면 |
| `core` | 순수 코틀린 분석 엔진(`OnGilEngine`). 안드로이드 의존성 없음 → [core/README.md](core/README.md) |

감점 크기와 근거는 [core/감점_근거.md](core/감점_근거.md), 모든 기준값은 [OnGilConfig.kt](core/src/main/kotlin/com/ongil/core/OnGilConfig.kt) 한 파일에 있어요.

## 데이터 흐름

```
CarPropertySensorSource (실제 차량/에뮬레이터 VHAL)
ScenarioSensorSource    (내장·직접 작성 시나리오)
        │  채널별 값
        ▼
Collector  ── 100ms(10Hz)마다 Sample 생성, 500ms 넘게 안 바뀐 값은 stale 표시
        ▼
DrivingCollectorService (포그라운드 서비스)
        ▼
OnGilEngine.onSample()  ── 이벤트(경고/감점) + 점수
        ▼
SensorDebugLog (StateFlow) ──▶ MainActivity 화면
```

차량 데이터와 시나리오가 **같은 Collector·엔진 경로**를 지나가서, 시나리오로 확인한 결과를 차량에서도 그대로 기대할 수 있어요.

### 구독하는 차량 속성

| 속성 | 주기 | 비고 |
|---|---|---|
| `PERF_VEHICLE_SPEED` | 10Hz | m/s, 절댓값 사용 |
| `GEAR_SELECTION` | 1Hz | P=4, R=2, N=1, D=8 로 매핑 |
| `ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE` | 10Hz | 0~100% |
| `BRAKE_PEDAL_COMPRESSION_PERCENTAGE` | 10Hz | 0~100% |
| `PERF_STEERING_ANGLE` | 10Hz | 앞바퀴각. AAOS는 왼쪽 −, core는 왼쪽 + 라서 부호를 뒤집음 |

## 빌드

필요한 것:
- Android Studio (AGP 9.5.1), JDK 17
- Android SDK Platform 37 과 그 안의 `platforms/android-37.0/optional/android.car.jar` (compileOnly로 참조)
- Pleos Maven 저장소 접근 (`settings.gradle.kts`에 등록됨, Vehicle SDK 2.3.2)
- 실행 환경: Pleos Connect Emulator (AAOS)

```bash
./gradlew :app:assembleDebug
```

## 앱 사용법

1. **차량 기록 시작**: 속도·페달·조향 권한을 요청하고 VHAL 구독을 시작해요. P 기어에서 시작한 뒤 Virtual Console의 Motion 값을 바꾸면 돼요.
2. **시나리오 실행**: 내장 시나리오 3개
   - 안전 주행: 이벤트 없이 점수가 오르는지
   - 급가속 반복: 첫 번째 경고, 두 번째 감점
   - 제한속도 초과 급제동: 제한속도 50km/h에서 72km/h로 달리다 급제동 → 감점
3. **직접 작성한 시나리오 실행**: 한 줄에 구간 하나, `초,기어,도달속도(km/h),가속페달(%),브레이크(%),조향각(°),제한속도(km/h)`
   ```
   # 초,기어,도달속도,가속페달,브레이크,조향각,제한속도
   2,D,0,0,0,0,50
   3,D,20,25,0,0,50
   2,D,60,75,0,0,50
   1,D,0,0,85,0,50
   ```
   - 속도는 구간 동안 직전 속도에서 도달속도까지 선형으로 바뀌어요 (첫 구간은 0km/h에서 시작)
   - 제한속도를 모르면 `-`, `#` 뒤는 주석
   - 구간 0.1~120초, 전체 10분 이하
4. **기록 종료 및 감점 리포트**: 점수와 감점 요인을 보여줘요. 경고·판단 불가 이벤트는 아래 센서 로그에서 확인해요.

## 엔진 테스트

[GoldenTest.kt](core/src/test/kotlin/com/ongil/core/GoldenTest.kt)의 `main()`을 Android Studio에서 실행하면 가상 시나리오마다 `PASS`/`FAIL`이 출력돼요.

## 현재 한계

- **방향지시등·안전벨트는 아직 수집 안 함**: 엔진에 `null`로 넘어가서 깜빡이 없는 차선 변경은 "판단 불가"로만 기록되고, 안전벨트 감점은 동작하지 않아요.
- **차량 모드에서 제한속도 없음**: 제한속도는 시나리오에서만 들어와요. 실제 차량에서 급제동은 "판단 불가"로 기록돼요 (`NAVI_ROUTE` 권한은 선언만 됨).
- **기어 값**: P=4만 확인됐고 나머지 매핑은 확인 필요해요.
- **저장 없음**: Room 의존성은 있지만 아직 쓰지 않아요. 로그는 메모리에 최근 120줄만 남아요.
- **기준값은 임시**: `OnGilConfig`의 `[TEMP]` 값은 팀 확정 전이고, 실제 주행 데이터로는 아직 검증하지 않았어요.
- 패키지명은 아직 템플릿 그대로(`com.example.myapplication`)예요.
