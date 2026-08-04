package com.weighttracker.app.data.repository

import androidx.lifecycle.LiveData
import com.weighttracker.app.data.db.WeightDao
import com.weighttracker.app.data.model.WeightEntry

class WeightRepository(private val dao: WeightDao) {
    val allEntries: LiveData<List<WeightEntry>> = dao.getAllEntries()

    suspend fun insert(entry: WeightEntry) = dao.insert(entry)
    suspend fun insertAll(entries: List<WeightEntry>) = dao.insertAll(entries)
    suspend fun delete(entry: WeightEntry) = dao.delete(entry)
    suspend fun getAllSync(): List<WeightEntry> = dao.getAllEntriesSync()
    suspend fun getEntriesFrom(date: String): List<WeightEntry> = dao.getEntriesFrom(date)
    suspend fun getByDate(date: String): WeightEntry? = dao.getEntryByDate(date)
}
