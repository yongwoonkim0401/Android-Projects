# GPS Toggle Widget

홈 화면 위젯 하나로 GPS 위치 로그 수집을 켜고 끄고, 기록된 경로를 지도에서 확인하는 개인용 Android 앱.

## 주요 기능

홈 화면 위젯을 클릭 횟수(450ms 창)로 구분해 세 가지 동작을 수행한다.

| 클릭 | 동작 |
|---|---|
| 1회 | 위치 수집 시작/중지 토글 |
| 2회 | 저장된 GPX 경로를 지도에서 보기 |
| 3회 이상 | 지도 선택 설정 화면 열기 |

- **위치 수집**: GPS(fix 확보 전엔 네트워크 위치 보조)로 5초 간격 좌표 수집, 포그라운드 서비스로 동작
- **GPX 저장**: 수집 시작 시각을 파일명으로 `Track yyyy-MM-dd HHmmss.gpx` 생성, 위도/경도/고도/시각 기록
- **고도 보정**: Android 14+ 에서는 지오이드 보정된 해수면(MSL) 기준 고도 우선 사용 (WGS84 타원체 고도는 한국 기준 약 +23~29m 오차)
- **경로 뷰어**: 저장된 GPX 목록 → 선택 시 지도에 경로 표시 + 고도/속도 그래프(핀치 확대, 드래그 이동, 탭으로 지점 선택 연동). 길게 눌러 파일 이름 변경/삭제
- **지도 선택**: OpenStreetMap(기본, 키 불필요) / 네이버 / 카카오 중 선택. 네이버·카카오는 API 키가 없으면 자동으로 OSM 폴백
- **위젯 아이콘**: 수집 중엔 활성(파랑) 위성 아이콘 + 고도 텍스트, 중지 중엔 비활성(회색) 아이콘

## 프로젝트 구조

```
app/src/main/java/com/example/gpstogglewidget/
├── GpsToggleWidget.kt          위젯 프로바이더 + 상태 저장(SharedPreferences) + 클릭 카운트 로직
├── ToggleActivity.kt           투명 Activity — 포그라운드 서비스 시작을 위해 앱을 잠깐 포그라운드로 전환
├── LocationTrackingService.kt  포그라운드 서비스 — 위치 수집 + GPX 파일 기록
├── GpsStateReceiver.kt         시스템 GPS on/off 변화 감지 → 위젯 갱신
├── MainActivity.kt             위치 권한(정확한 위치 / 백그라운드 "항상 허용") 요청 화면
├── GpxViewerActivity.kt        GPX 파일 목록 + 지도/그래프 뷰어(WebView)
├── MapSettingsActivity.kt      지도 provider(OSM/네이버/카카오) 선택 화면
└── MapKeys.kt                  네이버/카카오 지도 API 키
```

## 빌드 및 설치

```bash
./gradlew assembleDebug
```

생성된 APK를 기기에 설치한 뒤, 앱을 한 번 열어 위치 권한을 허용해야 위젯이 정상 동작한다.

1. 앱 실행 → "위치 권한 허용" 버튼으로 정확한 위치 권한 허용
2. "항상 허용(백그라운드)" 버튼으로 백그라운드 위치 권한 허용 (Android 11+, 위젯이 백그라운드에서도 동작하려면 필수)
3. 홈 화면에 위젯 추가

## 권한

| 권한 | 용도 |
|---|---|
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 위도/경도/고도 조회 |
| `ACCESS_BACKGROUND_LOCATION` | 앱이 백그라운드일 때도 위치 수집 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` | 위치 수집 포그라운드 서비스 |
| `POST_NOTIFICATIONS` | 수집 중 알림 표시 |
| `INTERNET` | GPX 뷰어 지도 타일 로딩 |

시스템 GPS 자체를 켜고 끄지는 않는다 — GPS가 꺼져 있으면 위젯 클릭 시 안내 토스트만 표시한다.

## 참고

- `LocationTrackingService.isRunning`(static 변수)으로 실제 서비스 생존 여부를 판단한다. SharedPreferences 플래그만으로는 프로세스 강제종료 시 상태가 어긋날 수 있기 때문.
- `MapKeys.kt`에 네이버/카카오 API 키가 평문으로 들어있다. 저장소를 공개하거나 공유할 경우 키를 제거하거나 로컬 설정으로 분리할 것.
- GPX 파일은 `getExternalFilesDir(DIRECTORY_DOCUMENTS)`(앱 전용 외부 저장소)에 저장된다.
