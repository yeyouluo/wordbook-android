package com.example.wordbook.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Word::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun wordDao(): WordDao

    companion object {
        /** v1 -> v2：新增同根词与复习统计列，老数据全部保留 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE words ADD COLUMN family TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE words ADD COLUMN reviewCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE words ADD COLUMN reviewCorrectCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE words ADD COLUMN lastReviewedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v2 -> v3：累计答对/答错次数（正确率统计），同样是追加式迁移 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE words ADD COLUMN totalCorrect INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE words ADD COLUMN totalWrong INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "wordbook.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
