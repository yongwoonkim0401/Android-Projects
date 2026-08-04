package com.weighttracker.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "weight_entries")
data class WeightEntry(
    @PrimaryKey
    val date: String,       // "yyyy-MM-dd"
    val weight: Float,
    val note: String = ""
)
