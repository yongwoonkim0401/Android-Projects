package com.example.androidcctv

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 의존성 없는 경량 HTTP 서버.
 *  - GET  /                       제어 패널(웹 UI)
 *  - GET  /stream.mp4             H.264 fragmented MP4 스트림(브라우저 MSE 재생)
 *  - GET  /stream.mjpeg           MJPEG 실시간 스트림(호환용)
 *  - GET  /snapshot.jpg           현재 프레임 1장
 *  - GET  /api/status             상태 JSON
 *  - POST /api/config             설정 변경 JSON
 *  - POST /api/action/<이름>      동작 실행(torch/lens/record 등)
 *  - GET  /api/media/<종류>       파일 목록
 *  - GET  /media/<종류>/<파일명>  파일 다운로드(Range 지원)
 */
class HttpServer(private val ctx: Context, private val bridge: Bridge) {

    companion object {
        private const val TAG = "CctvHttp"
        private const val BOUNDARY = "cctvframe"
        private const val STREAM_IDLE_TIMEOUT_MS = 30_000L

        /** 이 시간 동안 한 바이트도 못 내보낸 시청자는 죽은 것으로 보고 끊는다. */
        private const val STALL_TIMEOUT_MS = 15_000L
        private const val WATCHDOG_PERIOD_MS = 5_000L
    }

    /**
     * 진행 중인 스트리밍 연결.
     *
     * 자바 소켓에는 쓰기 타임아웃이 없어서, 상대가 FIN 없이 사라지면(모바일·VPN 에서 흔하다)
     * write() 가 커널 버퍼가 찰 때까지 성공하다가 그대로 멈춰 버린다. 그동안 시청자 수가
     * 줄지 않아 카메라·인코딩이 계속 전속력으로 돌게 되므로, 감시 스레드가 소켓을 닫아
     * 막힌 스레드를 깨운다.
     */
    private class Live(val socket: Socket) {
        @Volatile var progressAt = System.currentTimeMillis()
    }

    private val lives = CopyOnWriteArrayList<Live>()

    interface Bridge {
        fun token(): String
        fun status(): JSONObject
        fun applyConfig(body: JSONObject): JSONObject
        fun action(name: String, params: Map<String, String>): JSONObject
        fun storage(): Storage

        /** MJPEG 시청자가 없을 때도 JPEG 한 장을 만들도록 요청 */
        fun requestFrame()
    }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var pool: ExecutorService? = null

    @Volatile var running = false; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var port = 0; private set

    /** MJPEG + H.264 시청자 합계 */
    val viewers: Int get() = StreamHub.viewers.get() + VideoHub.viewers.get()

    fun start(desiredPort: Int): Boolean {
        stop()
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(desiredPort))
            serverSocket = ss
            port = desiredPort
            running = true
            lastError = null
            pool = Executors.newCachedThreadPool()
            Thread({ acceptLoop(ss) }, "cctv-accept").start()
            Thread({ watchdogLoop() }, "cctv-watchdog").start()
            true
        } catch (t: Throwable) {
            lastError = "포트 $desiredPort 열기 실패: ${t.message}"
            running = false
            false
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {
        }
        serverSocket = null
        for (l in lives) closeQuietly(l.socket)
        lives.clear()
        pool?.shutdownNow()
        pool = null
    }

    private fun closeQuietly(s: Socket) {
        try {
            s.close()
        } catch (ignored: Exception) {
        }
    }

    private fun watchdogLoop() {
        while (running) {
            try {
                Thread.sleep(WATCHDOG_PERIOD_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            val now = System.currentTimeMillis()
            for (l in lives) {
                if (now - l.progressAt > STALL_TIMEOUT_MS) {
                    Log.w(TAG, "응답 없는 시청자 연결을 끊습니다: ${l.socket.inetAddress}")
                    lives.remove(l)
                    closeQuietly(l.socket)   // 막혀 있는 write() 가 예외로 풀린다
                }
            }
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            try {
                val socket = ss.accept()
                pool?.execute { handle(socket) }
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "accept", t)
                break
            }
        }
    }

    // ---------------------------------------------------------------- 요청 처리

    private fun handle(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 20_000
            val input = BufferedInputStream(socket.getInputStream(), 8192)
            val output = BufferedOutputStream(socket.getOutputStream(), 32768)

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                sendText(output, 400, "Bad Request"); output.flush(); return
            }
            val method = parts[0].uppercase()
            val rawPath = parts[1]

            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) {
                    headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
            }

            var body = ""
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            if (len in 1..1_000_000) {
                val buf = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = input.read(buf, read, len - read)
                    if (n < 0) break
                    read += n
                }
                body = String(buf, 0, read, Charsets.UTF_8)
            }

            val q = rawPath.indexOf('?')
            val path = percentDecode(if (q >= 0) rawPath.substring(0, q) else rawPath)
            val params = parseQuery(if (q >= 0) rawPath.substring(q + 1) else "")

            route(socket, output, method, path, params, headers, body)
            output.flush()
        } catch (t: Throwable) {
            // 클라이언트가 먼저 끊는 것은 정상 상황이므로 조용히 넘어간다.
        } finally {
            try {
                socket.close()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun route(
        socket: Socket,
        out: OutputStream,
        method: String,
        path: String,
        params: Map<String, String>,
        headers: Map<String, String>,
        body: String
    ) {
        if (path == "/api/ping") {
            sendJson(out, 200, JSONObject().put("ok", true).put("authRequired", bridge.token().isNotEmpty()))
            return
        }
        if (path == "/" || path == "/index.html") {
            sendAsset(out, "web/index.html", "text/html; charset=utf-8")
            return
        }
        if (path == "/favicon.ico") {
            sendHeaderOnly(out, 204, emptyMap())
            return
        }

        if (!authorized(params, headers)) {
            sendJson(out, 401, JSONObject().put("error", "인증 토큰이 필요합니다"))
            return
        }

        when {
            path == "/stream.mjpeg" -> streamMjpeg(socket, out)

            path == "/stream.mp4" -> streamFmp4(socket, out)

            path == "/snapshot.jpg" -> {
                val current = StreamHub.latest()
                val age = StreamHub.lastFrameAgeMs()
                val f = if (current != null && age in 0..1500) {
                    current
                } else {
                    // MJPEG 시청자가 없으면 JPEG 를 만들지 않으므로 한 장을 요청해서 기다린다.
                    bridge.requestFrame()
                    StreamHub.waitForNext(current?.seq ?: -1L, 3000) ?: current
                }
                if (f == null) {
                    sendJson(out, 503, JSONObject().put("error", "아직 프레임이 없습니다"))
                } else {
                    sendBytes(out, 200, "image/jpeg", f.data, mapOf("Cache-Control" to "no-store"))
                }
            }

            path == "/api/status" -> sendJson(out, 200, bridge.status())

            path == "/api/config" -> {
                if (method != "POST") {
                    sendJson(out, 405, JSONObject().put("error", "POST 만 지원합니다")); return
                }
                val json = try {
                    if (body.isBlank()) JSONObject() else JSONObject(body)
                } catch (t: Throwable) {
                    sendJson(out, 400, JSONObject().put("error", "JSON 형식 오류")); return
                }
                sendJson(out, 200, bridge.applyConfig(json))
            }

            path.startsWith("/api/action/") -> {
                val name = path.substring("/api/action/".length)
                val merged = HashMap<String, String>(params)
                if (body.isNotBlank()) {
                    try {
                        val json = JSONObject(body)
                        json.keys().forEach { k -> merged[k] = json.get(k).toString() }
                    } catch (ignored: Throwable) {
                    }
                }
                sendJson(out, 200, bridge.action(name, merged))
            }

            path.startsWith("/api/media/") -> handleMediaApi(out, path, params)

            path.startsWith("/media/") -> {
                val seg = path.substring("/media/".length).split("/")
                if (seg.size < 2) {
                    sendJson(out, 404, JSONObject().put("error", "경로 오류")); return
                }
                val file = bridge.storage().resolve(seg[0], seg[1])
                if (file == null) {
                    sendJson(out, 404, JSONObject().put("error", "파일 없음")); return
                }
                val type = if (file.name.endsWith(".mp4")) "video/mp4" else "image/jpeg"
                sendFile(out, file.absolutePath, file.length(), type, headers["range"])
            }

            else -> sendJson(out, 404, JSONObject().put("error", "알 수 없는 경로: $path"))
        }
    }

    private fun handleMediaApi(out: OutputStream, path: String, params: Map<String, String>) {
        val seg = path.substring("/api/media/".length).split("/")
        val type = seg.getOrNull(0) ?: ""
        val storage = bridge.storage()
        if (storage.dirFor(type) == null) {
            sendJson(out, 404, JSONObject().put("error", "알 수 없는 종류: $type")); return
        }
        when (seg.getOrNull(1)) {
            null, "" -> {
                val limit = params["limit"]?.toIntOrNull() ?: 100
                sendJson(
                    out, 200,
                    JSONObject().put("type", type).put("items", storage.list(type, limit.coerceIn(1, 2000)))
                )
            }
            "delete" -> {
                val name = params["name"] ?: ""
                if (storage.resolve(type, name) == null) {
                    sendJson(out, 404, JSONObject().put("error", "파일 없음"))
                } else {
                    sendJson(out, 200, JSONObject().put("ok", storage.delete(type, name)))
                }
            }
            "clear" -> sendJson(out, 200, JSONObject().put("ok", true).put("deleted", storage.clear(type)))
            else -> sendJson(out, 404, JSONObject().put("error", "알 수 없는 명령"))
        }
    }

    // ---------------------------------------------------------------- 인증

    private fun authorized(params: Map<String, String>, headers: Map<String, String>): Boolean {
        val token = bridge.token()
        if (token.isEmpty()) return true
        if (params["token"] == token) return true
        if (headers["x-auth-token"] == token) return true
        val auth = headers["authorization"] ?: return false
        if (!auth.startsWith("Basic ", ignoreCase = true)) return false
        return try {
            val decoded = String(Base64.decode(auth.substring(6).trim(), Base64.DEFAULT), Charsets.UTF_8)
            val idx = decoded.indexOf(':')
            val user = if (idx >= 0) decoded.substring(0, idx) else decoded
            val pass = if (idx >= 0) decoded.substring(idx + 1) else ""
            user == token || pass == token
        } catch (t: Throwable) {
            false
        }
    }

    // ---------------------------------------------------------------- MJPEG

    private fun streamMjpeg(socket: Socket, out: OutputStream) {
        socket.soTimeout = 0
        val head = StringBuilder()
            .append("HTTP/1.0 200 OK\r\n")
            .append("Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n")
            .append("Cache-Control: no-store, no-cache, must-revalidate\r\n")
            .append("Pragma: no-cache\r\n")
            .append("Connection: close\r\n")
            .append("Access-Control-Allow-Origin: *\r\n")
            .append("\r\n")
            .toString()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.flush()

        val live = Live(socket)
        lives.add(live)
        StreamHub.viewers.incrementAndGet()
        try {
            var seq = -1L
            var idle = 0L
            while (running && !socket.isClosed) {
                val frame = StreamHub.waitForNext(seq, 2000)
                if (frame == null) {
                    idle += 2000
                    live.progressAt = System.currentTimeMillis()   // 보낼 게 없는 것은 시청자 잘못이 아니다
                    if (idle >= STREAM_IDLE_TIMEOUT_MS) break
                    continue
                }
                idle = 0
                seq = frame.seq
                val partHeader = "--$BOUNDARY\r\nContent-Type: image/jpeg\r\n" +
                    "Content-Length: ${frame.data.size}\r\n\r\n"
                out.write(partHeader.toByteArray(Charsets.US_ASCII))
                out.write(frame.data)
                out.write("\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
                live.progressAt = System.currentTimeMillis()
            }
        } catch (t: Throwable) {
            // 시청자가 창을 닫거나 감시 스레드가 소켓을 닫으면 여기로 온다.
        } finally {
            lives.remove(live)
            StreamHub.viewers.decrementAndGet()
        }
    }

    // ---------------------------------------------------------------- H.264

    /**
     * fragmented MP4 를 끊지 않고 계속 흘려보낸다.
     * HTTP/1.0 + Connection: close 이므로 Content-Length 없이 EOF 까지 읽는 형태가 되고,
     * 브라우저는 fetch() 의 ReadableStream 으로 받아 MSE 에 그대로 넣는다.
     */
    private fun streamFmp4(socket: Socket, out: OutputStream) {
        // 인코더가 SPS/PPS 를 내놓기까지 잠깐 기다린다.
        var waited = 0
        while (running && VideoHub.initSegment == null && waited < 6000) {
            try {
                Thread.sleep(200)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            waited += 200
        }
        val init = VideoHub.initSegment
        if (init == null) {
            sendJson(
                out, 503,
                JSONObject().put("error", "H.264 스트림이 준비되지 않았습니다(설정에서 H.264 사용 확인)")
            )
            return
        }

        socket.soTimeout = 0
        val head = StringBuilder()
            .append("HTTP/1.0 200 OK\r\n")
            .append("Content-Type: video/mp4\r\n")
            .append("Cache-Control: no-store, no-cache, must-revalidate\r\n")
            .append("Pragma: no-cache\r\n")
            .append("Connection: close\r\n")
            .append("Access-Control-Allow-Origin: *\r\n")
            .append("\r\n")
            .toString()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(init)
        out.flush()

        val live = Live(socket)
        lives.add(live)
        val sub = VideoHub.subscribe()
        try {
            var idle = 0L
            while (running && !socket.isClosed) {
                val frag = sub.poll(2000)
                if (frag == null) {
                    idle += 2000
                    live.progressAt = System.currentTimeMillis()
                    if (idle >= STREAM_IDLE_TIMEOUT_MS) break
                    continue
                }
                idle = 0
                out.write(frag.data)
                out.flush()
                live.progressAt = System.currentTimeMillis()
            }
        } catch (t: Throwable) {
            // 시청자가 창을 닫거나 감시 스레드가 소켓을 닫으면 여기로 온다.
        } finally {
            lives.remove(live)
            VideoHub.unsubscribe(sub)
        }
    }

    // ---------------------------------------------------------------- 응답 유틸

    private fun statusText(code: Int) = when (code) {
        200 -> "OK"; 204 -> "No Content"; 206 -> "Partial Content"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
        405 -> "Method Not Allowed"; 500 -> "Internal Server Error"; 503 -> "Service Unavailable"
        else -> "OK"
    }

    private fun sendHeaderOnly(out: OutputStream, code: Int, extra: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $code ${statusText(code)}\r\n")
        sb.append("Connection: close\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        extra.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun sendBytes(
        out: OutputStream,
        code: Int,
        contentType: String,
        data: ByteArray,
        extra: Map<String, String> = emptyMap()
    ) {
        val headers = HashMap<String, String>(extra)
        headers["Content-Type"] = contentType
        headers["Content-Length"] = data.size.toString()
        sendHeaderOnly(out, code, headers)
        out.write(data)
    }

    private fun sendText(out: OutputStream, code: Int, text: String) =
        sendBytes(out, code, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))

    private fun sendJson(out: OutputStream, code: Int, json: JSONObject) =
        sendBytes(
            out, code, "application/json; charset=utf-8",
            json.toString().toByteArray(Charsets.UTF_8),
            mapOf("Cache-Control" to "no-store")
        )

    private fun sendAsset(out: OutputStream, name: String, contentType: String) {
        try {
            val data = ctx.assets.open(name).use { readAll(it) }
            sendBytes(out, 200, contentType, data)
        } catch (t: Throwable) {
            sendText(out, 500, "asset 오류: ${t.message}")
        }
    }

    /** Range 헤더를 지원하는 파일 전송(브라우저에서 mp4 재생·탐색 가능). */
    private fun sendFile(
        out: OutputStream,
        absolutePath: String,
        length: Long,
        contentType: String,
        rangeHeader: String?
    ) {
        var start = 0L
        var end = length - 1
        var partial = false
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.substring(6).split("-")
            val s = spec.getOrNull(0)?.trim()?.toLongOrNull()
            val e = spec.getOrNull(1)?.trim()?.toLongOrNull()
            if (s != null) {
                start = s
                if (e != null) end = minOf(e, length - 1)
                partial = true
            }
        }
        if (start >= length) start = 0
        val count = end - start + 1

        val headers = HashMap<String, String>()
        headers["Content-Type"] = contentType
        headers["Content-Length"] = count.toString()
        headers["Accept-Ranges"] = "bytes"
        // 저장된 파일은 이름에 촬영 시각이 들어가 있어 내용이 바뀌지 않는다.
        // 캐시를 허용하지 않으면 목록을 볼 때마다 썸네일 수십 장을 다시 내려받게 된다.
        headers["Cache-Control"] = "private, max-age=604800, immutable"
        if (partial) headers["Content-Range"] = "bytes $start-$end/$length"
        sendHeaderOnly(out, if (partial) 206 else 200, headers)

        FileInputStream(absolutePath).use { fis ->
            var skipped = 0L
            while (skipped < start) {
                val n = fis.skip(start - skipped)
                if (n <= 0) break
                skipped += n
            }
            val buf = ByteArray(64 * 1024)
            var remain = count
            while (remain > 0) {
                val n = fis.read(buf, 0, minOf(buf.size.toLong(), remain).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                remain -= n
            }
        }
    }

    // ---------------------------------------------------------------- 파싱 유틸

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) {
                val s = sb.toString()
                return if (s.endsWith("\r")) s.substring(0, s.length - 1) else s
            }
            sb.append(c.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun readAll(input: InputStream): ByteArray {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            bos.write(buf, 0, n)
        }
        return bos.toByteArray()
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in query.split("&")) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            val k = if (i >= 0) pair.substring(0, i) else pair
            val v = if (i >= 0) pair.substring(i + 1) else ""
            map[decode(k)] = decode(v)
        }
        return map
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (t: Throwable) {
        s
    }

    /** 경로용 퍼센트 디코딩('+' 는 그대로 둔다) */
    private fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        return try {
            URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (t: Throwable) {
            s
        }
    }
}
