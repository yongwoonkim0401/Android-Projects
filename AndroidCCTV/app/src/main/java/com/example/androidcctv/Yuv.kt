package com.example.androidcctv

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * CameraX 의 YUV_420_888 프레임을 NV21 로 바꾸고, 회전/좌우반전 후 JPEG 로 인코딩한다.
 * 구형 기기에서도 확실히 동작하도록 Bitmap 을 거치지 않고 바이트 버퍼만 다룬다.
 */
object Yuv {

    /** ImageProxy(YUV_420_888) -> NV21(Y + 인터리브된 VU) */
    fun toNv21(image: ImageProxy): ByteArray {
        val w = image.width
        val h = image.height
        val ySize = w * h
        val out = ByteArray(ySize + ySize / 2)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride

        var pos = 0
        for (row in 0 until h) {
            var idx = row * yRowStride
            if (yPixStride == 1) {
                yBuf.position(idx)
                yBuf.get(out, pos, w)
                pos += w
            } else {
                for (col in 0 until w) {
                    out[pos++] = yBuf.get(idx)
                    idx += yPixStride
                }
            }
        }

        val cw = w / 2
        val ch = h / 2
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uRow = uPlane.rowStride
        val uPix = uPlane.pixelStride
        val vRow = vPlane.rowStride
        val vPix = vPlane.pixelStride

        var o = ySize
        for (row in 0 until ch) {
            var uIdx = row * uRow
            var vIdx = row * vRow
            for (col in 0 until cw) {
                out[o++] = vBuf.get(vIdx)   // NV21 은 V 가 먼저
                out[o++] = uBuf.get(uIdx)
                uIdx += uPix
                vIdx += vPix
            }
        }
        return out
    }

    /** 시계 방향 회전(90/180/270). 0 이면 원본을 그대로 돌려준다. */
    fun rotateNv21(src: ByteArray, w: Int, h: Int, degrees: Int): ByteArray {
        if (degrees == 0) return src
        val ySize = w * h
        val out = ByteArray(src.size)
        val cw = w / 2
        val ch = h / 2

        when (degrees) {
            90 -> {
                // 새 크기: h x w
                for (y in 0 until h) {
                    val rowBase = y * w
                    for (x in 0 until w) {
                        out[x * h + (h - 1 - y)] = src[rowBase + x]
                    }
                }
                for (j in 0 until ch) {
                    for (i in 0 until cw) {
                        val s = ySize + j * w + i * 2
                        val d = ySize + i * (ch * 2) + (ch - 1 - j) * 2
                        out[d] = src[s]
                        out[d + 1] = src[s + 1]
                    }
                }
            }
            180 -> {
                for (y in 0 until h) {
                    val rowBase = y * w
                    val dstBase = (h - 1 - y) * w
                    for (x in 0 until w) {
                        out[dstBase + (w - 1 - x)] = src[rowBase + x]
                    }
                }
                for (j in 0 until ch) {
                    for (i in 0 until cw) {
                        val s = ySize + j * w + i * 2
                        val d = ySize + (ch - 1 - j) * w + (cw - 1 - i) * 2
                        out[d] = src[s]
                        out[d + 1] = src[s + 1]
                    }
                }
            }
            270 -> {
                for (y in 0 until h) {
                    val rowBase = y * w
                    for (x in 0 until w) {
                        out[(w - 1 - x) * h + y] = src[rowBase + x]
                    }
                }
                for (j in 0 until ch) {
                    for (i in 0 until cw) {
                        val s = ySize + j * w + i * 2
                        val d = ySize + (cw - 1 - i) * (ch * 2) + j * 2
                        out[d] = src[s]
                        out[d + 1] = src[s + 1]
                    }
                }
            }
            else -> return src
        }
        return out
    }

    /** 좌우 반전 */
    fun mirrorNv21(src: ByteArray, w: Int, h: Int): ByteArray {
        val ySize = w * h
        val out = ByteArray(src.size)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                out[base + (w - 1 - x)] = src[base + x]
            }
        }
        val cw = w / 2
        val ch = h / 2
        for (j in 0 until ch) {
            for (i in 0 until cw) {
                val s = ySize + j * w + i * 2
                val d = ySize + j * w + (cw - 1 - i) * 2
                out[d] = src[s]
                out[d + 1] = src[s + 1]
            }
        }
        return out
    }

    fun nv21ToJpeg(nv21: ByteArray, w: Int, h: Int, quality: Int): ByteArray {
        val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        val bos = ByteArrayOutputStream(w * h / 4)
        yuv.compressToJpeg(Rect(0, 0, w, h), quality, bos)
        return bos.toByteArray()
    }
}
