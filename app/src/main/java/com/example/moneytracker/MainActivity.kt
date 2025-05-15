package com.example.moneytracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.View.OnClickListener
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.moneytracker.data.AppDatabase
import com.example.moneytracker.data.Transaction
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.lifecycle.asFlow
import androidx.viewpager2.widget.ViewPager2
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var database: AppDatabase
    private var currentTransactionId: Long? = null
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPagerAdapter: ViewPagerAdapter
    private var transactionsFragment: TransactionsFragment? = null
    private lateinit var filterFab: FloatingActionButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Set up toolbar
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        
        // Use an icon instead of title for a cleaner look
        supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbar.setTitle(R.string.app_name)
        
        database = AppDatabase.getDatabase(this)
        setupViewPager()
        setupFilterFab()
        checkPermissions()

        // Check if we need to show the tagging dialog
        currentTransactionId = intent.getLongExtra("transaction_id", -1)
        if (currentTransactionId != -1L) {
            lifecycleScope.launch {
                val transaction = database.transactionDao().getTransactionById(currentTransactionId!!)
                transaction?.let {
                    showTaggingDialog(it)
                }
            }
        }
    }

    private fun setupViewPager() {
        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)
        
        viewPagerAdapter = ViewPagerAdapter(this)
        viewPager.adapter = viewPagerAdapter
        
        // Connect TabLayout with ViewPager2
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Transactions"
                1 -> "Expense Analysis"
                else -> "Tab ${position + 1}"
            }
        }.attach()
        
        // Update visuals when tab changes
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                // Show/hide FAB based on selected tab
                updateFilterFabVisibility(position)
            }
        })
    }
    
    private fun setupFilterFab() {
        filterFab = findViewById(R.id.filterFab)
        
        // Initially update visibility based on current tab
        if (::viewPager.isInitialized) {
            updateFilterFabVisibility(viewPager.currentItem)
        } else {
            // Default to hiding if viewPager is not yet initialized
            filterFab.hide()
        }
        
        // Set up click listener with explicit type for the view parameter
        filterFab.setOnClickListener(View.OnClickListener { view ->
            // Animate the button press
            view.animate()
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(100)
                .withEndAction {
                    view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(100)
                        .start()
                    
                    // Forward the click to the transactions fragment
                    if (viewPager.currentItem == 0) {
                        (viewPagerAdapter.createFragment(0) as? TransactionsFragment)?.onFilterFabClicked()
                    } else {
                        // If somehow we're on another tab but the FAB is visible
                        Log.d("MainActivity", "Filter FAB clicked but not on transactions tab")
                        showFilterDialog()
                    }
                }
                .start()
        })
        
        // Add a long press tooltip
        filterFab.setOnLongClickListener {
            Toast.makeText(
                this,
                "Filter transactions by tag",
                Toast.LENGTH_SHORT
            ).show()
            true
        }
    }
    
    private fun updateFilterFabVisibility(tabPosition: Int) {
        try {
            // Only show the FAB on the Transactions tab (position 0)
            if (tabPosition == 0) {
                filterFab.show()
            } else {
                filterFab.hide()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error updating FAB visibility: ${e.message}", e)
        }
    }
    
    /**
     * Called by TransactionsFragment when it's active to set up the filter FAB.
     * This ensures the FAB is properly configured to work with the transactions fragment.
     */
    fun setupFilterFabForTransactions() {
        Log.d("MainActivity", "Setting up filter FAB for transactions tab")
        try {
            // Make sure the FAB is visible when we're on the Transactions tab
            if (::viewPager.isInitialized && viewPager.currentItem == 0) {
                filterFab.show()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in setupFilterFabForTransactions: ${e.message}", e)
        }
    }

    fun showTaggingDialog(transaction: Transaction) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_tag_transaction, null)
        dialog.setContentView(view)

        // Set up the transaction info text
        val infoText = view.findViewById<TextView>(R.id.transactionInfoText)
        val amountString = String.format("₹%.2f", if (transaction.isExpense) -transaction.amount else transaction.amount)
        val dateString = if (transaction.transactionTime.isNotEmpty()) {
            transaction.transactionTime
        } else {
            dateFormat.format(transaction.date)
        }
        
        // Create a formatted info string
        val infoBuilder = StringBuilder()
        infoBuilder.append("$amountString on $dateString\n")
        
        if (transaction.bank.isNotEmpty()) {
            infoBuilder.append("Bank: ${transaction.bank}\n")
        }
        
        if (transaction.accountType.isNotEmpty()) {
            infoBuilder.append("Account: ${transaction.accountType}")
            if (transaction.accountNumber.isNotEmpty()) {
                infoBuilder.append(" xx${transaction.accountNumber}")
            }
            infoBuilder.append("\n")
        }
        
        infoText.text = infoBuilder.toString()

        // Set up the tag inputs
        val tagInput = view.findViewById<TextInputEditText>(R.id.tagInput)
        val customTagsInput = view.findViewById<TextInputEditText>(R.id.customTagsInput)
        val previewChipGroup = view.findViewById<ChipGroup>(R.id.previewChipGroup)
        val saveButton = view.findViewById<View>(R.id.saveButton)

        // Set initial values
        tagInput.setText(transaction.tag)
        
        // Join existing custom tags with commas
        if (transaction.customTags.isNotEmpty()) {
            customTagsInput.setText(transaction.customTags.joinToString(", "))
            updateChipPreview(transaction.customTags, previewChipGroup)
        }
        
        // Add text change listener for custom tags
        customTagsInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                val tagText = s.toString()
                if (tagText.isNotEmpty()) {
                    val tags = tagText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    updateChipPreview(tags, previewChipGroup)
                } else {
                    previewChipGroup.removeAllViews()
                }
            }
        })

        saveButton.setOnClickListener {
            val primaryTag = tagInput.text.toString()
            val customTagsText = customTagsInput.text.toString()
            val customTags = if (customTagsText.isNotEmpty()) {
                customTagsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                emptyList()
            }
            
            lifecycleScope.launch {
                val updatedTransaction = transaction.copy(
                    tag = primaryTag,
                    customTags = customTags
                )
                database.transactionDao().update(updatedTransaction)
                dialog.dismiss()
                
                // Refresh the transactions fragment if it's active
                if (viewPager.currentItem == 0) {
                    (viewPagerAdapter.createFragment(0) as? TransactionsFragment)?.loadAllTransactions()
                }
            }
        }

        dialog.show()
    }
    
    private fun updateChipPreview(tags: List<String>, chipGroup: ChipGroup) {
        chipGroup.removeAllViews()
        tags.forEach { tag ->
            if (tag.isNotEmpty()) {
                val chip = com.google.android.material.chip.Chip(this)
                chip.text = tag
                chip.isCheckable = false
                // Set the close icon visibility using method
                chip.setCloseIconVisible(false)
                chipGroup.addView(chip)
            }
        }
    }

    fun showFilterDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_filter, null)
        dialog.setContentView(view)

        // Get references to views
        val tabLayout = view.findViewById<TabLayout>(R.id.filterTabLayout)
        val primaryTagsChipGroup = view.findViewById<ChipGroup>(R.id.primaryTagsChipGroup)
        val customTagsChipGroup = view.findViewById<ChipGroup>(R.id.customTagsChipGroup)
        val clearFilterButton = view.findViewById<View>(R.id.clearFilterButton)
        
        // Set up tab change listener
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> { // Primary Tags
                        primaryTagsChipGroup.visibility = View.VISIBLE
                        customTagsChipGroup.visibility = View.GONE
                    }
                    1 -> { // Custom Tags
                        primaryTagsChipGroup.visibility = View.GONE
                        customTagsChipGroup.visibility = View.VISIBLE
                    }
                }
            }
            
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        
        // Get the current TransactionsFragment
        val transactionFragment = viewPagerAdapter.createFragment(0) as TransactionsFragment
        
        // Load primary tags
        lifecycleScope.launch {
            try {
                database.transactionDao().getAllTags().asFlow().collect { tags ->
                    primaryTagsChipGroup.removeAllViews()
                    
                    if (tags.isEmpty()) {
                        // Add a message if no tags exist
                        val textView = TextView(this@MainActivity)
                        textView.text = "No primary tags available"
                        textView.setPadding(16, 16, 16, 16)
                        primaryTagsChipGroup.addView(textView)
                    } else {
                        tags.forEach { tag ->
                            val chip = com.google.android.material.chip.Chip(this@MainActivity).apply {
                                text = tag
                                isCheckable = true
                                setChipBackgroundColorResource(R.color.purple_200)
                                // Make sure UI properly reflects checked state
                                setTextColor(resources.getColor(android.R.color.black, null))
                                
                                setOnCheckedChangeListener { _, isChecked ->
                                    if (isChecked) {
                                        // Apply filter
                                        transactionFragment.filterByPrimaryTag(tag)
                                        // Show clear feedback that filtering is active
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Filtering by tag: $tag",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        transactionFragment.loadAllTransactions()
                                    }
                                }
                            }
                            primaryTagsChipGroup.addView(chip)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading primary tags: ${e.message}", e)
                Toast.makeText(
                    this@MainActivity,
                    "Error loading tags",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        
        // Load custom tags
        lifecycleScope.launch {
            try {
                database.transactionDao().getAllCustomTags().asFlow().collect { rawTags ->
                    customTagsChipGroup.removeAllViews()
                    
                    // Parse all custom tags from the concatenated strings
                    val allCustomTags = mutableSetOf<String>()
                    rawTags.forEach { tagsString ->
                        allCustomTags.addAll(tagsString.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                    }
                    
                    if (allCustomTags.isEmpty()) {
                        // Add a message if no custom tags exist
                        val textView = TextView(this@MainActivity)
                        textView.text = "No custom tags available"
                        textView.setPadding(16, 16, 16, 16)
                        customTagsChipGroup.addView(textView)
                    } else {
                        // Create chips for each unique custom tag
                        allCustomTags.forEach { tag ->
                            val chip = com.google.android.material.chip.Chip(this@MainActivity).apply {
                                text = tag
                                isCheckable = true
                                setChipBackgroundColorResource(R.color.teal_200)
                                // Make sure UI properly reflects checked state
                                setTextColor(resources.getColor(android.R.color.black, null))
                                
                                setOnCheckedChangeListener { _, isChecked ->
                                    if (isChecked) {
                                        // Apply filter
                                        transactionFragment.filterByCustomTag(tag)
                                        // Show clear feedback that filtering is active
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Filtering by custom tag: $tag",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        transactionFragment.loadAllTransactions()
                                    }
                                }
                            }
                            customTagsChipGroup.addView(chip)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading custom tags: ${e.message}", e)
                Toast.makeText(
                    this@MainActivity,
                    "Error loading custom tags",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        
        // Set up clear filter button
        clearFilterButton.setOnClickListener {
            // Uncheck all chips
            for (i in 0 until primaryTagsChipGroup.childCount) {
                val chip = primaryTagsChipGroup.getChildAt(i) as? Chip
                chip?.isChecked = false
            }
            
            for (i in 0 until customTagsChipGroup.childCount) {
                val chip = customTagsChipGroup.getChildAt(i) as? Chip
                chip?.isChecked = false
            }
            
            // Load all transactions
            transactionFragment.loadAllTransactions()
        }

        dialog.show()
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest, PERMISSIONS_REQUEST_CODE)
        }
        
        // Check for overlay permission
        if (!checkOverlayPermission()) {
            requestOverlayPermission()
        }
    }
    
    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true // On older devices, this permission is granted by default
        }
    }
    
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AlertDialog.Builder(this)
                .setTitle("Overlay Permission Required")
                .setMessage("This app needs permission to display over other apps to show transaction tagging dialogs when new SMS messages arrive.")
                .setPositiveButton("Grant") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    dialog.dismiss()
                    Toast.makeText(
                        this,
                        "Overlay permission is required for instant tagging",
                        Toast.LENGTH_LONG
                    ).show()
                }
                .create()
                .show()
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(
                        this,
                        "Overlay permission denied. Instant tagging will not work.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 123
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 456
    }
}