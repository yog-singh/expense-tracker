package com.example.moneytracker

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.moneytracker.data.AppDatabase
import com.example.moneytracker.data.Transaction
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import android.widget.LinearLayout
import java.util.concurrent.TimeUnit

class ExpenseAnalysisFragment : Fragment() {
    private lateinit var database: AppDatabase
    private lateinit var monthYearSpinner: Spinner
    private lateinit var chartContainer: LinearLayout
    private lateinit var noDataText: TextView
    private lateinit var totalExpenseText: TextView
    
    private val dateFormat = SimpleDateFormat("MMM yyyy", Locale.getDefault())
    private val calendar = Calendar.getInstance()
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_expense_analysis, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        database = AppDatabase.getDatabase(requireContext())
        
        // Initialize views
        monthYearSpinner = view.findViewById(R.id.monthYearSpinner)
        chartContainer = view.findViewById(R.id.chartContainer)
        noDataText = view.findViewById(R.id.noDataText)
        totalExpenseText = view.findViewById(R.id.totalExpenseText)
        
        setupMonthYearSpinner()
    }
    
    private fun setupMonthYearSpinner() {
        val dates = ArrayList<Date>()
        
        // Add the last 12 months to the spinner
        val calendar = Calendar.getInstance()
        for (i in 0 until 12) {
            dates.add(calendar.time)
            calendar.add(Calendar.MONTH, -1)
        }
        
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            dates.map { dateFormat.format(it) }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        monthYearSpinner.adapter = adapter
        
        monthYearSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedDate = dates[position]
                loadExpensesForMonth(selectedDate)
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    private fun loadExpensesForMonth(date: Date) {
        val calendar = Calendar.getInstance()
        calendar.time = date
        
        // Set time to the beginning of the month
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        val startDate = calendar.timeInMillis
        
        // Set time to the end of the month
        calendar.add(Calendar.MONTH, 1)
        calendar.add(Calendar.MILLISECOND, -1)
        val endDate = calendar.timeInMillis
        
        viewLifecycleOwner.lifecycleScope.launch {
            val transactions = database.transactionDao().getTransactionsBetweenDates(Date(startDate), Date(endDate))
            updateChart(transactions)
        }
    }
    
    private fun updateChart(transactions: List<Transaction>) {
        // Clear previous chart
        chartContainer.removeAllViews()
        
        // Filter only expense transactions
        val expenses = transactions.filter { it.isExpense }
        
        if (expenses.isEmpty()) {
            noDataText.visibility = View.VISIBLE
            totalExpenseText.visibility = View.GONE
            return
        }
        
        noDataText.visibility = View.GONE
        totalExpenseText.visibility = View.VISIBLE
        
        // Calculate total expense
        val totalExpense = expenses.sumOf { it.amount }
        totalExpenseText.text = String.format("Total Expenses: ₹%.2f", totalExpense)
        
        // Group expenses by tag
        val expensesByTag = expenses.groupBy { it.tag.ifEmpty { "Untagged" } }
            .mapValues { it.value.sumOf { transaction -> transaction.amount } }
            .toList()
            .sortedByDescending { it.second }
        
        // Create simple bar representation for each tag
        val colors = arrayOf(
            Color.parseColor("#4285F4"), // Google Blue
            Color.parseColor("#EA4335"), // Google Red
            Color.parseColor("#FBBC05"), // Google Yellow
            Color.parseColor("#34A853"), // Google Green
            Color.parseColor("#FF6D00"), // Orange
            Color.parseColor("#9C27B0"), // Purple
            Color.parseColor("#2196F3"), // Light Blue
            Color.parseColor("#FF5722")  // Deep Orange
        )
        
        expensesByTag.forEachIndexed { index, (tag, amount) ->
            val itemView = layoutInflater.inflate(R.layout.chart_item, chartContainer, false)
            
            val tagText = itemView.findViewById<TextView>(R.id.tagText)
            val amountText = itemView.findViewById<TextView>(R.id.amountText)
            val percentageText = itemView.findViewById<TextView>(R.id.percentageText)
            val barView = itemView.findViewById<View>(R.id.barView)
            
            val percentage = (amount / totalExpense) * 100
            
            tagText.text = tag
            amountText.text = String.format("₹%.2f", amount)
            percentageText.text = String.format("%.1f%%", percentage)
            
            // Set the width of the bar relative to the percentage
            val layoutParams = barView.layoutParams
            layoutParams.width = (percentage * 3).toInt()
            barView.layoutParams = layoutParams
            
            // Set color
            barView.setBackgroundColor(colors[index % colors.size])
            
            chartContainer.addView(itemView)
        }
    }
} 