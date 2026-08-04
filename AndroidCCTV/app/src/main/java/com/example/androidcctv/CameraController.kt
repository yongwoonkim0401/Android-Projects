package com.example.androidcctv

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 카메라 바인딩과 두 갈래 출력을 담당한다.
 *
 *   Preview        → MediaCodec 하드웨어 H.264 인코더 → fMP4 스트림 / MP4 녹화
 *   ImageAnalysis  → 움직임 감지(Y 평면만) + 필요할 때만 JPEG(MJPEG·스냅샷·이벤트 사진)
 *
 * Preview + ImageAnalysis 는 CameraX 가 모든 기기에서 지원을 보장하는 조합이라
 * 구형(LEGACY) 카메라에서도 안전하다.
 */
class CameraController(
    private val ctx: Context,
    private val owner: LifecycleOwner,
    private val prefs: Prefs,
    private val storage: Storage
) {

    companion object {
        private const val TAG = "CctvCamera"
    }

    /** 움직임 감지 시 호출(시각, 저장된 스냅샷 파일 또는 null) */
    var onMotion: ((Long, File?) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val analysisExec = Executors.newSingleThreadExecutor()
    private val cameraExec = Executors.newSingleThreadExecutor()
    private val motion = MotionDetector()
    private val snapshotWanted = AtomicBoolean(false)

    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var camera: Camera? = null

    @Volatile private var encoder: H264Encoder? = null
    @Volatile private var streamMuxer: Fmp4Muxer? = null
    @Volatile private var encoderFormat: MediaFormat? = null
    @Volatile private var recorder: Mp4Recorder? = null

    @Volatile var lastError: String? = null; private set
    @Volatile var frameWidth = 0; private set
    @Volatile var frameHeight = 0; private set
    @Volatile var activeLens = "front"; private set
    @Volatile var torchAvailable = false; private set
    @Volatile var torchOn = false; private set
    @Volatile var h264Active = false; private set
    @Volatile var isRecording = false; private set
    @Volatile var recordingFile: File? = null; private set
    @Volatile var recordingStartedAt = 0L; private set
    @Volatile var motionCount = 0; private set
    @Volatile var lastMotionAt = 0L; private set
    @Volatile var bound = false; private set
    @Volatile var streamRotation = 0; private set

    /** 실제로 카메라에 요청한 FPS 범위(지원되지 않으면 null) */
    @Volatile var appliedFpsRange: String? = null; private set

    val motionScore: Double get() = motion.lastScore
    val recordedBytes: Long get() = recorder?.bytes ?: 0L

    private var lastEmitAt = 0L
    private var lastMotionSaveAt = 0L

    fun start() {
        VideoHub.keyFrameRequester = { encoder?.requestKeyFrame() }
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                provider = future.get()
                bind()
            } catch (t: Throwable) {
                lastError = "카메라 초기화 실패: ${t.message}"
                Log.e(TAG, "provider", t)
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun stop() {
        stopRecording()
        main.post {
            try {
                provider?.unbindAll()
            } catch (ignored: Throwable) {
            }
            releaseEncoder()
            bound = false
            camera = null
            analysis = null
            preview = null
            StreamHub.reset()
            VideoHub.reset()
            motion.reset()
        }
    }

    fun release() {
        VideoHub.keyFrameRequester = null
        stop()
        analysisExec.shutdown()
        cameraExec.shutdown()
    }

    /** 렌즈·해상도·H.264 설정이 바뀌었을 때 다시 바인딩한다. */
    fun rebind() {
        main.post { bind() }
    }

    fun requestSnapshot() {
        snapshotWanted.set(true)
    }

    // ------------------------------------------------------------------ 바인딩

    private fun bind() {
        val p = provider ?: return
        try {
            p.unbindAll()
            releaseEncoder()
            VideoHub.reset()
            bound = false
            h264Active = false

            var selector = if (prefs.lens == "back") {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            var lensName = prefs.lens
            if (!p.hasCamera(selector)) {
                selector = if (lensName == "front") {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
                lensName = if (lensName == "front") "back" else "front"
                if (!p.hasCamera(selector)) {
                    lastError = "사용 가능한 카메라가 없습니다"
                    return
                }
            }
            activeLens = lensName

            // targetRotation 을 ROTATION_0 으로 고정하면 필요한 회전각이
            // 두 use case 모두 센서 방향과 같아져 계산이 단순해진다.
            val sensorRotation = try {
                selector.filter(p.availableCameraInfos).firstOrNull()?.sensorRotationDegrees ?: 90
            } catch (t: Throwable) {
                90
            }
            streamRotation = ((sensorRotation + prefs.rotation) % 360 + 360) % 360

            val target = Size(
                maxOf(prefs.width, prefs.height),
                minOf(prefs.width, prefs.height)
            )

            @Suppress("DEPRECATION")
            val iaBuilder = ImageAnalysis.Builder()
                .setTargetResolution(target)
                .setTargetRotation(Surface.ROTATION_0)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

            // 카메라 자체의 촬영 속도를 낮춘다. 센서·ISP·인코더가 함께 덜 돌기 때문에
            // 배터리에 가장 크게 작용한다. 기기가 지원하는 범위 중에서만 고른다.
            appliedFpsRange = null
            val fpsRange = pickFpsRange(selector, p, prefs.fps)
            if (fpsRange != null) {
                try {
                    Camera2Interop.Extender(iaBuilder).setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange
                    )
                    appliedFpsRange = "${fpsRange.lower}-${fpsRange.upper}"
                } catch (t: Throwable) {
                    Log.w(TAG, "FPS 범위 지정 실패", t)
                }
            }

            val ia = iaBuilder.build()
            ia.setAnalyzer(analysisExec) { image -> analyze(image) }
            analysis = ia

            val useCases = ArrayList<UseCase>()
            useCases.add(ia)

            if (prefs.h264Enabled) {
                @Suppress("DEPRECATION")
                val pv = Preview.Builder()
                    .setTargetResolution(target)
                    .setTargetRotation(Surface.ROTATION_0)
                    .build()
                pv.setSurfaceProvider(cameraExec) { request -> provideEncoderSurface(request) }
                preview = pv
                useCases.add(pv)
            } else {
                preview = null
            }

            camera = try {
                p.bindToLifecycle(owner, selector, *useCases.toTypedArray())
            } catch (t: Throwable) {
                Log.w(TAG, "Preview 포함 바인딩 실패, MJPEG 만 사용", t)
                lastError = "이 기기에서 H.264 스트림을 쓸 수 없어 MJPEG 만 제공합니다"
                releaseEncoder()
                preview = null
                p.unbindAll()
                p.bindToLifecycle(owner, selector, ia)
            }

            torchAvailable = camera?.cameraInfo?.hasFlashUnit() ?: false
            if (!torchAvailable) torchOn = false
            motion.reset()
            bound = true
            if (prefs.h264Enabled && preview != null) lastError = null
        } catch (t: Throwable) {
            bound = false
            lastError = "카메라 바인딩 실패: ${t.message}"
            Log.e(TAG, "bind", t)
        }
    }

    /**
     * 기기가 지원하는 AE 목표 FPS 범위 중 desired 에 가장 가까우면서 그 이하인 것을 고른다.
     * 지원 목록에 없는 값을 넣으면 세션 설정이 실패해 카메라가 아예 안 열리므로
     * 반드시 목록 안에서만 선택한다.
     */
    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun pickFpsRange(
        selector: CameraSelector,
        provider: ProcessCameraProvider,
        desired: Int
    ): Range<Int>? {
        return try {
            val info: CameraInfo = selector.filter(provider.availableCameraInfos).firstOrNull()
                ?: return null
            val ranges = Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            if (ranges == null || ranges.isEmpty()) return null

            // 1) 정확히 고정된 범위 [n,n]
            ranges.firstOrNull { it.lower == desired && it.upper == desired }
            // 2) 상한이 desired 이하인 것 중 가장 높은 것
                ?: ranges.filter { it.upper <= desired }.maxByOrNull { it.upper * 100 + it.lower }
                // 3) 그래도 없으면 상한이 가장 낮은 것(조명이 어두우면 AE 가 알아서 더 낮춘다)
                ?: ranges.minWithOrNull(
                    compareBy({ it.upper }, { it.lower })
                )
        } catch (t: Throwable) {
            Log.w(TAG, "지원 FPS 범위 조회 실패", t)
            null
        }
    }

    /** CameraX 가 그릴 곳을 요청하면 인코더의 입력 Surface 를 넘겨 준다. */
    private fun provideEncoderSurface(request: SurfaceRequest) {
        val size = request.resolution
        val w = size.width
        val h = size.height
        try {
            val mux = Fmp4Muxer(w, h, streamRotation)
            // 보는 사람이 없으면 프래그먼트를 만들지 않는다. 만들어 봐야 버려지는데
            // 프레임마다 전체 데이터를 여러 번 복사하게 되어 CPU·GC 를 그냥 태운다.
            var idle = true
            val enc = H264Encoder(
                w, h, prefs.bitrateKbps, prefs.fps, prefs.keyInterval,
                onConfig = { sps, pps, format ->
                    encoderFormat = format
                    mux.setConfig(sps, pps)
                    val init = mux.initSegment
                    val codec = mux.codecString
                    if (init != null && codec != null) {
                        VideoHub.setInit(init, codec, w, h)
                        h264Active = true
                    }
                },
                onSample = { annexB, pts, key ->
                    // 녹화는 인코더 출력을 그대로 쓰므로 시청자와 무관하게 항상 동작한다.
                    recorder?.write(annexB, pts, key)

                    if (VideoHub.viewers.get() > 0) {
                        if (idle) {
                            idle = false
                            mux.reset()          // 새 시청자는 새 MediaSource 라 타임라인을 다시 시작해도 된다
                        }
                        val avcc = H264Nal.toAvcc(annexB)
                        if (avcc.isNotEmpty()) {
                            val frag = mux.offer(avcc, pts, key)
                            if (frag != null) VideoHub.publish(frag.first, frag.second)
                        }
                    } else if (!idle) {
                        idle = true
                        mux.reset()
                        VideoHub.markIdle()
                    }
                }
            )
            streamMuxer = mux
            encoder = enc
            request.provideSurface(enc.inputSurface, cameraExec) {
                enc.release()
                if (encoder === enc) {
                    encoder = null
                    streamMuxer = null
                    h264Active = false
                }
            }
            enc.start()
        } catch (t: Throwable) {
            lastError = "H.264 인코더 초기화 실패: ${t.message}"
            Log.e(TAG, "encoder", t)
            try {
                request.willNotProvideSurface()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun releaseEncoder() {
        val e = encoder
        encoder = null
        streamMuxer = null
        h264Active = false
        encoderFormat = null
        try {
            e?.release()
        } catch (ignored: Throwable) {
        }
    }

    // ------------------------------------------------------------- 프레임 분석

    private fun analyze(image: ImageProxy) {
        try {
            val mjpegOn = StreamHub.viewers.get() > 0
            // MJPEG 를 보는 사람이 없으면 감지에 필요한 만큼만 돌린다.
            val targetFps = if (mjpegOn) prefs.fps else minOf(prefs.fps, 5)
            val now = SystemClock.elapsedRealtime()
            if (now - lastEmitAt < 1000L / targetFps.coerceAtLeast(1)) return
            lastEmitAt = now

            val w = image.width
            val h = image.height

            var motionHit = false
            if (prefs.motionEnabled) {
                val y = image.planes[0]
                motionHit = motion.analyze(
                    y.buffer, y.rowStride, y.pixelStride, w, h, prefs.motionSensitivity
                )
            } else {
                motion.reset()
            }

            var saveEvent = false
            var eventAt = 0L
            if (motionHit) {
                val wall = System.currentTimeMillis()
                if (wall - lastMotionSaveAt >= prefs.motionCooldown * 1000L) {
                    lastMotionSaveAt = wall
                    lastMotionAt = wall
                    motionCount++
                    eventAt = wall
                    saveEvent = prefs.motionSaveShot
                    if (!saveEvent) onMotion?.invoke(wall, null)
                }
            }

            // JPEG 인코딩은 실제로 필요할 때만 한다(구형 폰의 CPU·발열 절약).
            val wantJpeg = mjpegOn || snapshotWanted.getAndSet(false) || saveEvent
            if (!wantJpeg) return

            val nv21 = Yuv.toNv21(image)
            val degrees = ((image.imageInfo.rotationDegrees + prefs.rotation) % 360 + 360) % 360
            var data = nv21
            var outW = w
            var outH = h
            if (degrees != 0) {
                data = Yuv.rotateNv21(data, outW, outH, degrees)
                if (degrees == 90 || degrees == 270) {
                    val t = outW; outW = outH; outH = t
                }
            }
            if (prefs.mirror) {
                data = Yuv.mirrorNv21(data, outW, outH)
            }

            val jpeg = Yuv.nv21ToJpeg(data, outW, outH, prefs.quality)
            frameWidth = outW
            frameHeight = outH
            StreamHub.publish(jpeg)

            if (saveEvent) {
                val saved = try {
                    val f = storage.saveEvent(jpeg)
                    storage.prune("events", prefs.maxEvents)
                    f
                } catch (t: Throwable) {
                    lastError = "이벤트 저장 실패: ${t.message}"
                    null
                }
                onMotion?.invoke(eventAt, saved)
            }
        } catch (t: Throwable) {
            lastError = "프레임 처리 오류: ${t.message}"
            Log.e(TAG, "analyze", t)
        } finally {
            image.close()
        }
    }

    // ------------------------------------------------------------------ 제어

    fun setTorch(on: Boolean): String? {
        val c = camera ?: return "카메라가 준비되지 않았습니다"
        if (!torchAvailable) return "이 카메라에는 플래시가 없습니다"
        return try {
            c.cameraControl.enableTorch(on)
            torchOn = on
            null
        } catch (t: Throwable) {
            "플래시 제어 실패: ${t.message}"
        }
    }

    fun setZoom(linear: Float): String? {
        val c = camera ?: return "카메라가 준비되지 않았습니다"
        return try {
            c.cameraControl.setLinearZoom(linear.coerceIn(0f, 1f))
            null
        } catch (t: Throwable) {
            "줌 제어 실패: ${t.message}"
        }
    }

    /** 비트레이트만 바꾼다(재바인딩 없이 즉시 적용). */
    fun applyBitrate() {
        encoder?.setBitrate(prefs.bitrateKbps)
    }

    @SuppressLint("MissingPermission")
    fun startRecording(): String? {
        if (recorder != null) return "이미 녹화 중입니다"
        if (!prefs.h264Enabled) return "H.264 스트림이 꺼져 있어 녹화할 수 없습니다"
        val fmt = encoderFormat ?: return "인코더가 준비 중입니다. 몇 초 후 다시 시도하세요."
        val file = storage.newVideoFile()
        val r = Mp4Recorder(file, fmt, streamRotation)
        if (!r.open()) return "녹화 파일을 만들 수 없습니다"
        recordingFile = file
        recordingStartedAt = System.currentTimeMillis()
        recorder = r
        isRecording = true
        encoder?.requestKeyFrame()      // 첫 키프레임부터 담기도록
        return null
    }

    fun stopRecording(): String? {
        val r = recorder ?: return "녹화 중이 아닙니다"
        recorder = null
        isRecording = false
        val saved = r.close()
        if (saved == null) {
            lastError = "녹화된 프레임이 없어 파일을 저장하지 않았습니다"
        } else {
            storage.prune("videos", prefs.maxEvents)
        }
        return null
    }

    fun clearError() {
        lastError = null
    }
}
