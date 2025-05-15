package com.example.moneytracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import android.widget.Toast
import com.example.moneytracker.data.AppDatabase
import com.example.moneytracker.data.Transaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

class SmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SmsReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                messages?.forEach { sms ->
                    val messageBody = sms.messageBody
                    if (isBankMessage(messageBody)) {
                        processBankMessage(context, messageBody)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing SMS: ${e.message}", e)
        }
    }

    private fun isBankMessage(message: String): Boolean {
        // Add your bank message patterns here
        val patterns = listOf(
            "debited",
            "credited",
            "spent",
            "transaction",
            "payment",
            "withdrawal",
            "Rs.",
            "INR"
        )
        return patterns.any { message.lowercase().contains(it) }
    }

    private fun processBankMessage(context: Context, message: String) {
        try {
            val amount = extractAmount(message)
            if (amount != null) {
                val isExpense = message.lowercase().contains("debited") || 
                              message.lowercase().contains("spent") ||
                              message.lowercase().contains("withdrawal")

                val tag = extractTag(message)
                val bank = extractBank(message)
                val accountInfo = extractAccountInfo(message)
                val transactionTime = extractTransactionTime(message)

                val transaction = Transaction(
                    amount = amount,
                    description = message,
                    tag = tag,
                    date = Date(),
                    smsBody = message,
                    isExpense = isExpense,
                    bank = bank,
                    accountType = accountInfo.first,
                    accountNumber = accountInfo.second,
                    transactionTime = transactionTime
                )

                // Save to database
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val database = AppDatabase.getDatabase(context)
                        val transactionId = database.transactionDao().insert(transaction)
                        
                        // Show overlay for tagging
                        showTaggingOverlay(context, transactionId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error saving transaction: ${e.message}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing bank message: ${e.message}", e)
        }
    }
    
    private fun showTaggingOverlay(context: Context, transactionId: Long) {
        try {
            // Launch floating overlay service
            val intent = Intent(context, OverlayTaggingService::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("transaction_id", transactionId)
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing overlay: ${e.message}", e)
            
            // Fallback to notification if overlay fails
            val mainIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("transaction_id", transactionId)
            }
            context.startActivity(mainIntent)
        }
    }

    private fun extractAmount(message: String): Double? {
        // Extract amount with improved regex patterns
        val patterns = listOf(
            Pattern.compile("""(?:Rs\.?|INR)\s*(\d+(?:,\d+)*(?:\.\d{2})?)"""),
            Pattern.compile("""(?:Rs\.?|INR)\s*([0-9,]+\.[0-9]{2})"""),
            Pattern.compile("""(?:Rs\.?|INR)\s*([0-9,]+)""")
        )
        
        for (pattern in patterns) {
            val matcher = pattern.matcher(message)
            if (matcher.find()) {
                return matcher.group(1)?.replace(",", "")?.toDoubleOrNull()
            }
        }
        return null
    }

    private fun extractBank(message: String): String {
        // List of common banks and their name variations that might appear in SMS
        val bankPatterns = mapOf(
            "SBI" to "State Bank of India",
            "HDFC" to "HDFC Bank",
            "ICICI" to "ICICI Bank",
            "AXIS" to "Axis Bank",
            "PNB" to "Punjab National Bank",
            "BOB" to "Bank of Baroda",
            "CANARA" to "Canara Bank",
            "IDBI" to "IDBI Bank",
            "KOTAK" to "Kotak Mahindra Bank",
            "YES" to "Yes Bank",
            "IndusInd" to "IndusInd Bank",
            "INDIAN" to "Indian Bank",
            "IOB" to "Indian Overseas Bank"
        )
        
        // Check for bank name at the end of the message
        for ((shortName, fullName) in bankPatterns) {
            if (message.contains("$shortName.", ignoreCase = true) || 
                message.endsWith(shortName, ignoreCase = true) ||
                message.endsWith(fullName, ignoreCase = true) ||
                message.contains(fullName, ignoreCase = true)) {
                return fullName
            }
        }
        
        return ""
    }

    private fun extractAccountInfo(message: String): Pair<String, String> {
        var accountType = ""
        var accountNumber = ""
        
        // Extract account type
        when {
            message.contains("SB", ignoreCase = true) || 
            message.contains("Saving", ignoreCase = true) -> accountType = "Savings Account"
            
            message.contains("CA", ignoreCase = true) || 
            message.contains("Current", ignoreCase = true) -> accountType = "Current Account"
            
            message.contains("Card", ignoreCase = true) -> accountType = "Card"
            
            message.contains("Loan", ignoreCase = true) -> accountType = "Loan Account"
            
            message.contains("FD", ignoreCase = true) -> accountType = "Fixed Deposit"
        }
        
        // Extract account number using patterns like XX1234, XXXX1234, etc.
        val accountPatterns = listOf(
            Pattern.compile("""(?:a/c|ac|acct|account|card)(?:\s+no\.?|\s+number|\s+ending|\s+)(?:\s+with)?\s+(?:xx|x{2,}|[*]{2,})?([0-9]{4,})""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""(?:xx|x{2,}|[*]{2,})([0-9]{4,})""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""(?:sb|ca|card|loan)-(?:xx|x{2,}|[*]{2,})?([0-9]{4,})""", Pattern.CASE_INSENSITIVE)
        )
        
        for (pattern in accountPatterns) {
            val matcher = pattern.matcher(message)
            if (matcher.find()) {
                accountNumber = matcher.group(1) ?: ""
                break
            }
        }
        
        return Pair(accountType, accountNumber)
    }

    private fun extractTransactionTime(message: String): String {
        // Various date and time patterns in SMS
        val dateTimePatterns = listOf(
            Pattern.compile("""(\d{1,2}-\d{1,2}-\d{2,4})\s+(\d{1,2}:\d{1,2}(?::\d{1,2})?)"""),
            Pattern.compile("""(\d{1,2}/\d{1,2}/\d{2,4})\s+(\d{1,2}:\d{1,2}(?::\d{1,2})?)"""),
            Pattern.compile("""(\d{1,2}\s+[a-zA-Z]{3}\s+\d{2,4})(?:\s+at\s+|\s+)(\d{1,2}:\d{1,2}(?::\d{1,2})?)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""(\d{1,2}-[a-zA-Z]{3}-\d{2,4})""", Pattern.CASE_INSENSITIVE)
        )
        
        for (pattern in dateTimePatterns) {
            val matcher = pattern.matcher(message)
            if (matcher.find()) {
                if (matcher.groupCount() >= 2) {
                    return "${matcher.group(1)} ${matcher.group(2)}"
                } else {
                    return matcher.group(1) ?: ""
                }
            }
        }
        
        return ""
    }

    private fun extractTag(message: String): String {
        val lowerMessage = message.lowercase()
        
        // Common merchant patterns
        val merchantPatterns = mapOf(
            "swiggy" to "Food",
            "zomato" to "Food",
            "uber" to "Transport",
            "ola" to "Transport",
            "amazon" to "Shopping",
            "flipkart" to "Shopping",
            "myntra" to "Shopping",
            "netflix" to "Entertainment",
            "hotstar" to "Entertainment",
            "prime" to "Entertainment",
            "spotify" to "Entertainment",
            "airtel" to "Bills",
            "jio" to "Bills",
            "vi" to "Bills",
            "vodafone" to "Bills",
            "electricity" to "Bills",
            "water" to "Bills",
            "gas" to "Bills",
            "rent" to "Housing",
            "salary" to "Income",
            "atm" to "Cash",
            "upi" to "Transfer"
        )

        // Check for merchant patterns
        for ((keyword, tag) in merchantPatterns) {
            if (lowerMessage.contains(keyword)) {
                return tag
            }
        }

        // Check for common transaction types
        when {
            lowerMessage.contains("salary") || lowerMessage.contains("credited") -> return "Income"
            lowerMessage.contains("atm") || lowerMessage.contains("cash") -> return "Cash"
            lowerMessage.contains("rent") || lowerMessage.contains("house") -> return "Housing"
            lowerMessage.contains("food") || lowerMessage.contains("restaurant") -> return "Food"
            lowerMessage.contains("movie") || lowerMessage.contains("theatre") -> return "Entertainment"
            lowerMessage.contains("petrol") || lowerMessage.contains("fuel") -> return "Transport"
            lowerMessage.contains("medical") || lowerMessage.contains("hospital") -> return "Healthcare"
            lowerMessage.contains("school") || lowerMessage.contains("college") -> return "Education"
        }

        return ""
    }
} 