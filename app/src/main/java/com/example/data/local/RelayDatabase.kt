package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NumberEntity::class,
        MessageEntity::class,
        TempNumberEntity::class,
        CustomSourceEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class RelayDatabase : RoomDatabase() {
    abstract fun relayDao(): RelayDao

    companion object {
        @Volatile
        private var INSTANCE: RelayDatabase? = null

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN dedupHash TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN explicitCode TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN serviceName TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN isManual INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_numberId_dedupHash ON messages(numberId, dedupHash)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS temp_numbers (
                        numberId TEXT NOT NULL PRIMARY KEY,
                        e164 TEXT NOT NULL,
                        mode TEXT NOT NULL,
                        sourceType TEXT NOT NULL,
                        resolvedSource TEXT,
                        customSourceId INTEGER,
                        label TEXT NOT NULL,
                        notes TEXT NOT NULL,
                        lastSyncEpochMs INTEGER,
                        status TEXT NOT NULL,
                        lastError TEXT,
                        isPinned INTEGER NOT NULL,
                        isMuted INTEGER NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        FOREIGN KEY(numberId) REFERENCES numbers(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_temp_numbers_mode ON temp_numbers(mode)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_temp_numbers_isPinned ON temp_numbers(isPinned)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS custom_sources (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        urlTemplate TEXT NOT NULL,
                        parserType TEXT NOT NULL,
                        jsonArrayPath TEXT NOT NULL,
                        jsonFromKey TEXT NOT NULL,
                        jsonTextKey TEXT NOT NULL,
                        jsonCodeKey TEXT NOT NULL,
                        jsonTimeKey TEXT NOT NULL,
                        jsonServiceKey TEXT NOT NULL,
                        htmlRegexPattern TEXT NOT NULL,
                        cacheSeconds INTEGER NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'sms'")
                db.execSQL("ALTER TABLE messages ADD COLUMN audioUrl TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN localAudioPath TEXT")
            }
        }

        fun getInstance(context: Context): RelayDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RelayDatabase::class.java,
                    "relay_sms.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
