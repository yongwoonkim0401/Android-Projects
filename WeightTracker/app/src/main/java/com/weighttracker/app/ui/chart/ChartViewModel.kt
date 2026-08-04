package com.weighttracker.app.ui.chart

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.weighttracker.app.data.db.WeightDatabase
import com.weighttracker.app.data.repository.WeightRepository

class ChartViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = WeightRepository(WeightDatabase.getDatabase(app).weightDao())
    val allEntries = repo.allEntries
}
