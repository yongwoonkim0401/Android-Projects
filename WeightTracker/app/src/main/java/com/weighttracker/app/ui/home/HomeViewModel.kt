package com.weighttracker.app.ui.home

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.weighttracker.app.data.db.WeightDatabase
import com.weighttracker.app.data.model.WeightEntry
import com.weighttracker.app.data.repository.WeightRepository
import com.weighttracker.app.util.CsvUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = WeightRepository(WeightDatabase.getDatabase(app).weightDao())
    val allEntries = repo.allEntries

    private val prefs = app.getSharedPreferences("weight_prefs", Context.MODE_PRIVATE)
    private val _goalWeight = MutableLiveData<Float?>(
        prefs.getFloat("goal_weight", -1f).takeIf { it > 0 }
    )
    val goalWeight: LiveData<Float?> = _goalWeight

    fun setGoalWeight(weight: Float?) {
        if (weight != null && weight > 0) {
            prefs.edit().putFloat("goal_weight", weight).apply()
            _goalWeight.value = weight
        } else {
            prefs.edit().remove("goal_weight").apply()
            _goalWeight.value = null
        }
    }

    fun saveWeight(dateStr: String, weight: Float, note: String = "") {
        viewModelScope.launch {
            repo.insert(WeightEntry(date = dateStr, weight = weight, note = note))
        }
    }

    fun deleteEntry(entry: WeightEntry) {
        viewModelScope.launch { repo.delete(entry) }
    }

    fun importCsv(context: Context, uri: Uri, onResult: (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val entries = CsvUtil.parseCsv(context, uri)
            repo.insertAll(entries)
            withContext(Dispatchers.Main) { onResult(entries.size) }
        }
    }

    fun exportCsv(context: Context, onResult: (File?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val entries = repo.getAllSync()
            val csv = CsvUtil.buildCsvContent(entries)
            val file = try {
                val dir = File(context.getExternalFilesDir(null), "exports")
                dir.mkdirs()
                val name = "weight_${LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))}.csv"
                File(dir, name).apply { writeText(csv) }
            } catch (e: Exception) { null }
            withContext(Dispatchers.Main) { onResult(file) }
        }
    }
}
