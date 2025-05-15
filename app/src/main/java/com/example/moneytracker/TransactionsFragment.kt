package com.example.moneytracker

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.asFlow
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.moneytracker.data.AppDatabase
import com.example.moneytracker.data.Transaction
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TransactionsFragment : Fragment() {
    private lateinit var adapter: TransactionAdapter
    private lateinit var database: AppDatabase
    private lateinit var emptyView: TextView
    private lateinit var recyclerView: RecyclerView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_transactions, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        database = AppDatabase.getDatabase(requireContext())
        emptyView = view.findViewById(R.id.emptyView)
        recyclerView = view.findViewById(R.id.transactionsRecyclerView)
        
        setupRecyclerView(view)
        setupFilterFab(view)
    }

    private fun setupRecyclerView(view: View) {
        adapter = TransactionAdapter { transaction ->
            (activity as? MainActivity)?.showTaggingDialog(transaction)
        }
        
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // Observe transactions
        viewLifecycleOwner.lifecycleScope.launch {
            database.transactionDao().getAllTransactions().asFlow().collectLatest { transactions ->
                adapter.submitList(transactions)
                updateEmptyState(transactions.isEmpty())
            }
        }
    }
    
    private fun updateEmptyState(isEmpty: Boolean) {
        if (isEmpty) {
            emptyView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun setupFilterFab(view: View) {
        // The FAB is actually in the MainActivity layout, not the fragment layout
        // We'll implement a method that the MainActivity can call to register the FAB
        
        android.util.Log.d("TransactionsFragment", "Setting up filter FAB via activity")
        
        // Notify the activity that we're active so it can set up the FAB
        (activity as? MainActivity)?.setupFilterFabForTransactions()
    }
    
    // This method will be called by the MainActivity when the FAB is clicked
    fun onFilterFabClicked() {
        android.util.Log.d("TransactionsFragment", "Filter FAB clicked")
        
        try {
            // Show a toast to confirm the button was clicked
            android.widget.Toast.makeText(
                context,
                "Opening filters...",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            
            // Open the filter dialog
            (activity as? MainActivity)?.showFilterDialog()
        } catch (e: Exception) {
            android.util.Log.e("TransactionsFragment", "Error showing filter dialog: ${e.message}", e)
            android.widget.Toast.makeText(
                context,
                "Error showing filters",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun filterByPrimaryTag(tag: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            database.transactionDao().getTransactionsByTag(tag).asFlow().collect { transactions ->
                adapter.submitList(transactions)
                updateEmptyState(transactions.isEmpty())
            }
        }
    }
    
    fun filterByCustomTag(tag: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            database.transactionDao().getTransactionsByCustomTag(tag).asFlow().collect { transactions ->
                adapter.submitList(transactions)
                updateEmptyState(transactions.isEmpty())
            }
        }
    }

    fun loadAllTransactions() {
        viewLifecycleOwner.lifecycleScope.launch {
            database.transactionDao().getAllTransactions().asFlow().collect { transactions ->
                adapter.submitList(transactions)
                updateEmptyState(transactions.isEmpty())
            }
        }
    }
} 