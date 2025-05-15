package com.example.moneytracker

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.example.moneytracker.data.AppDatabase
import com.example.moneytracker.data.Transaction
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale

class OverlayTaggingService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private lateinit var database: AppDatabase
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val TAG = "OverlayTaggingService"
    private var allTags = listOf<String>()
    private var popularTags = listOf<String>()
    private var currentTransactionId: Long = -1L
    
    companion object {
        // Keep track of all active overlay services with thread safety
        private val activeOverlays = Collections.synchronizedSet(mutableSetOf<Long>())
        // Add a timeout mechanism to automatically clean up stale overlays after 2 minutes
        private const val OVERLAY_TIMEOUT_MS = 2 * 60 * 1000L // 2 minutes
        
        fun isTransactionOverlayActive(transactionId: Long): Boolean {
            synchronized(activeOverlays) {
                return activeOverlays.contains(transactionId)
            }
        }
        
        // Add a transaction to active overlays with automatic timeout cleanup
        fun addActiveOverlay(transactionId: Long) {
            synchronized(activeOverlays) {
                activeOverlays.add(transactionId)
                // Schedule removal after timeout
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    synchronized(activeOverlays) {
                        if (activeOverlays.contains(transactionId)) {
                            activeOverlays.remove(transactionId)
                            android.util.Log.d(
                                "OverlayTaggingService", 
                                "Removing stale overlay for transaction $transactionId after timeout"
                            )
                        }
                    }
                }, OVERLAY_TIMEOUT_MS)
            }
        }
        
        // Force close all active overlays
        fun closeAllOverlays(context: Context) {
            synchronized(activeOverlays) {
                if (activeOverlays.isNotEmpty()) {
                    android.util.Log.d(
                        "OverlayTaggingService", 
                        "Force closing ${activeOverlays.size} active overlays"
                    )
                    // Clear the set
                    activeOverlays.clear()
                    // Force stop all services
                    context.stopService(android.content.Intent(context, OverlayTaggingService::class.java))
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        database = AppDatabase.getDatabase(this)
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.e(TAG, "Null intent received")
            stopSelf()
            return START_NOT_STICKY
        }

        val transactionId = intent.getLongExtra("transaction_id", -1L)
        if (transactionId == -1L) {
            Log.e(TAG, "Invalid transaction ID")
            stopSelf()
            return START_NOT_STICKY
        }
        
        currentTransactionId = transactionId
        Log.d(TAG, "Received command for transaction $transactionId")
        
        // Check if this transaction is already being displayed
        if (isTransactionOverlayActive(transactionId)) {
            Log.d(TAG, "Overlay for transaction $transactionId is already active, not creating another one")
            // Try to bring the existing overlay to front by stopping and restarting this service
            closeAllOverlays(this)
            stopSelf()
            return START_NOT_STICKY
        }
        
        // Mark this transaction as active with timeout handling
        addActiveOverlay(transactionId)
        Log.d(TAG, "Added transaction $transactionId to active overlays with timeout")

        // Fetch the transaction and tags data
        coroutineScope.launch {
            try {
                Log.d(TAG, "Loading transaction data and tags")
                
                // Load transaction
                val transaction = database.transactionDao().getTransactionById(transactionId)
                if (transaction == null) {
                    Log.e(TAG, "Transaction not found")
                    activeOverlays.remove(transactionId)
                    stopSelf()
                    return@launch
                }
                
                // Load tags
                try {
                    allTags = database.transactionDao().getAllTagsList()
                    Log.d(TAG, "Loaded ${allTags.size} tags: ${allTags.take(5)}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading all tags: ${e.message}")
                    allTags = emptyList()
                }
                
                try {
                    popularTags = database.transactionDao().getMostUsedTags(5)
                    Log.d(TAG, "Loaded ${popularTags.size} popular tags: $popularTags")
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading popular tags: ${e.message}")
                    popularTags = emptyList()
                }
                
                // Show overlay on main thread
                withContext(Dispatchers.Main) {
                    try {
                        showOverlay(transaction)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error showing overlay: ${e.message}", e)
                        Toast.makeText(
                            this@OverlayTaggingService,
                            "Failed to show transaction overlay",
                            Toast.LENGTH_SHORT
                        ).show()
                        activeOverlays.remove(transactionId)
                        stopSelf()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error retrieving transaction: ${e.message}", e)
                activeOverlays.remove(transactionId)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun showOverlay(transaction: Transaction) {
        try {
            Log.d(TAG, "Showing overlay for transaction ${transaction.id}")
            
            // Create a ContextThemeWrapper with our overlay theme
            val contextThemeWrapper = android.view.ContextThemeWrapper(this, R.style.Theme_MoneyTracker_Overlay)
            val inflater = LayoutInflater.from(contextThemeWrapper)
            
            try {
                // Inflate with the themed context
                overlayView = inflater.inflate(R.layout.overlay_tagging, null)
                Log.d(TAG, "Overlay inflated successfully")
                
                // Set up close button first (important in case of error)
                val closeButton = overlayView.findViewById<View>(R.id.closeButton)
                closeButton?.setOnClickListener(object : View.OnClickListener {
                    override fun onClick(v: View) {
                        Log.d(TAG, "Close button clicked for transaction ${transaction.id}")
                        try {
                            // Disable the button to prevent multiple clicks
                            v.isEnabled = false
                            // Immediately show a toast so the user knows their click was registered
                            Toast.makeText(
                                this@OverlayTaggingService,
                                "Closing...",
                                Toast.LENGTH_SHORT
                            ).show()
                            // Close the overlay
                            closeOverlay(transaction.id)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error handling close button click: ${e.message}", e)
                            // Force stop if there's an error
                            stopSelf()
                        }
                    }
                })
                
                // Populate transaction info 
                populateTransactionInfo(transaction)
                
                // Make sure suggestedTagsContainer and its label are visible
                val suggestedTagsContainer = overlayView.findViewById<com.google.android.flexbox.FlexboxLayout>(R.id.suggestedTagsContainer)
                val suggestedTagsLabel = overlayView.findViewById<TextView>(R.id.suggestedTagsLabel)
                
                if (suggestedTagsContainer != null) {
                    suggestedTagsContainer.visibility = View.VISIBLE
                }
                
                if (suggestedTagsLabel != null) {
                    suggestedTagsLabel.visibility = View.VISIBLE
                }
                
                // Set up input fields and callbacks
                setupTagInputs(transaction)
                
                // Set up touch listener for dragging - do this after all UI setup
                setupDragListener()
            } catch (e: Exception) {
                Log.e(TAG, "Error inflating overlay: ${e.message}", e)
                
                // Use a fallback simple layout
                overlayView = createSimpleOverlay(transaction)
            }

            // Set layout parameters for overlay
            val params = createWindowParams()
            
            // Add view to window manager
            try {
                windowManager.addView(overlayView, params)
                Log.d(TAG, "Overlay added to window")
            } catch (e: Exception) {
                Log.e(TAG, "Error adding overlay to window: ${e.message}", e)
                activeOverlays.remove(transaction.id)
                stopSelf()
                return
            }
            
            // Make sure it's visible and touchable
            overlayView.post {
                try {
                    val params = overlayView.layoutParams as WindowManager.LayoutParams
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                    windowManager.updateViewLayout(overlayView, params)
                    Log.d(TAG, "Made overlay inputs touchable")
                } catch (e: Exception) {
                    Log.e(TAG, "Error making inputs touchable: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error showing overlay: ${e.message}", e)
            activeOverlays.remove(transaction.id)
            stopSelf()
        }
    }
    
    private fun closeOverlay(transactionId: Long) {
        Log.d(TAG, "Closing overlay for transaction $transactionId")
        try {
            // Remove from active overlays set immediately
            synchronized(activeOverlays) {
                activeOverlays.remove(transactionId)
                Log.d(TAG, "Removed transaction ID from active overlays. Remaining: ${activeOverlays.size}")
            }
            
            // Remove view from window manager if it's attached
            if (::overlayView.isInitialized) {
                try {
                    if (overlayView.windowToken != null) {
                        windowManager.removeView(overlayView)
                        Log.d(TAG, "Overlay view removed from window")
                    } else {
                        Log.d(TAG, "Overlay view wasn't attached to window")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing overlay view: ${e.message}", e)
                }
            }
            
            // Stop this specific service instance
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing overlay: ${e.message}", e)
            // Force stop as a fallback
            stopSelf()
        }
    }
    
    private fun createSimpleOverlay(transaction: Transaction): View {
        Log.d(TAG, "Creating simple overlay fallback")
        
        // Create a simple fallback layout with material components
        val contextThemeWrapper = android.view.ContextThemeWrapper(this, R.style.Theme_MoneyTracker_Overlay)
        
        // Use CardView for the root
        val card = com.google.android.material.card.MaterialCardView(contextThemeWrapper).apply {
            radius = resources.getDimension(R.dimen.cardview_default_radius)
            elevation = resources.getDimension(R.dimen.cardview_default_elevation)
            setCardBackgroundColor(android.graphics.Color.WHITE)
        }
        
        // Create a container for our content
        val layout = LinearLayout(contextThemeWrapper).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        
        // Header
        val headerLayout = LinearLayout(contextThemeWrapper).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
            setBackgroundColor(resources.getColor(R.color.purple_500, null))
            setPadding(16, 16, 16, 16)
        }
        
        // Title in header
        val title = TextView(contextThemeWrapper).apply {
            text = "New Transaction"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        headerLayout.addView(title)
        
        // Close button in header
        val closeButton = ImageButton(contextThemeWrapper).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = null
            setColorFilter(android.graphics.Color.WHITE)
            setOnClickListener { closeOverlay(transaction.id) }
        }
        headerLayout.addView(closeButton)
        
        // Add header to layout
        layout.addView(headerLayout)
        
        // Info card
        val infoCard = com.google.android.material.card.MaterialCardView(contextThemeWrapper).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
            radius = resources.getDimension(R.dimen.cardview_default_radius) / 2
            strokeWidth = 1
            strokeColor = android.graphics.Color.parseColor("#E0E0E0")
        }
        
        val infoLayout = LinearLayout(contextThemeWrapper).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        
        // Transaction info
        val info = TextView(contextThemeWrapper).apply {
            val amountStr = String.format("₹%.2f", if (transaction.isExpense) -transaction.amount else transaction.amount)
            text = "Amount: $amountStr\nBank: ${transaction.bank}"
        }
        infoLayout.addView(info)
        infoCard.addView(infoLayout)
        layout.addView(infoCard)
        
        // Tag input layout
        val tagLabel = TextView(contextThemeWrapper).apply {
            text = "Primary Tag"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 8)
            }
        }
        layout.addView(tagLabel)
        
        // Tag input
        val tagInput = EditText(contextThemeWrapper).apply {
            setText(transaction.tag)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
            setPadding(12, 12, 12, 12)
            background = resources.getDrawable(android.R.drawable.edit_text, null)
        }
        layout.addView(tagInput)
        
        // Suggested tags label
        if (popularTags.isNotEmpty()) {
            val suggestedLabel = TextView(contextThemeWrapper).apply {
                text = "Suggested Tags"
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 8)
                }
            }
            layout.addView(suggestedLabel)
            
            // Horizontal layout for chips
            val chipContainer = LinearLayout(contextThemeWrapper).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 16)
                }
            }
            
                            // Add tag buttons as simple TextViews (not Chips for compatibility)
                popularTags.forEach { tag ->
                    val tagButton = TextView(contextThemeWrapper).apply {
                        text = tag
                        setPadding(16, 8, 16, 8)
                        setBackgroundResource(android.R.drawable.btn_default_small)
                        setTextColor(Color.parseColor("#333333"))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 0, 8, 0)
                        }
                        // Make sure click is visibly registered
                        isClickable = true
                        isFocusable = true
                        
                        setOnClickListener { view ->
                            // Provide visual feedback
                            view.alpha = 0.7f
                            view.postDelayed({
                                view.alpha = 1.0f
                            }, 150)
                            
                            // Save with this tag and close
                            tagInput.setText(tag)
                            // Force a save immediately
                            saveTag(transaction, tag, "")
                        }
                    }
                    chipContainer.addView(tagButton)
                }
            layout.addView(chipContainer)
        }
        
        // Save button with material styling
        val saveButton = Button(contextThemeWrapper).apply {
            text = "Save"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                saveTag(transaction, tagInput.text.toString(), "")
            }
        }
        layout.addView(saveButton)
        
        // Add the layout to the card
        card.addView(layout)
        
        return card
    }
    
    private fun saveTag(transaction: Transaction, primaryTag: String, customTagsText: String) {
        Log.d(TAG, "Saving tag '$primaryTag' for transaction ${transaction.id}")
        
        // Immediately disable save button to prevent double-clicks
        try {
            val saveButton = overlayView.findViewById<com.google.android.material.button.MaterialButton>(R.id.saveButton)
            saveButton?.isEnabled = false
            saveButton?.text = "Saving..."
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling save button: ${e.message}", e)
        }
        
        // Validate inputs
        if (primaryTag.isEmpty()) {
            Log.w(TAG, "Attempted to save empty primary tag")
            Toast.makeText(
                this@OverlayTaggingService,
                "Please enter a primary tag",
                Toast.LENGTH_SHORT
            ).show()
            
            // Re-enable save button
            try {
                val saveButton = overlayView.findViewById<com.google.android.material.button.MaterialButton>(R.id.saveButton)
                saveButton?.isEnabled = true
                saveButton?.text = "Save"
            } catch (e: Exception) {
                Log.e(TAG, "Error re-enabling save button: ${e.message}", e)
            }
            return
        }
        
        val customTags = if (customTagsText.isNotEmpty()) {
            customTagsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }

        // Show a loading message
        Toast.makeText(
            this@OverlayTaggingService,
            "Saving transaction...",
            Toast.LENGTH_SHORT
        ).show()

        // Create a flag to track if we've already closed the overlay
        var overlayClosed = false
        
        coroutineScope.launch {
            try {
                // Create updated transaction with new tags
                val updatedTransaction = transaction.copy(
                    tag = primaryTag,
                    customTags = customTags
                )
                
                // Update in database
                database.transactionDao().update(updatedTransaction)
                Log.d(TAG, "Successfully updated transaction ${transaction.id} with tag '$primaryTag'")
                
                withContext(Dispatchers.Main) {
                    try {
                        if (!overlayClosed) {
                            // Show success message
                            Toast.makeText(
                                this@OverlayTaggingService,
                                "Transaction tagged: $primaryTag",
                                Toast.LENGTH_SHORT
                            ).show()
                            
                            // Mark as closed before actually closing to prevent double-closing
                            overlayClosed = true
                            
                            // Close the overlay - this will trigger overlay removal and service stop
                            closeOverlay(transaction.id)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in UI update after saving: ${e.message}", e)
                        // Force close the overlay if there was an error showing the success message
                        if (!overlayClosed) {
                            overlayClosed = true
                            closeOverlay(transaction.id)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving tag: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    try {
                        if (!overlayClosed) {
                            Toast.makeText(
                                this@OverlayTaggingService,
                                "Failed to save tag: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                            
                            // Re-enable save button on error
                            try {
                                val saveButton = overlayView.findViewById<com.google.android.material.button.MaterialButton>(R.id.saveButton)
                                saveButton?.isEnabled = true
                                saveButton?.text = "Save"
                            } catch (e: Exception) {
                                Log.e(TAG, "Error re-enabling save button: ${e.message}", e)
                            }
                        }
                    } catch (innerE: Exception) {
                        Log.e(TAG, "Error showing error toast: ${innerE.message}", innerE)
                        // Force close the overlay if there was an error showing the error message
                        if (!overlayClosed) {
                            overlayClosed = true
                            closeOverlay(transaction.id)
                        }
                    }
                }
            }
        }
    }
    
    private fun createWindowParams(): WindowManager.LayoutParams {
        // Create layout parameters based on SDK version
        val params = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // For Android 8.0 (API 26) and above
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            )
        } else {
            // For older versions (below Android 8.0)
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) 
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT  // For Android 7.0-7.1 
                else 
                    WindowManager.LayoutParams.TYPE_TOAST,  // For Android 6.0 and below
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            )
        }
        
        // Center the overlay
        params.gravity = Gravity.CENTER
        
        // Set initial position
        params.x = 0
        params.y = 0
        
        // Set window title for debugging
        params.title = "MoneyTracker-OverlayTagging"
        
        return params
    }

    private fun setupDragListener() {
        val dragHandle = overlayView.findViewById<View>(R.id.dragHandle) ?: return
        Log.d(TAG, "Setting up drag listener")
        
        // Make inputs touchable by temporarily removing FLAG_NOT_FOCUSABLE
        val makeInputsTouchable = {
            try {
                val params = overlayView.layoutParams as WindowManager.LayoutParams
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                windowManager.updateViewLayout(overlayView, params)
                Log.d(TAG, "Made overlay inputs touchable")
            } catch (e: Exception) {
                Log.e(TAG, "Error making inputs touchable: ${e.message}", e)
            }
        }
        
        // Run this once to make inputs touchable on start
        makeInputsTouchable()
        
        // Create a touch listener that we can reuse
        val dragTouchListener = View.OnTouchListener { _, event ->
            val params = overlayView.layoutParams as WindowManager.LayoutParams

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Add FLAG_NOT_FOCUSABLE for drag operation
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    try {
                        windowManager.updateViewLayout(overlayView, params)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating overlay flags: ${e.message}")
                    }
                    
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(overlayView, params)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating overlay position: ${e.message}")
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Make inputs touchable again after drag
                    makeInputsTouchable()
                    true
                }
                else -> false
            }
        }
        
        // Set the touch listener on the drag handle
        dragHandle.setOnTouchListener(dragTouchListener)
        
        // Make the header/title bar also draggable as a fallback mechanism
        try {
            // The overlay itself is already a MaterialCardView, so we don't need to find it
            // Let's find the header view that contains the drag handle elements
            val headerView = overlayView.findViewById<View>(R.id.dragHandle)
            if (headerView != null) {
                // The header is already draggable as it contains the drag handle
                Log.d(TAG, "Header view is already draggable")
            }
            
            // Try to find other areas that should be draggable
            val transactionInfoText = overlayView.findViewById<TextView>(R.id.transactionInfoText)
            if (transactionInfoText != null) {
                Log.d(TAG, "Making transaction info text draggable")
                transactionInfoText.setOnTouchListener(dragTouchListener)
            }
            
            // If the overlay is a ViewGroup itself, make its background draggable
            // Use safe casting to avoid smart cast issues with mutable properties
            val overlayAsViewGroup = overlayView as? ViewGroup
            if (overlayAsViewGroup != null) {
                // Find the root view's first child which should be the LinearLayout containing everything
                if (overlayAsViewGroup.childCount > 0) {
                    val mainContainer = overlayAsViewGroup.getChildAt(0)
                    if (mainContainer is ViewGroup) {
                        Log.d(TAG, "Setting up touch listener on main container")
                        // Set a background touch listener, but don't override existing listeners for children
                        mainContainer.setOnTouchListener { v, event ->
                            // Only handle the event if it's not handled by a child view
                            // This ensures input fields and buttons still work
                            if (!v.hasOnClickListeners()) {
                                dragTouchListener.onTouch(v, event)
                            } else {
                                false
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up additional touch listeners: ${e.message}", e)
        }
    }

    private fun populateTransactionInfo(transaction: Transaction) {
        Log.d(TAG, "Populating transaction info")
        val infoText = overlayView.findViewById<TextView>(R.id.transactionInfoText) ?: return
        
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
    }

    private fun setupTagInputs(transaction: Transaction) {
        try {
            Log.d(TAG, "Setting up tag inputs for transaction ${transaction.id}")
            
            val tagInput = overlayView.findViewById<AutoCompleteTextView>(R.id.tagInput)
            if (tagInput == null) {
                Log.e(TAG, "tagInput view not found")
                return
            }
            
            val customTagsInput = overlayView.findViewById<EditText>(R.id.customTagsInput)
            val previewChipGroup = overlayView.findViewById<com.google.android.flexbox.FlexboxLayout>(R.id.previewChipGroup)
            val suggestedTagsContainer = overlayView.findViewById<com.google.android.flexbox.FlexboxLayout>(R.id.suggestedTagsContainer)
            val suggestedTagsLabel = overlayView.findViewById<TextView>(R.id.suggestedTagsLabel)
            val customTagsLabel = overlayView.findViewById<TextView>(R.id.customTagsLabel)
            val saveButton = overlayView.findViewById<com.google.android.material.button.MaterialButton>(R.id.saveButton)
            
            if (saveButton == null) {
                Log.e(TAG, "saveButton view not found")
                return
            }

            // Set up tag autocomplete
            if (allTags.isNotEmpty()) {
                Log.d(TAG, "Setting up autocomplete with ${allTags.size} tags")
                val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, allTags)
                tagInput.setAdapter(adapter)
                tagInput.threshold = 1  // Show suggestions after 1 character
            } else {
                Log.w(TAG, "No tags available for autocomplete")
            }
            
            // Set initial values
            tagInput.setText(transaction.tag)
            Log.d(TAG, "Set initial tag: ${transaction.tag}")

            // Join existing custom tags with commas if component exists
            if (customTagsInput != null && transaction.customTags.isNotEmpty()) {
                customTagsInput.setText(transaction.customTags.joinToString(", "))
                if (previewChipGroup != null && customTagsLabel != null) {
                    updateCustomTagsPreview(transaction.customTags, previewChipGroup, customTagsLabel)
                }
            }

            // Show suggested tags if components exist and tags available
            if (suggestedTagsContainer != null && popularTags.isNotEmpty()) {
                Log.d(TAG, "Setting up ${popularTags.size} suggested tags in view")
                
                // Make sure views are visible
                if (suggestedTagsLabel != null) {
                    suggestedTagsLabel.visibility = View.VISIBLE
                }
                suggestedTagsContainer.visibility = View.VISIBLE
                
                // Clear any existing chips
                suggestedTagsContainer.removeAllViews()
                
                // Add chips for each popular tag
                popularTags.forEach { tag ->
                    try {
                        val chip = createSuggestionChip(tag)
                        chip.setOnClickListener {
                            Log.d(TAG, "Suggestion chip clicked: $tag")
                            tagInput.setText(tag)
                            // Save immediately when a suggestion is selected
                            saveTag(transaction, tag, customTagsInput?.text?.toString() ?: "")
                        }
                        suggestedTagsContainer.addView(chip)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error creating chip for tag '$tag': ${e.message}", e)
                    }
                }
            } else {
                Log.d(TAG, "Not showing tag suggestions. Container: ${suggestedTagsContainer != null}, Tags: ${popularTags.isNotEmpty()}")
                if (suggestedTagsLabel != null) {
                    suggestedTagsLabel.visibility = View.GONE
                }
                if (suggestedTagsContainer != null) {
                    suggestedTagsContainer.visibility = View.GONE
                }
            }

            // Add text change listener for custom tags if component exists
            if (customTagsInput != null && previewChipGroup != null && customTagsLabel != null) {
                customTagsInput.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                    override fun afterTextChanged(s: Editable?) {
                        val tagText = s.toString()
                        if (tagText.isNotEmpty()) {
                            val tags = tagText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            updateCustomTagsPreview(tags, previewChipGroup, customTagsLabel)
                        } else {
                            previewChipGroup.removeAllViews()
                            customTagsLabel.visibility = View.GONE
                        }
                    }
                })
            }

            // Set a click listener for the save button with proper error handling
            saveButton.setOnClickListener {
                Log.d(TAG, "Save button clicked")
                try {
                    val primaryTag = tagInput.text.toString()
                    val customTagsText = customTagsInput?.text?.toString() ?: ""
                    
                    Log.d(TAG, "Saving with primary tag: '$primaryTag', custom tags: '$customTagsText'")
                    
                    if (primaryTag.isNotEmpty()) {
                        saveTag(transaction, primaryTag, customTagsText)
                    } else {
                        Toast.makeText(this@OverlayTaggingService, "Please enter a primary tag", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in save button click: ${e.message}", e)
                    Toast.makeText(this@OverlayTaggingService, "Error saving tag", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up tag inputs: ${e.message}", e)
        }
    }
    
    private fun createSuggestionChip(text: String): View {
        Log.d(TAG, "Creating suggestion chip for '$text'")
        try {
            // Create chip with proper themed context
            val contextThemeWrapper = android.view.ContextThemeWrapper(this, R.style.Theme_MoneyTracker_Overlay)
            val chip = com.google.android.material.chip.Chip(contextThemeWrapper)
            
            // Set basic properties
            chip.text = text
            chip.isCheckable = false
            chip.isClickable = true
            chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#EEEEEE"))
            chip.setTextColor(Color.parseColor("#333333"))
            
            // Set chip style - use standard Material chip style
            chip.chipStartPadding = dpToPx(8).toFloat()
            chip.chipEndPadding = dpToPx(8).toFloat()
            // Set the close icon visibility using correct method
            chip.setCloseIconVisible(false)
            
            // Set margins
            val layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams.setMargins(4, 4, 4, 4)
            chip.layoutParams = layoutParams
            
            return chip
        } catch (e: Exception) {
            Log.e(TAG, "Error creating chip: ${e.message}", e)
            
            // Fallback to TextView if chip creation fails
            val textView = TextView(this)
            textView.text = text
            textView.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            textView.setBackgroundResource(android.R.drawable.btn_default_small)
            textView.setTextColor(Color.parseColor("#333333"))
            
            val layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams.setMargins(4, 4, 4, 4)
            textView.layoutParams = layoutParams
            
            return textView
        }
    }
    
    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    private fun updateCustomTagsPreview(tags: List<String>, container: com.google.android.flexbox.FlexboxLayout, label: TextView) {
        try {
            if (tags.isEmpty()) {
                container.removeAllViews()
                label.visibility = View.GONE
                return
            }
            
            label.visibility = View.VISIBLE
            container.removeAllViews()
            
            // Create a themed context for Material components
            val contextThemeWrapper = android.view.ContextThemeWrapper(this, R.style.Theme_MoneyTracker_Overlay)
            
            tags.forEach { tag ->
                if (tag.isNotEmpty()) {
                    try {
                        // Create chip with proper theming
                        val chip = com.google.android.material.chip.Chip(contextThemeWrapper)
                        chip.text = tag
                        chip.isCheckable = false
                        chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#EEEEEE"))
                        chip.setTextColor(Color.parseColor("#333333"))
                        // Set the close icon visibility using correct method
                        chip.setCloseIconVisible(false)
                        
                        val layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        layoutParams.setMargins(4, 4, 4, 4)
                        
                        container.addView(chip, layoutParams)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error adding tag chip: ${e.message}")
                        
                        // Fallback to TextView if chip creation fails
                        try {
                            val textView = TextView(this)
                            textView.text = tag
                            textView.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
                            textView.setBackgroundResource(android.R.drawable.btn_default_small)
                            textView.setTextColor(Color.parseColor("#333333"))
                            
                            val layoutParams = ViewGroup.MarginLayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                            layoutParams.setMargins(4, 4, 4, 4)
                            
                            container.addView(textView, layoutParams)
                        } catch (innerE: Exception) {
                            Log.e(TAG, "Error creating fallback text view: ${innerE.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating tag preview: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        
        Log.d(TAG, "Service being destroyed")
        
        try {
            // Remove this transaction ID from active overlays
            if (currentTransactionId != -1L) {
                synchronized(activeOverlays) {
                    activeOverlays.remove(currentTransactionId)
                    Log.d(TAG, "Removed transaction $currentTransactionId from active overlays. Remaining: ${activeOverlays.size}")
                }
            }
            
            // Remove view from window manager if it's initialized
            if (::overlayView.isInitialized) {
                try {
                    // Use windowToken to check if the view is attached to a window
                    if (overlayView.windowToken != null) {
                        windowManager.removeView(overlayView)
                        Log.d(TAG, "Overlay view removed from window")
                    } else {
                        Log.d(TAG, "Overlay view wasn't attached to window - no need to remove")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing overlay view: ${e.message}", e)
                } finally {
                    // Clear any strong references
                    try {
                        val viewGroup = overlayView as? ViewGroup
                        viewGroup?.removeAllViews()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error clearing view references: ${e.message}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during service destruction: ${e.message}", e)
        }
    }
} 