package com.weighttracker.app.data.db

import androidx.lifecycle.LiveData
import androidx.room.*
import com.weighttracker.app.data.model.WeightEntry

@Dao
interface WeightDao {
    @Query("SELECT * FROM weight_entries ORDER BY date DESC")
    fun getAllEntries(): LiveData<List<WeightEntry>>

    @Query("SELECT * FROM weight_entries ORDER BY date ASC")
    suspend fun getAllEntriesSync(): List<WeightEntry>

    @Query("SELECT * FROM weight_entries WHERE date >= :startDate ORDER BY date ASC")
    suspend fun getEntriesFrom(startDate: String): List<WeightEntry>

    @Query("SELECT * FROM weight_entries WHERE date = :date LIMIT 1")
    suspend fun getEntryByDate(date: String): WeightEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: WeightEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<WeightEntry>)

    @Delete
    suspend fun delete(entry: WeightEntry)
}
