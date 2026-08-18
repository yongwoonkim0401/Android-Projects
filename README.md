# Android Projects

개인용 Android 앱 모음. 각 폴더가 독립된 Gradle 프로젝트입니다.

| 프로젝트 | 설명 |
|---|---|
| [AndroidCCTV](AndroidCCTV) | 구형 폰을 CCTV로 — 자체 구현 HTTP 서버로 H.264/MJPEG 실시간 스트리밍 + 웹 제어 패널 |
| [BigSpaceKeyboard](BigSpaceKeyboard) | 스페이스바를 더 넓고 더 높게 만든 키보드(IME). 키 배치/터치/렌더링 직접 구현, 멀티터치로 빠르게 쳐도 안 밀림, 모음 8개로 줄이고 연타로 채운 한글 두벌식, 길게 눌러 쓰는 기호 39종, 기호·이모지 팔레트 1,659자(카테고리별 스크롤), 클립보드 붙여넣기, 키보드 위에서 바로 여는 설정 판 |
| [FakeGPS](FakeGPS) | osmdroid 기반 모의 위치(Mock GPS) 주입 앱. 지도 탭/드래그로 위치 이동, 출발점→도착점을 찍으면 OSRM 도로 경로를 따라 지정 속도(1~200km/h)로 자동 이동 (Google Maps API 키 불필요) |
| [GpsToggleWidget](GpsToggleWidget) | 홈 화면 위젯으로 GPS 위치 로그 수집 on/off + GPX 경로/그래프 뷰어 |
| [SangilWidget](SangilWidget) | 홈 화면 위젯 탭으로 "산길샘" 앱의 시작/종료 버튼을 AccessibilityService로 자동 클릭 |
| [SciCalc](SciCalc) | 갭 버퍼 + 스택 기반 파싱으로 긴 수식도 힙 할당 없이 처리하는 공학용 계산기 |
| [WeightTracker](WeightTracker) | 캘린더 기반 체중 기록 + 기간별 그래프, Room DB, CSV 가져오기/내보내기 |

각 프로젝트의 상세 내용(설치, 사용법, 구조)은 폴더 안의 README를 참고하세요.

## 공통 개발 환경

- Kotlin, Gradle (각 프로젝트에 wrapper 포함)
- Android Studio로 각 폴더를 개별 프로젝트로 열어서 빌드/실행
- 명령줄 빌드 예시:
  ```bash
  cd <프로젝트 폴더>
  ./gradlew assembleDebug
  ```

## 주의

- `GpsToggleWidget/app/src/main/java/com/example/gpstogglewidget/MapKeys.kt`에 네이버/카카오 지도 API 키가 평문으로 들어있습니다. 저장소가 비공개(private)가 아니라면 반드시 제거하거나 로컬 설정으로 분리하세요.
- 각 프로젝트의 `local.properties`, 키스토어(`*.jks`/`*.keystore`), 빌드 산출물(`build/`, `.gradle/`)은 `.gitignore`로 제외되어 있습니다.
