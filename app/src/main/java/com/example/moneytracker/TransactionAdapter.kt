package com.example.moneytracker

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.moneytracker.data.Transaction
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.text.SimpleDateFormat
import java.util.Locale

class TransactionAdapter(
    private val onItemClick: (Transaction) -> Unit
) : ListAdapter<Transaction, TransactionAdapter.TransactionViewHolder>(TransactionDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            R.layout.item_transaction,
            parent,
            false
        )
        return TransactionViewHolder(view)
    }

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TransactionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val amountTextView: TextView = itemView.findViewById(R.id.amountTextView)
        private val dateTextView: TextView = itemView.findViewById(R.id.dateTextView)
        private val contextTextView: TextView = itemView.findViewById(R.id.contextTextView)
        private val descriptionTextView: TextView = itemView.findViewById(R.id.descriptionTextView)
        private val tagChip: Chip = itemView.findViewById(R.id.tagChip)
        private val customTagsChipGroup: ChipGroup = itemView.findViewById(R.id.customTagsChipGroup)
        private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

        init {
            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position))
                }
            }
        }

        @SuppressLint("DefaultLocale")
        fun bind(transaction: Transaction) {
            // Amount
            amountTextView.text = String.format(
                "₹%.2f",
                if (transaction.isExpense) -transaction.amount else transaction.amount
            )
            amountTextView.setTextColor(
                itemView.context.getColor(
                    if (transaction.isExpense) android.R.color.holo_red_dark
                    else android.R.color.holo_green_dark
                )
            )
            
            // Date
            dateTextView.text = transaction.transactionTime.ifEmpty { dateFormat.format(transaction.date) }
            
            // Create contextual summary
            val contextSummary = buildString {
                if (transaction.bank.isNotEmpty()) {
                    append(transaction.bank)
                    if (transaction.accountType.isNotEmpty() || transaction.accountNumber.isNotEmpty()) {
                        append(" • ")
                    }
                }
                
                if (transaction.accountType.isNotEmpty()) {
                    append(transaction.accountType)
                    if (transaction.accountNumber.isNotEmpty()) {
                        append(" ")
                    }
                }
                
                if (transaction.accountNumber.isNotEmpty()) {
                    append("xx")
                    append(transaction.accountNumber)
                }
            }
            
            // Set the summary or original description if no context
            if (contextSummary.isNotEmpty()) {
                contextTextView.text = contextSummary
                contextTextView.visibility = View.VISIBLE
            } else {
                contextTextView.visibility = View.GONE
            }
            
            // Description
            descriptionTextView.text = transaction.description
            
            // Set up the tag chip
            tagChip.text = transaction.tag.ifEmpty { "Untagged" }
            
            // Set up custom tags if available
            customTagsChipGroup.removeAllViews()
            if (transaction.customTags.isNotEmpty()) {
                customTagsChipGroup.visibility = View.VISIBLE
                transaction.customTags.forEach { tag ->
                    val chip = Chip(itemView.context)
                    chip.text = tag
                    chip.isCheckable = false
                    chip.isClickable = false
                    customTagsChipGroup.addView(chip)
                }
            } else {
                customTagsChipGroup.visibility = View.GONE
            }
        }
    }

    private class TransactionDiffCallback : DiffUtil.ItemCallback<Transaction>() {
        override fun areItemsTheSame(oldItem: Transaction, newItem: Transaction): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Transaction, newItem: Transaction): Boolean {
            return oldItem == newItem
        }
    }
} 