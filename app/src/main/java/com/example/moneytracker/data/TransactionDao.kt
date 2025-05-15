package com.example.moneytracker.data

import androidx.lifecycle.LiveData
import androidx.room.*
import java.util.Date

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY date DESC")
    fun getAllTransactions(): LiveData<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE tag = :tag")
    fun getTransactionsByTag(tag: String): LiveData<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE tag = :tag ORDER BY date DESC LIMIT 1")
    suspend fun getTransactionByTag(tag: String): Transaction?

    @Query("SELECT * FROM transactions WHERE customTags LIKE '%' || :customTag || '%'")
    fun getTransactionsByCustomTag(customTag: String): LiveData<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getTransactionById(id: Long): Transaction?

    @Query("SELECT * FROM transactions WHERE date BETWEEN :startDate AND :endDate ORDER BY date DESC")
    suspend fun getTransactionsBetweenDates(startDate: Date, endDate: Date): List<Transaction>

    @Query("SELECT DISTINCT tag FROM transactions WHERE tag != '' ORDER BY tag ASC")
    suspend fun getAllTagsList(): List<String>

    @Query("SELECT tag FROM transactions WHERE tag != '' GROUP BY tag ORDER BY COUNT(tag) DESC LIMIT :limit")
    suspend fun getMostUsedTags(limit: Int): List<String>

    @Insert
    suspend fun insert(transaction: Transaction): Long

    @Update
    suspend fun update(transaction: Transaction)

    @Delete
    suspend fun delete(transaction: Transaction)

    @Query("SELECT DISTINCT tag FROM transactions WHERE tag != ''")
    fun getAllTags(): LiveData<List<String>>

    @Query("SELECT DISTINCT customTags FROM transactions WHERE customTags != ''")
    fun getAllCustomTags(): LiveData<List<String>>
} 