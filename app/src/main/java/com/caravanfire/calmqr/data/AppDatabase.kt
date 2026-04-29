package com.caravanfire.calmqr.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SavedCode::class], version = 3)
abstract class AppDatabase : RoomDatabase() {

    abstract fun savedCodeDao(): SavedCodeDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Adds the nullable createdAt column. Pre-existing rows get NULL,
        // which the info page treats as "unknown" and hides the row.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_codes ADD COLUMN createdAt INTEGER")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "calm_qr.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
