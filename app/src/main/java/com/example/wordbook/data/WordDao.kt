package com.example.wordbook.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WordDao {

    @Query("SELECT * FROM words ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Word>>

    @Query("SELECT * FROM words WHERE spelling LIKE '%' || :query || '%' OR meaning LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun search(query: String): Flow<List<Word>>

    @Insert
    suspend fun insert(word: Word): Long

    @Insert
    suspend fun insertAll(words: List<Word>)

    @Update
    suspend fun update(word: Word)

    @Delete
    suspend fun delete(word: Word)

    @Query("SELECT COUNT(*) FROM words")
    suspend fun count(): Int

    @Query("SELECT * FROM words ORDER BY createdAt DESC")
    suspend fun getAllOnce(): List<Word>

    @Query("SELECT * FROM words WHERE mastered = 0")
    suspend fun getUnmasteredOnce(): List<Word>

    @Query("SELECT spelling FROM words")
    suspend fun getAllSpellings(): List<String>

    @Query("SELECT COUNT(*) FROM words WHERE mastered = 1")
    suspend fun masteredCount(): Int

    @Query("DELETE FROM words")
    suspend fun deleteAll()

    /**
     * 云同步 pull 用：整表替换（事务内先清空再写入），
     * 调用方负责在此之前做好本机备份（见 WordBookViewModel.pullFromRemote）。
     */
    @Transaction
    suspend fun replaceAll(words: List<Word>) {
        deleteAll()
        insertAll(words)
    }
}