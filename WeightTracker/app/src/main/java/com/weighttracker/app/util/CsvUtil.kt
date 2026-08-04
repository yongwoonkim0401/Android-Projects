package com.weighttracker.app.util

import android.content.Context
import android.net.Uri
import com.weighttracker.app.data.model.WeightEntry
import java.io.BufferedReader
import java.io.InputStreamReader

object CsvUtil {

    /**
     * CSV 파일을 파싱하여 WeightEntry 리스트로 반환.
     * 지원 형식:
     *   date,weight[,note]
     *   date는 yyyy-MM-dd, yyyy/MM/dd, MM/dd/yyyy, dd-MM-yyyy 등 자동 감지
     */
    fun parseCsv(context: Context, uri: Uri): List<WeightEntry> {
        val entries = mutableListOf<WeightEntry>()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream)).use { reader ->
                var isFirst = true
                reader.forEachLine { line ->
                    if (isFirst) {
                        isFirst = false
                        // 헤더 행이면 건너뜀 (숫자가 아닌 경우)
                        val cols = line.split(",")
                        if (cols.isNotEmpty() && !cols[0].any { it.isDigit() }) return@forEachLine
                    }
                    parseRow(line)?.let { entries.add(it) }
                }
            }
        }
        return entries
    }

    private fun parseRow(line: String): WeightEntry? {
        val cols = line.trim().split(",")
        if (cols.size < 2) return null
        val rawDate = cols[0].trim().removeSurrounding("\"")
        val rawWeight = cols[1].trim().removeSurrounding("\"")
        val note = if (cols.size > 2) cols[2].trim().removeSurrounding("\"") else ""

        val date = normalizeDate(rawDate) ?: return null
        val weight = rawWeight.toFloatOrNull() ?: return null
        return WeightEntry(date = date, weight = weight, note = note)
    }

    private fun normalizeDate(raw: String): String? {
        // yyyy-MM-dd
        if (raw.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return raw
        // yyyy/MM/dd
        if (raw.matches(Regex("\\d{4}/\\d{2}/\\d{2}"))) return raw.replace("/", "-")
        // MM/dd/yyyy
        val mdy = Regex("(\\d{1,2})/(\\d{1,2})/(\\d{4})").matchEntire(raw)
        if (mdy != null) {
            val (m, d, y) = mdy.destructured
            return "$y-${m.padStart(2,'0')}-${d.padStart(2,'0')}"
        }
        // dd-MM-yyyy
        val dmy = Regex("(\\d{1,2})-(\\d{1,2})-(\\d{4})").matchEntire(raw)
        if (dmy != null) {
            val (d, m, y) = dmy.destructured
            return "$y-${m.padStart(2,'0')}-${d.padStart(2,'0')}"
        }
        return null
    }

    fun buildCsvContent(entries: List<WeightEntry>): String {
        val sb = StringBuilder()
        sb.appendLine("date,weight,note")
        entries.sortedBy { it.date }.forEach { e ->
            sb.appendLine("${e.date},${e.weight},${e.note}")
        }
        return sb.toString()
    }
}
