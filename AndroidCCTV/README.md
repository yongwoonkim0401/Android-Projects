# 폰 CCTV (AndroidCCTV)

쓰지 않는 구형 안드로이드 폰을 **전면 카메라 CCTV**로 만드는 앱.
폰 안에 작은 HTTP 서버가 떠서, 브라우저로 **실시간 영상 + 원격 제어**를 할 수 있다.

- 최소 지원: **Android 5.0 (API 21)** / 대상: Android 14 (API 34)
- 외부 라이브러리 없이 자체 구현한 HTTP 서버 + fMP4 muxer
- **하드웨어 H.264** 스트림(기본) + MJPEG(호환용) 두 가지를 동시에 제공

---

## 1. 스트리밍 방식

| | H.264 (기본) | MJPEG (호환용) |
|---|---|---|
| 경로 | `/stream.mp4` | `/stream.mjpeg` |
| 대역폭 (640×480) | **0.3~0.6 Mbps** | 1.4~2.4 Mbps |
| 지연 | 0.3~1초 | 0.1~0.3초 |
| CPU | 하드웨어 인코더 (거의 0) | 소프트웨어 JPEG (높음) |
| 재생 | 브라우저(MSE) | `<img>`, VLC, 어떤 브라우저든 |

카메라 출력을 MediaCodec 하드웨어 인코더 Surface 에 직접 연결하므로 CPU 로 픽셀을 만지지 않는다.
구형 폰에서 발열·배터리 소모가 MJPEG 방식보다 크게 낮다.

```
CameraX Preview ──Surface──▶ MediaCodec (H.264 하드웨어)
                                  │ NAL
                     ┌────────────┴────────────┐
                     ▼                         ▼
          Fmp4Muxer (moof+mdat)        MediaMuxer (일반 MP4 녹화)
                     ▼
        GET /stream.mp4 ──▶ 브라우저 MSE

CameraX ImageAnalysis ──▶ 움직임 감지(Y 평면만)
                       └─▶ 필요할 때만 JPEG → MJPEG · 스냅샷 · 이벤트 사진
```

`Preview + ImageAnalysis` 는 CameraX 가 모든 기기에서 지원을 보장하는 조합이라 LEGACY 수준 구형
카메라에서도 안전하다. 만약 이 조합마저 거부하는 기기라면 자동으로 H.264 를 포기하고 MJPEG 만 제공한다.

**MJPEG 는 보는 사람이 있을 때만 인코딩한다.** 아무도 MJPEG 를 보지 않으면 JPEG 를 아예 만들지 않아
CPU 를 아끼고, 스냅샷·이벤트 사진이 필요한 순간에만 한 장씩 만든다.

### 브라우저 호환성
- Chrome / Edge / Firefox / Android 브라우저: H.264 정상
- **iPhone Safari**: iOS 17.1 미만은 MSE 미지원 → 자동으로 MJPEG 로 전환된다(패널에서 수동 전환도 가능)
- 회전은 MP4 헤더(tkhd 행렬)로 처리하고, 좌우 반전은 표시할 때 CSS 로 뒤집는다

---

## 2. 설치

### Android Studio
`C:\USER\YWKIM\Claude\AndroidCCTV` 열고 실행(▶).

### ADB (PowerShell 은 앞에 `&` 필요)

```bash
& "C:\Users\adca\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r "C:\USER\YWKIM\Claude\AndroidCCTV\app\build\outputs\apk\debug\app-debug.apk"
```

---

## 3. 사용법

1. 폰에서 앱 실행 → **카메라 / 알림** 권한 허용
2. **[CCTV 시작]**
3. 화면에 뜬 주소(예: `http://192.168.0.12:8080/?token=3f9a1c7b2d4e5f60`)를 브라우저에 입력
4. 화면을 꺼도 계속 동작한다. **충전기 연결 권장**

### 권장 설정
| 상황 | 해상도 | FPS | 비트레이트 | 키프레임 간격 |
|---|---|---|---|---|
| 집 안 Wi‑Fi | 640×480 | 10 | 600~800 kbps | 2초 |
| 외부(LTE) 접속 | 640×480 | 5~8 | 300~400 kbps | 2초 |
| 아주 느린 회선 | 320×240 | 5 | 150 kbps | 3초 |

비트레이트가 대역폭을 결정하고, **FPS 가 배터리를 결정한다.**

---

## 3-1. 전력 관리

배터리는 대부분 카메라 파이프라인에서 나간다. 앱은 다음과 같이 줄인다.

| 항목 | 동작 |
|---|---|
| 카메라 촬영 속도 | 설정한 FPS 를 `CONTROL_AE_TARGET_FPS_RANGE` 로 카메라에 직접 요청한다. 30fps → 10fps 면 센서·ISP·인코더가 모두 함께 줄어든다. 기기가 지원하는 범위 안에서만 고르며, 지원 목록에 없으면 적용하지 않는다(잘못된 값을 넣으면 카메라가 아예 안 열린다) |
| 시청자 0명 | H.264 인코더는 계속 돌지만 AVCC 변환·fMP4 조각 생성을 건너뛴다. 프레임마다 있던 데이터 복사 3~4회가 사라진다. 접속 지연은 없다 |
| 끊긴 시청자 | 15초 동안 한 바이트도 못 보내면 감시 스레드가 소켓을 닫는다. 자바 소켓에는 쓰기 타임아웃이 없어서, 이게 없으면 조용히 사라진 시청자 때문에 인코딩이 몇 분씩 전속력으로 계속 돈다(모바일·VPN 에서 흔한 상황) |
| 웹 패널 | 탭이 가려지고 60초가 지나면 스트림을 끊는다(돌아오면 자동 재개). 숨은 탭에서는 상태 조회도 멈춘다 |
| Wi‑Fi 잠금 | 기본은 일반 잠금. 고성능 잠금(절전 해제)은 설정에서 선택 |
| 상태 조회 | IP 목록·파일 개수·여유 공간을 15초 캐시. 알림은 20초 간격 + 내용이 바뀔 때만 갱신 |

패널의 **전력** 카드에서 실제 적용된 FPS 범위와 인코딩 상태(전송 중 / 절전)를 확인할 수 있다.

Tailscale 같은 상시 VPN 도 그 자체로 전력을 쓴다. 오래 켜 둘 것이라면 충전기 연결을 권한다.

### 보는 쪽(시청 기기) 배터리

폰으로 오래 보면 시청하는 폰도 빨리 닳는다. 피할 수 없는 부분과 줄일 수 있는 부분이 나뉜다.

**피할 수 없는 것**
- `<video>` 재생 중에는 모바일 브라우저가 **화면을 강제로 켜 둔다.** 화면이 최대 소비원이다.
- Tailscale(WireGuard)은 수신 패킷마다 복호화하므로, 스트림을 계속 받으면 보는 폰의 CPU 도 계속 돈다.

**줄일 수 있는 것 — 패널 상단의 세 가지 모드**

| 모드 | 연결 | 화면 | 용도 |
|---|---|---|---|
| H.264 | 계속 유지 | 재생 중 꺼지지 않음 | 실시간 감시 |
| MJPEG | 계속 유지 | 꺼짐 | 호환성 |
| **스냅샷** | **없음(N초마다 1장)** | **정상적으로 꺼짐** | **폰으로 잠깐 확인** |

**스냅샷 모드**는 연결을 유지하지 않고 3~60초 간격으로 사진 한 장만 받는다. 무선이 대부분 잠들 수 있고
CCTV 폰도 그때만 JPEG 을 만들어서 양쪽 모두 전력이 크게 준다. 폰에서 "지금 집에 별일 없나" 확인하는
용도라면 이 모드를 쓰는 게 맞다.

그 밖에 패널이 하는 절약:
- 탭이 가려지고 60초가 지나면 스트림을 끊고, 상태 조회 타이머도 **아예 해제**한다(돌아오면 자동 재개)
- 파일 목록은 저장 개수가 실제로 바뀌었을 때만 다시 받는다(예전에는 20초마다 썸네일 수십 장을 재요청)
- 저장 파일에 `Cache-Control: immutable` 을 붙여 썸네일을 다시 내려받지 않는다

### 연결이 끊겼을 때 (VPN 종료·외출 등)

CCTV 폰에 닿지 않게 되면 보는 폰이 계속 재접속을 시도하며 무선을 깨운다. 이게 배터리 소모의
큰 원인이라 단계적으로 물러난다.

| 경과 | 동작 |
|---|---|
| 실패 직후 | 3초 후 재시도 |
| 이후 | 간격을 1.8배씩 늘려 최대 60초 |
| 5분 연속 실패 | **재시도를 완전히 중단**하고 "탭하면 다시 연결" 안내 표시 |
| 탭 복귀 / 안내 탭 | 즉시 재개 |

중단 상태에서는 스트림도 상태 조회도 타이머가 남지 않는다. Tailscale 을 끄고 패널을 열어 둔 채
잊어버려도 5분 뒤부터는 아무 일도 하지 않는다.

### 오래 켜두기
- 앱의 **[배터리 최적화 제외 설정]** 실행
- 제조사 절전 예외 등록 (삼성: 배터리 → 백그라운드 사용 제한 해제)
- 개발자 옵션의 "충전 중 화면 켜짐 유지"를 끄면 화면이 꺼져 발열이 줄어든다

---

## 4. 웹 제어 패널

| 분류 | 기능 |
|---|---|
| 스트림 | H.264 / MJPEG 전환, 비트레이트, 키프레임 간격, 실시간 사용량(kbps) |
| 영상 | 해상도, 90° 회전, 좌우 반전, MJPEG 화질·FPS |
| 카메라 | 전면↔후면 전환, 플래시(후면), 카메라 재시작 |
| 저장 | 스냅샷, MP4 녹화 시작/중지(재인코딩 없음) |
| 감지 | 움직임 감지 on/off, 민감도, 재감지 간격, 감지 시 사진 자동 저장 |
| 파일 | 이벤트/스냅샷/녹화 목록 · 다운로드 · 삭제 |
| 상태 | 배터리·온도, 저장공간, 시청자 수, 가동 시간, 오류 |

---

## 5. HTTP API

인증은 3가지 중 아무 방식이나 가능:
`?token=<토큰>` · 헤더 `X-Auth-Token: <토큰>` · Basic 인증(비밀번호 자리에 토큰)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/` | 제어 패널 |
| GET | `/stream.mp4` | H.264 fragmented MP4 스트림 |
| GET | `/stream.mjpeg` | MJPEG 스트림 |
| GET | `/snapshot.jpg` | 현재 프레임 1장(없으면 즉시 생성해 반환) |
| GET | `/api/status` | 상태 JSON |
| POST | `/api/config` | 설정 변경 (JSON 본문) |
| POST | `/api/action/<이름>` | 동작 실행 |
| GET | `/api/media/<종류>` | 파일 목록 (`events`/`snapshots`/`videos`) |
| POST | `/api/media/<종류>/delete?name=` | 파일 1개 삭제 |
| POST | `/api/media/<종류>/clear` | 폴더 비우기 |
| GET | `/media/<종류>/<파일명>` | 파일 다운로드 (Range 지원) |

**동작**: `torch`(`on=`), `lens`(`lens=front|back`), `zoom`(`value=0~1`), `snapshot`, `record`(`on=`), `restart`, `stop`

**설정 키**: `h264Enabled`, `bitrateKbps`(100~8000), `keyInterval`(1~10초), `resolution`("640x480"), `width`, `height`, `fps`(1~30, **카메라 촬영 속도**), `quality`(MJPEG 10~100), `rotation`(0/90/180/270), `mirror`, `motionEnabled`, `motionSensitivity`(1~100), `motionCooldown`, `motionSaveShot`, `highPerfWifi`, `autoStart`, `maxEvents`, `port`

```bash
curl "http://192.168.0.12:8080/api/status?token=TOKEN"
curl -X POST "http://192.168.0.12:8080/api/config?token=TOKEN" -H "Content-Type: application/json" -d "{\"fps\":5,\"bitrateKbps\":300}"
curl -X POST "http://192.168.0.12:8080/api/action/record?on=true&token=TOKEN"
curl -o now.jpg "http://192.168.0.12:8080/snapshot.jpg?token=TOKEN"
```

`bitrateKbps`·`highPerfWifi` 는 즉시 적용된다.
`fps`·`rotation`·`keyInterval`·`resolution`·`h264Enabled` 는 카메라 세션을 다시 열기 때문에
1~2초 화면이 끊긴다.

상태 응답의 `power` 항목에서 실제 적용된 FPS 범위(`fpsRange`)와 인코딩 여부(`encoding`)를 볼 수 있다.

---

## 6. 집 밖에서 보기

H.264 스트림도 HTTP 평문이므로 **인터넷에 포트를 직접 열지 말 것**. 권장 순서:

1. **Tailscale / WireGuard** — 폰과 보는 기기에 설치하면 어디서든 `http://<폰의 VPN IP>:8080`
2. **Cloudflare Tunnel** (Termux) — HTTPS 터널, 무료 플랜은 주소가 매번 바뀜
3. 공유기 포트포워딩 — 토큰 재발급 + 포트 변경 필수. 통신사 CGNAT 회선에서는 아예 동작하지 않는다

외부 접속 시에는 비트레이트를 300~400 kbps 로 낮추는 것이 좋다.

---

## 7. 구조

```
app/src/main/java/com/example/androidcctv/
├── MainActivity.kt      화면: 시작/중지, 주소·토큰, 로컬 미리보기
├── CctvService.kt       포그라운드 서비스 + 상태/설정/동작 API
├── CameraController.kt  CameraX 바인딩, 인코더 연결, 프레임 처리
├── H264Encoder.kt       MediaCodec 하드웨어 인코더(Surface 입력)
├── H264Nal.kt           Annex-B ↔ AVCC 변환, SPS/PPS 추출
├── Fmp4Muxer.kt         fragmented MP4 생성(ftyp/moov/moof/mdat 직접 작성)
├── VideoHub.kt          H.264 조각을 시청자별 큐로 배포(밀리면 키프레임 재동기)
├── Mp4Recorder.kt       MediaMuxer 로 일반 MP4 저장(재인코딩 없음)
├── HttpServer.kt        의존성 없는 HTTP 서버
├── StreamHub.kt         MJPEG 최신 프레임 1장 배포
├── Yuv.kt               YUV_420_888 → NV21 → 회전/반전 → JPEG
├── MotionDetector.kt    32×24 격자 밝기 비교(Y 평면만 읽음)
├── Storage.kt           파일 관리 및 자동 정리
├── Prefs.kt             설정 저장
├── NetUtil.kt           로컬 IP 조회
└── BootReceiver.kt      재부팅 후 자동 시작
app/src/main/assets/web/index.html   웹 제어 패널(MSE 재생기 포함, 단일 파일)
app/src/test/java/...                fMP4 컨테이너 구조 · NAL 파싱 단위 테스트
```

저장 위치: `Android/data/com.example.androidcctv/files/cctv/{events,snapshots,videos}`
이벤트 사진은 `maxEvents`(기본 300장)를 넘으면 오래된 것부터 자동 삭제된다.

테스트 실행:

```bash
gradlew testDebugUnitTest
```

---

## 8. 알아둘 점

- **녹화에 소리는 담기지 않는다.** 마이크를 쓰면 카메라 use case 가 3개가 되어 구형(LEGACY) 기기에서
  바인딩이 거부된다. 그 대신 이미 인코딩된 H.264 를 그대로 파일에 담아 녹화 중에도 CPU 부담이 늘지 않는다.
- 녹화 파일은 첫 키프레임부터 시작하므로 [녹화 시작] 직후 최대 1초(키프레임 간격)가 잘릴 수 있다.
- 전면 카메라에는 대부분 플래시가 없다. 야간에는 **후면 카메라 + 플래시**가 낫다.
- 네트워크가 느린 시청자는 조각이 밀리면 자동으로 다음 키프레임부터 다시 붙는다(순간 끊김).
- 토큰을 재발급하면 기존 연결이 모두 끊긴다.
