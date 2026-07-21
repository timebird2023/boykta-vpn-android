package com.boykta.vpn.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [LocalServer::class], version = 1, exportSchema = false)
abstract class LocalDatabase : RoomDatabase() {

    abstract fun localServerDao(): LocalServerDao

    companion object {
        @Volatile private var INSTANCE: LocalDatabase? = null

        fun get(context: Context): LocalDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    LocalDatabase::class.java,
                    "boykta_local.db"
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}
