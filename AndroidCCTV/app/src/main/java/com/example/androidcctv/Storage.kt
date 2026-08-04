package com.example.androidcctv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 앱 전용 외부 저장소(Android/data/.../files) 아래에 스냅샷·이벤트·녹화 파일을 관리한다.
 * 별도의 저장소 권한이 필요 없고, 앱을 지우면 함께 정리된다.
 */
class Storage(ctx: Context) {

    private val root: File =
        (ctx.getExternalFilesDir(null) ?: ctx.filesDir).let { File(it, "cctv") }

    val events = File(root, "events").apply { mkdirs() }
    val snapshots = File(root, "snapshots").apply { mkdirs() }
    val videos = File(root, "videos").apply { mkdirs() }

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    /**
     * 상태 조회(/api/status)는 2초마다 불릴 수 있는데 listFiles()·freeSpace 는
     * 파일이 수백 개면 무시할 수 없는 비용이라 잠깐 캐시한다.
     */
    private val cacheTtlMs = 15_000L
    private val counts = HashMap<String, Int>()
    private val countAt = HashMap<String, Long>()
    private var freeCache = -1L
    private var freeAt = 0L

    private fun now() = stamp.format(Date())

    fun saveEvent(jpeg: ByteArray): File =
        write(File(events, "evt_${now()}.jpg"), jpeg).also { invalidate("events") }

    fun saveSnapshot(jpeg: ByteArray): File =
        write(File(snapshots, "snap_${now()}.jpg"), jpeg).also { invalidate("snapshots") }

    fun newVideoFile(): File {
        invalidate("videos")
        return File(videos, "rec_${now()}.mp4")
    }

    @Synchronized
    private fun invalidate(type: String) {
        countAt.remove(type)
        freeAt = 0L
    }

    private fun write(f: File, data: ByteArray): File {
        FileOutputStream(f).use { it.write(data) }
        return f
    }

    fun dirFor(type: String): File? = when (type) {
        "events" -> events
        "snapshots" -> snapshots
        "videos" -> videos
        else -> null
    }

    /** 이름만으로 파일을 찾는다. 경로 탈출(../) 은 허용하지 않는다. */
    fun resolve(type: String, name: String): File? {
        val dir = dirFor(type) ?: return null
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        val f = File(dir, name)
        return if (f.exists() && f.parentFile == dir) f else null
    }

    fun list(type: String, limit: Int): JSONArray {
        val dir = dirFor(type) ?: return JSONArray()
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        val arr = JSONArray()
        for (f in files.take(limit)) {
            arr.put(
                JSONObject()
                    .put("name", f.name)
                    .put("size", f.length())
                    .put("at", f.lastModified())
                    .put("url", "/media/$type/${f.name}")
            )
        }
        return arr
    }

    @Synchronized
    fun count(type: String): Int {
        val at = countAt[type] ?: 0L
        val cached = counts[type]
        if (cached != null && System.currentTimeMillis() - at < cacheTtlMs) return cached
        val n = dirFor(type)?.listFiles()?.size ?: 0
        counts[type] = n
        countAt[type] = System.currentTimeMillis()
        return n
    }

    fun clear(type: String): Int {
        val dir = dirFor(type) ?: return 0
        var n = 0
        dir.listFiles()?.forEach { if (it.delete()) n++ }
        invalidate(type)
        return n
    }

    /** 파일 1개 삭제(경로 탈출 방지 포함) */
    fun delete(type: String, name: String): Boolean {
        val f = resolve(type, name) ?: return false
        val ok = f.delete()
        if (ok) invalidate(type)
        return ok
    }

    /** 오래된 파일부터 지워 개수를 max 이하로 유지한다. */
    fun prune(type: String, max: Int) {
        val dir = dirFor(type) ?: return
        val files = dir.listFiles() ?: return
        if (files.size <= max) return
        files.sortedBy { it.lastModified() }
            .take(files.size - max)
            .forEach { it.delete() }
        invalidate(type)
    }

    @Synchronized
    fun freeBytes(): Long {
        val now = System.currentTimeMillis()
        if (freeAt != 0L && now - freeAt < cacheTtlMs) return freeCache
        freeCache = try {
            root.freeSpace
        } catch (e: Exception) {
            -1L
        }
        freeAt = now
        return freeCache
    }
}
