# Fake GPS (모의 위치 앱)

지도를 탭하거나 드래그해서 안드로이드 기기에 가짜 GPS 신호를 주입하는 앱입니다.
Google Maps API 키가 필요 없는 OpenStreetMap(osmdroid)을 사용합니다.

> ⚠️ 이 앱은 앱 개발/테스트, 위치 기반 기능 디버깅용입니다. 안드로이드가 개발자
> 옵션의 **"모의 위치 앱 선택"** 으로 공식 지원하는 기능이며, 그 지정 없이는
> 동작하지 않습니다.

## 사용법

1. **빌드 & 설치** (아래 참고)
2. 앱 실행 후 위치 권한 허용
3. **개발자 옵션 활성화**
   - 설정 > 휴대전화 정보 > 빌드 번호 7회 탭
4. **모의 위치 앱 지정**
   - 설정 > 개발자 옵션 > "모의 위치 앱 선택" > **Fake GPS** 선택
5. 앱 실행 시 **실제 현재 위치**로 지도가 자동 이동합니다.
   - 위치 **권한 허용** + 기기의 **위치 서비스(GPS) ON** 상태여야 합니다. 꺼져 있으면 안내
     Toast와 함께 위치 설정 화면이 열립니다.
   - 현재 위치는 우선 **Fused Location(Google Play 서비스)** 으로 실제 fix를 받아오고,
     실패 시 LocationManager(GPS/네트워크)로 폴백합니다. 실내에서도 Wi-Fi/셀 기반으로 대개 잡힙니다.
6. 화면 구성 (전체 화면 지도):
   - 화면 **중앙의 빨간 십자선**이 항상 현재 가짜 위치입니다.
   - 십자선 **바로 아래의 ON/OFF 스위치**가 시작/멈춤입니다.
     - **ON** → 현재 십자선 위치에서 가짜 GPS 주입이 시작됩니다.
     - **OFF** → 모의 위치를 해제하고 **실제(Original) 위치로 되돌아옵니다.**
   - **ON 상태에서 지도를 터치 드래그** → 지도가 움직이면서 십자선(중앙) 아래 좌표로 가짜 GPS가
     **드래그 방향으로 실시간 이동**합니다. 손을 떼면 그 자리에 고정됩니다.
     (OFF 상태에서는 드래그해도 지도만 둘러보며 위치는 바뀌지 않습니다.)
   - **확대/축소**: 두 손가락 핀치 또는 우측 하단 **+/− 버튼**. 최대 21단계까지 확대되어
     세밀하게 위치를 맞출 수 있습니다.
   - 하단 버튼 패널은 제거되어 화면 전체를 드래그 영역으로 사용합니다.
7. **백그라운드 유지**: 위치를 설정하면 **포그라운드 서비스**가 실행되어(알림 표시),
   지도/내비 등 **다른 앱으로 전환해도 모의 위치가 계속 유지**됩니다. 알림의 **"중지"** 를
   누르면 주입을 멈춥니다. (이 기능이 없으면 앱을 벗어난 직후 실제 GPS로 되돌아갑니다.)

## 빌드

### Android Studio (권장)
1. Android Studio에서 `FakeGPS` 폴더 열기
2. Gradle 동기화가 자동으로 실행되며 Gradle 8.9와 wrapper를 내려받습니다
3. 기기/에뮬레이터 연결 후 Run

### 명령줄
Gradle wrapper(`gradlew`)는 Android Studio가 최초 동기화 시 생성합니다.
이미 wrapper가 있다면:
```
./gradlew assembleDebug
```
APK 위치: `app/build/outputs/apk/debug/app-debug.apk`

## 주요 파일
- `app/src/main/java/com/example/fakegps/MainActivity.kt` — 지도, 터치 처리, 현재 위치, UI
- `app/src/main/java/com/example/fakegps/MockLocationService.kt` — 포그라운드 서비스, 모의 위치 주입
- `app/src/main/AndroidManifest.xml` — 권한 (`ACCESS_MOCK_LOCATION`, 포그라운드 서비스 포함)
- `app/src/main/res/layout/activity_main.xml` — UI

## 동작 원리
포그라운드 서비스(`MockLocationService`)가 `LocationManager.addTestProvider()` 로
GPS/NETWORK 테스트 프로바이더를 등록하고 `setTestProviderLocation()` 으로 가짜 위치를,
그리고 `FusedLocationProviderClient.setMockMode()/setMockLocation()` 으로 **Fused Provider**
(대부분의 앱이 사용)까지 함께 주입합니다. 서비스가 매초 갱신하므로 **다른 앱으로 전환해도**
실제 GPS로 덮어써지지 않고 유지됩니다.
- **드래그 중**: `MapListener.onScroll` 에서 지도 중심(`mapCenter`, 화면 중앙 십자선)을
  읽어 주입합니다. 직전 중심과의 거리·경과 시간으로 실제 속도(m/s)와 방위(bearing)를
  계산해 함께 실어 보냅니다. 사용자 조작인지 구분하기 위해 터치 상태를 별도 오버레이로
  추적하며(제스처는 소비하지 않아 지도는 정상적으로 팬/줌됨), 프로그램에 의한 이동은 무시합니다.
- **시작 위치**: 실행 시 `getLastKnownLocation` + 단발성 위치 업데이트로 실제 현재 위치에
  지도를 중심 이동합니다(모의 프로바이더 등록 전에 실제 위치를 읽음).
- **정지/고정**: 1초마다 같은 좌표를 재전송해 fix 가 만료되지 않게 유지합니다.
- **자동 이동**: `GeoPoint.destinationPoint(거리, 방위)` 로 매 틱마다 좌표를 전진시키고
  지도도 따라 이동시켜 십자선이 그 점 위에 유지됩니다.
