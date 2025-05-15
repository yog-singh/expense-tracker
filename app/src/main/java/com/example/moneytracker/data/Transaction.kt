package com.example.moneytracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val amount: Double,
    val description: String,
    var tag: String,
    val date: Date,
    val smsBody: String,
    val isExpense: Boolean,
    // Additional contextual data
    val bank: String = "",
    val accountType: String = "",
    val accountNumber: String = "",
    val transactionTime: String = "",
    val customTags: List<String> = emptyList()
) 