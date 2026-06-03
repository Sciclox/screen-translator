package com.translator.realtime

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import kotlin.math.abs

class FloatingControlView(
    context: Context,
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams,
    private val onClick: () -> Unit
) : FrameLayout(context) {

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var touchStartTime = 0L

    init {
        // Create the bubble background programmatically: Purple-to-violet gradient
        val bubbleBackground = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            colors = intArrayOf(Color.parseColor("#7B2CBF"), Color.parseColor("#9D4EDD"))
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
            // Add a clean border
            setStroke(2 * resources.displayMetrics.density.toInt(), Color.parseColor("#FFFFFF"))
        }
        
        background = bubbleBackground
        
        // Add drop shadow
        elevation = 8f * resources.displayMetrics.density

        // Create the translation symbol text view
        val symbolTextView = TextView(context).apply {
            text = "文"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        
        addView(symbolTextView)

        // Set padding
        val paddingVal = (12 * resources.displayMetrics.density).toInt()
        setPadding(paddingVal, paddingVal, paddingVal, paddingVal)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                touchStartTime = System.currentTimeMillis()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                
                // Keep inside screen vertical bounds roughly
                val displayMetrics = resources.displayMetrics
                val screenHeight = displayMetrics.heightPixels
                if (layoutParams.y < 50) layoutParams.y = 50
                if (layoutParams.y > screenHeight - 200) layoutParams.y = screenHeight - 200

                windowManager.updateViewLayout(this, layoutParams)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val clickDuration = System.currentTimeMillis() - touchStartTime
                val deltaX = abs(event.rawX - initialTouchX)
                val deltaY = abs(event.rawY - initialTouchY)

                if (clickDuration < 200 && deltaX < 10 && deltaY < 10) {
                    // Tap event triggered
                    onClick()
                } else {
                    // Snap bubble to nearest screen edge (left or right)
                    snapToEdge()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun snapToEdge() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val bubbleWidth = width
        
        val targetX = if (layoutParams.x + bubbleWidth / 2 < screenWidth / 2) {
            0 // Snap left
        } else {
            screenWidth - bubbleWidth // Snap right
        }

        val animator = ValueAnimator.ofInt(layoutParams.x, targetX).apply {
            duration = 250
            addUpdateListener { animation ->
                layoutParams.x = animation.animatedValue as Int
                if (parent != null && ViewCompat.isAttachedToWindow(this@FloatingControlView)) {
                    try {
                        windowManager.updateViewLayout(this@FloatingControlView, layoutParams)
                    } catch (e: Exception) {
                        // View might have been detached during animation
                    }
                }
            }
        }
        animator.start()
    }
}
