package com.translator.realtime

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.core.widget.TextViewCompat

class OverlayView(context: Context) : FrameLayout(context) {

    init {
        // Transparent container
        setBackgroundColor(Color.TRANSPARENT)
    }

    private fun markdownToHtml(markdown: String): String {
        // Convert bold **text** to <b>text</b>
        var html = markdown.replace("\\*\\*(.*?)\\*\\*".toRegex(), "<b>$1</b>")
        // Convert italic *text* to <i>text</i>
        html = html.replace("\\*(.*?)\\*".toRegex(), "<i>$1</i>")
        // Convert italic _text_ to <i>text</i> (just in case)
        html = html.replace("_(.*?)_".toRegex(), "<i>$1</i>")
        // Convert newlines to <br/>
        html = html.replace("\n", "<br/>")
        return html
    }

    /**
     * Adds a translation box over the specified coordinates.
     */
    fun addTranslation(translatedText: String, boundingBox: Rect) {
        val density = resources.displayMetrics.density

        // Enforce minimum dimensions so text remains readable
        val minWidth = (50 * density).toInt()
        val minHeight = (24 * density).toInt()
        val boxWidth = maxOf(boundingBox.width(), minWidth)
        val boxHeight = maxOf(boundingBox.height(), minHeight)

        val textView = TextView(context).apply {
            val htmlContent = markdownToHtml(translatedText)
            text = HtmlCompat.fromHtml(htmlContent, HtmlCompat.FROM_HTML_MODE_LEGACY)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            
            // Set programmatically a nice rounded-corner dark background to cover original text
            val bgShape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#E60B0914")) // Matches bg_main but semi-transparent (90%)
                cornerRadius = 6f * density // 6dp corner radius
                setStroke((1 * density).toInt(), Color.parseColor("#30FFFFFF")) // Thin border
            }
            background = bgShape

            // Set padding inside the box
            val padLeftRight = (4 * density).toInt()
            val padTopBottom = (2 * density).toInt()
            setPadding(padLeftRight, padTopBottom, padLeftRight, padTopBottom)

            // Auto text sizing so it fits inside the original layout coordinates
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this,
                8, // min text size in sp
                16, // max text size in sp
                1, // step granularity
                TypedValue.COMPLEX_UNIT_SP
            )
            
            maxLines = 5
        }

        // Layout parameters positioning text exactly over the source text bounding box
        val layoutParams = LayoutParams(boxWidth, boxHeight).apply {
            leftMargin = boundingBox.left
            topMargin = boundingBox.top
        }

        // Add view to container on UI Thread
        post {
            addView(textView, layoutParams)
        }
    }

    /**
     * Clears all translation boxes from screen.
     */
    fun clearTranslations() {
        post {
            removeAllViews()
        }
    }
}
