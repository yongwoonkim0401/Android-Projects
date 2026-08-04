package com.weighttracker.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.weighttracker.app.data.model.WeightEntry

@Database(entities = [WeightEntry::class], version = 1, exportSchema = false)
abstract class WeightDatabase : RoomDatabase() {
    abstract fun weightDao(): WeightDao

    companion object {
        @Volatile private var INSTANCE: WeightDatabase? = null

        fun getDatabase(context: Context): WeightDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, WeightDatabase::class.java, "weight_db")
                    .build().also { INSTANCE = it }
            }
    }
}
