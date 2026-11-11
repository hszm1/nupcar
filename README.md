# 눕카 (Nupcar)

주차 중 터치 한 번으로 시트·조명·공조·사운드를 휴식 환경으로 바꾸고, 정한 시간에 모션베드처럼 깨운 뒤 차량을 휴식 전 상태로 되돌리는 Pleos Connect 차량용 휴식 앱입니다.

> **에뮬레이터 제한 사항:** Pleos Connect Emulator v2.1.0에서는 등받이 이동 명령(`moveSeatBackrestAngle`)이 접수되지만 등받이 위치 값이 0으로 유지되고, 차량 화면에서도 등받이가 움직이지 않습니다. 그래서 앱은 등받이 동작을 **시각화 화면**으로 함께 보여 줍니다. 자세한 내용은 [에뮬레이터 제한 사항](#에뮬레이터-제한-사항)을 참고하세요.

## 주요 기능

- **휴식 모드 4종:** 낮잠, 명상, ASMR, 자유 휴식. 모드별로 휴식 시간, 등받이 각도, 조명 밝기, 온도, 사운드, 기상 속도를 저장합니다.
- **휴식 환경 전환:** 시작 전 등받이·공조·조명 상태를 저장한 뒤, 등받이를 눕히고 조명을 낮추고 온도를 맞추고 사운드를 재생합니다.
- **단계별 기상:** 기상 사운드 → 조명이 서서히 밝아짐 → 등받이가 천천히 올라옴 → 공조·조명 복원 순서로 진행합니다.
- **등받이 시각화:** 휴식과 기상 중 등받이 상태를 측면 그림으로 보여 줍니다. 차량이 실제 위치 변화를 보고하면 그 값을 따르고, 보고하지 않으면 명령한 동작을 같은 속도로 재생합니다.
- **휴식 사운드:** 모든 소리를 코드로 생성해 별도 음원 라이선스가 필요 없습니다. 백색소음은 고음을 줄인 핑크 노이즈로, 빗소리처럼 부드럽고 아주 느리게 커졌다 작아집니다. 휴식 사운드는 3초 동안 서서히 커지며 시작합니다. 호흡 가이드는 소리가 5초 커지고 5초 작아지는 박자에 맞춰 화면의 원이 커졌다 작아지고, **들이쉬세요 / 내쉬세요**를 함께 표시합니다.
- **백그라운드 유지:** 화면이 꺼지거나 다른 앱으로 이동해도 포그라운드 서비스로 타이머와 사운드가 계속 동작합니다.

## 안전 설계

- 기어가 P일 때만 시작하고, 휴식·기상·복원 중 P가 해제되면 시트를 즉시 멈추고 세션을 중단합니다.
- 뒷좌석에 탑승자가 있으면 등받이를 눕히지 않습니다.
- 등받이는 전체 가동 범위의 최대 70%까지만 눕힙니다. 설정의 **등받이 눕힘** 값은 이 안전 범위 기준이라, 100%가 전체 가동 범위의 70%입니다. 예를 들어 80%로 두면 실제로는 전체 범위의 56%만큼 눕고, 세션 화면에도 설정과 같은 기준으로 "등받이 눕힘 80% (안전 범위 기준)"이라고 표시합니다.
- 기어나 시트 위치를 읽을 수 없는 등 상태가 불확실하면 시트를 움직이지 않습니다.
- 휴식 중 언제든 **지금 일어나기** 또는 **즉시 중단**을 누를 수 있고, 중단 후에는 P 상태에서 **시트 되돌리기**로 원래 위치에 복원합니다.

## 테스트 환경

| 항목 | 버전 |
|---|---|
| 빌드 도구 | Gradle 8.13, Android Gradle Plugin 8.13.2 |
| JDK | Microsoft OpenJDK 17 |
| 에뮬레이터 | Pleos Connect Emulator v2.1.0 (Android 14, API 34) |
| Vehicle SDK | `ai.pleos.playground:Vehicle:2.3.2` |
| Vehicle Service | 2.3.22 |
| 최소 SDK | API 28 |

## 실행 방법

### 1. 빌드와 설치
1. 이 저장소를 Android Studio로 엽니다.
2. JDK 17을 사용하도록 설정합니다. 터미널에서 빌드한다면 `JAVA_HOME`이 JDK 17을 가리키게 합니다.
3. Device Manager에서 Pleos Connect Emulator를 실행합니다. 설치 방법은 [Pleos Playground 개발 문서](https://pleos.ai/playground/develop)를 따릅니다.
4. `app` 구성을 실행하거나, 터미널에서 다음 명령을 실행합니다.

```bash
./gradlew :app:installDebug
```

### 2. Virtual Console 연결
기어, 뒷좌석 탑승, 공조 상태를 바꿔 보려면 Virtual Console을 엽니다.

```bash
adb forward tcp:8088 tcp:8088
```

브라우저에서 `http://127.0.0.1:8088/`에 접속해 **Emulation Controls**를 사용합니다.

### 3. 시연 순서
1. Virtual Console에서 기어를 **P**로 둡니다.
2. 앱에서 모드를 고르고 시간을 정한 뒤 **쉬기 시작**을 누릅니다. 디버그 빌드에서는 1분 옵션으로 짧게 시연할 수 있습니다.
3. 등받이 시각화, 조명 밝기, 공조 온도 변화를 확인합니다.
4. **지금 일어나기**를 눌러 기상 단계를 확인합니다. 종료 후 공조와 조명이 시작 전 값으로 돌아옵니다.
5. 안전 동작 확인: 휴식 중 기어를 D로 바꾸면 즉시 중단되고, 뒷좌석 Seat Occupancy를 Occupied로 두면 시작이 거부됩니다.

### 차량 없이 실행하기 (시뮬레이션 모드)
에뮬레이터 없이 화면 흐름만 확인하려면 가상 차량으로 빌드합니다. 이 빌드는 실제 차량을 제어하지 않습니다.

```bash
./gradlew :app:installDebug -Pnupcar.simulated=true
```

## APK

릴리스 APK는 [GitHub Releases](../../releases)에 올립니다.

## 에뮬레이터 제한 사항

에뮬레이터에서 확인한 결과는 다음과 같습니다.

| 기능 | 에뮬레이터 결과 |
|---|---|
| 공조 온도·전원 적용과 복원 | 동작 확인 |
| 앰비언트 조명 밝기 적용과 복원 | 동작 확인 |
| 기어 P 확인, P 해제 시 중단 | 동작 확인 |
| 뒷좌석 탑승 확인 | 동작 확인 |
| 등받이 이동 | 명령은 접수되나 위치 값이 0으로 유지되고 화면도 바뀌지 않음 |

- `moveSeatBackrestAngle`로 FORWARD와 BACKWARD를 보내면 요청은 정상 접수되지만, `getCurrentSeatBackrestAnglePosition` 값과 위치 변경 알림이 계속 0입니다.
- 차량 설정의 시트 모드에서 **휴식**을 선택해도 값이 바뀌지 않고, Virtual Console에도 등받이 항목이 없습니다.
- 공모전 운영사무국에 문의한 결과, 이 API의 에뮬레이터 지원은 안내받았지만 위치 값이 0으로 고정되는 현상의 해결 방법은 아직 없습니다. FORWARD는 시트가 바라보는 방향, 즉 등받이를 세우는 방향이라고 안내받았고, 앱도 이 방향으로 구현했습니다.

그래서 시트 제어 코드는 실제 차량과 같은 방식으로 SDK를 호출하고, 화면의 등받이 그림만 시각화로 대체합니다. 시각화 중에는 "시각화 화면"이라는 문구와 실제 등받이 값을 함께 표시해, 실제 동작과 구분할 수 있게 했습니다.

## 구조

```text
app/src/main/java/ai/pleos/playground/sample/vehicle/
├─ MainActivity.kt            눕카 화면(Compose)과 등받이 시각화
├─ NupcarApplication.kt       세션 컨트롤러 생성
└─ session/
   ├─ RestProfile.kt          휴식 모드와 설정값
   ├─ RestSessionController.kt  준비 → 휴식 → 기상 → 복원 상태 관리, 안전 확인
   ├─ SeatMover.kt            위치를 확인하며 목표 각도까지 등받이 이동
   ├─ VehicleGateway.kt       차량 기능 인터페이스
   ├─ PleosVehicleGateway.kt  Pleos Vehicle SDK 연동(시트, 공조, 조명, 기어)
   ├─ SimulatedVehicleGateway.kt  시뮬레이션 모드와 테스트용 가상 차량
   ├─ GeneratedRestAudio.kt   휴식·기상 사운드 생성
   ├─ RestSessionService.kt   백그라운드 유지용 포그라운드 서비스
   └─ ProfileStore.kt         모드별 설정 저장
```

세션 로직은 단위 테스트로 검증합니다.

```bash
./gradlew :app:testDebugUnitTest
```

## 사용한 SDK / API

- **Seat:** 등받이 위치 범위·현재 위치 조회, `moveSeatBackrestAngle`, 위치 변경 알림, 뒷좌석 탑승 여부(`getCurrentSeatOccupancy`)
- **HVAC:** 공조 전원과 온도 조회·설정
- **Light:** 앰비언트 조명 스위치와 밝기 조회·설정
- **기어:** Android Automotive 차량 속성 `GEAR_SELECTION`

## 참고

- 디버그 빌드에서는 제목 **눕카**를 길게 누르면 개발용 차량 기능 점검 화면이 열립니다. 이 프로젝트는 Pleos Vehicle SDK 샘플에서 시작했으며, 샘플 설명은 [docs/vehicle-sdk-sample.md](docs/vehicle-sdk-sample.md)에 남겨 두었습니다.
