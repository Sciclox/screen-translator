package com.translator.realtime

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import kotlin.math.abs
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

class TranslationService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    
    private var floatingBubble: FloatingControlView? = null
    private lateinit var overlayView: OverlayView
    
    private var translationsShowing = false
    
    // Capture resources
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    
    // ML Kit Components
    private var textRecognizer: TextRecognizer? = null
    private var translator: Translator? = null
    private var activeSourceLang = ""
    private var activeTargetLang = ""

    companion object {
        var isRunning = false
            private set
            
        private const val NOTIFICATION_ID = 2004
        private const val CHANNEL_ID = "translation_service_channel"
        
        const val ACTION_STOP_SERVICE = "com.translator.realtime.STOP_SERVICE"
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        overlayView = OverlayView(this)
        
        // Notify MainActivity that service started
        sendBroadcast(Intent(MainActivity.ACTION_SERVICE_STATE_CHANGED).apply {
            setPackage(packageName)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Start Foreground Service
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        try {
            // Obtain MediaProjection token
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))
            
            // Start persistent capture resources
            initializeCaptureResources()
        } catch (e: Exception) {
            Toast.makeText(this, "Error al iniciar captura: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        // Initialize translation resources based on preferences
        setupTranslationModels()

        // Create overlay components
        showOverlayViews()

        return START_REDELIVER_INTENT
    }

    private fun setupTranslationModels() {
        val prefs = getSharedPreferences("translator_prefs", Context.MODE_PRIVATE)
        val sourceLang = prefs.getString("source_lang", "ja") ?: "ja"
        val targetLang = prefs.getString("target_lang", "es") ?: "es"

        if (sourceLang == activeSourceLang && targetLang == activeTargetLang && translator != null) {
            return // Already initialized with correct languages
        }

        activeSourceLang = sourceLang
        activeTargetLang = targetLang

        // Close previous clients if they exist
        translator?.close()
        textRecognizer?.close()

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.fromLanguageTag(sourceLang)!!)
            .setTargetLanguage(TranslateLanguage.fromLanguageTag(targetLang)!!)
            .build()
        translator = Translation.getClient(options)

        // Select correct OCR engine (Japanese has specific ML model)
        textRecognizer = if (sourceLang == "ja") {
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        } else {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    private fun initializeCaptureResources() {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val density = displayMetrics.densityDpi

        // Persistent imageReader with buffer size 2
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "TranslatorCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, null
        )
    }

    private fun showOverlayViews() {
        // 1. Add Draggable Floating Control Bubble (Circular 56dp FAB)
        val density = resources.displayMetrics.density
        val bubbleSize = (56 * density).toInt()

        val bubbleParams = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else 
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 500
        }

        floatingBubble = FloatingControlView(this, windowManager, bubbleParams) {
            toggleTranslation()
        }
        windowManager.addView(floatingBubble, bubbleParams)

        // 2. Add full screen touch-transparent translation overlay view
        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else 
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(overlayView, overlayParams)
    }

    private fun toggleTranslation() {
        if (translationsShowing) {
            overlayView.clearTranslations()
            translationsShowing = false
            Toast.makeText(this, "Traducciones limpias", Toast.LENGTH_SHORT).show()
        } else {
            captureAndTranslate()
        }
    }

    private fun captureAndTranslate() {
        val reader = imageReader
        if (reader == null) {
            Toast.makeText(this, "Captura de pantalla no inicializada. Reinicia el servicio.", Toast.LENGTH_SHORT).show()
            return
        }

        // Dynamically update translation configuration if changed in settings
        setupTranslationModels()

        Toast.makeText(this, "Traduciendo pantalla...", Toast.LENGTH_SHORT).show()

        var image = reader.acquireLatestImage()
        if (image == null) {
            image = reader.acquireNextImage()
        }

        if (image != null) {
            try {
                val displayMetrics = resources.displayMetrics
                val width = displayMetrics.widthPixels
                val height = displayMetrics.heightPixels
                
                val bitmap = getCleanBitmap(image, width, height)
                image.close()

                // Perform OCR and Translate
                runOcrAndTranslate(bitmap)
            } catch (e: Exception) {
                Toast.makeText(this, "Error al procesar captura: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Provide a friendly reminder to interact/move screen if buffer is empty
            Toast.makeText(this, "Inténtalo de nuevo. Si persiste, mueve un poco la pantalla.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getCleanBitmap(image: android.media.Image, width: Int, height: Int): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, width, height)
        } else {
            bitmap
        }
    }

    private fun runOcrAndTranslate(bitmap: Bitmap) {
        val recognizer = textRecognizer ?: return
        val trans = translator ?: return

        val inputImage = InputImage.fromBitmap(bitmap, 0)
        
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                // 1. Filter text blocks to only translate the selected language (e.g. ignore English UI text on Japanese manga)
                val targetBlocks = visionText.textBlocks.filter { block ->
                    shouldTranslateBlock(block.text, activeSourceLang)
                }

                if (targetBlocks.isEmpty()) {
                    overlayView.clearTranslations()
                    translationsShowing = true // Set state so click can clear it if needed
                    return@addOnSuccessListener
                }

                overlayView.clearTranslations()
                translationsShowing = true

                val prefs = getSharedPreferences("translator_prefs", Context.MODE_PRIVATE)
                val apiKey = prefs.getString("gemini_api_key", "") ?: ""
                val density = resources.displayMetrics.density

                // 2. Group close text blocks (spatial clustering) to combine multi-line dialog bubbles into one translation unit
                val clusters = groupTextBlocks(targetBlocks, density)

                for (cluster in clusters) {
                    // 3. Sort blocks inside the cluster according to correct reading order
                    if (activeSourceLang == "ja") {
                        // Japanese manga vertical text layout is read right-to-left
                        cluster.textBlocks.sortByDescending { it.boundingBox?.right ?: 0 }
                    } else {
                        // Latin horizontal text layout is read top-to-bottom, left-to-right
                        cluster.textBlocks.sortWith(Comparator { b1, b2 ->
                            val r1 = b1.boundingBox ?: Rect()
                            val r2 = b2.boundingBox ?: Rect()
                            if (abs(r1.top - r2.top) < 15 * density) {
                                r1.left.compareTo(r2.left)
                            } else {
                                r1.top.compareTo(r2.top)
                            }
                        })
                    }

                    // 4. Combine dialogues into one unified text block
                    val combinedText = cluster.textBlocks.joinToString("\n") { it.text }
                    val cleanedText = cleanTextForTranslation(combinedText, activeSourceLang)
                    if (cleanedText.isBlank()) continue

                    // Translate on a background thread for high-quality online API with fallback
                    Thread {
                        var translatedText: String? = null
                        
                        // Try Gemini AI translation if API key is provided
                        if (apiKey.isNotEmpty()) {
                            translatedText = translateWithGemini(cleanedText, activeSourceLang, activeTargetLang, apiKey)
                        }
                        
                        // Fallback to Google Translate Online if Gemini fails or is not configured
                        if (translatedText == null) {
                            translatedText = translateOnline(cleanedText, activeSourceLang, activeTargetLang)
                        }
                        
                        if (translatedText != null) {
                            if (translationsShowing) {
                                overlayView.addTranslation(translatedText, cluster.rect)
                            }
                        } else {
                            // Last fallback: offline translation model
                            trans.translate(cleanedText)
                                .addOnSuccessListener { offlineTranslation ->
                                    if (translationsShowing) {
                                        overlayView.addTranslation(offlineTranslation, cluster.rect)
                                    }
                                }
                        }
                    }.start()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Reconocimiento fallido: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Canal del Traductor en Pantalla",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, TranslationService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Traductor de Pantalla Activo")
            .setContentText("Toca la burbuja flotante para traducir lo que ves.")
            .setSmallIcon(android.R.drawable.ic_menu_search) // Using system icon for compile safety
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener Servicio", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        
        // Remove views from WindowManager
        floatingBubble?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) { /* already removed or not attached */ }
        }
        
        try {
            windowManager.removeView(overlayView)
        } catch (e: Exception) { /* already removed or not attached */ }

        // Release capture resources
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null

        // Release MediaProjection
        mediaProjection?.stop()
        mediaProjection = null

        // Close ML Kit clients to prevent memory leaks
        translator?.close()
        textRecognizer?.close()

        // Notify MainActivity that service stopped
        sendBroadcast(Intent(MainActivity.ACTION_SERVICE_STATE_CHANGED).apply {
            setPackage(packageName)
        })
    }

    private fun translateWithGemini(text: String, source: String, target: String, apiKey: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val sourceName = getLanguageName(source)
            val targetName = getLanguageName(target)

            val requestBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "Eres un traductor experto de manga e historias del idioma $sourceName al $targetName. Traduce el siguiente texto de forma natural, manteniendo el tono coloquial y el contexto. Devuelve UNICAMENTE la traducción final, sin comentarios, introducciones ni comillas adicionales.\n\nTexto:\n$text")
                            })
                        })
                    })
                })
            }.toString()

            conn.outputStream.use { os ->
                os.write(requestBody.toByteArray(charset("UTF-8")))
            }

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(response)
                val candidates = jsonResponse.getJSONArray("candidates")
                val content = candidates.getJSONObject(0).getJSONObject("content")
                val parts = content.getJSONArray("parts")
                var result = parts.getJSONObject(0).getString("text").trim()
                
                // Strip unnecessary enclosing quotes if Gemini wraps the response in them
                if (result.startsWith("\"") && result.endsWith("\"")) {
                    result = result.substring(1, result.length - 1)
                }
                result
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun getLanguageName(code: String): String {
        return when(code) {
            "ja" -> "Japonés"
            "es" -> "Español"
            "en" -> "Inglés"
            "zh" -> "Chino"
            "ko" -> "Coreano"
            "fr" -> "Francés"
            "de" -> "Alemán"
            "pt" -> "Portugués"
            else -> code
        }
    }

    private fun translateOnline(text: String, source: String, target: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$source&tl=$target&dt=t&q=" + 
                    java.net.URLEncoder.encode(text, "UTF-8")
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(response)
                val sentencesArray = jsonArray.getJSONArray(0)
                val result = StringBuilder()
                for (i in 0 until sentencesArray.length()) {
                    val sentence = sentencesArray.getJSONArray(i)
                    result.append(sentence.getString(0))
                }
                result.toString()
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun cleanTextForTranslation(text: String, sourceLang: String): String {
        return if (sourceLang == "ja") {
            // For Japanese: remove all spaces, newlines, and carriage returns
            // to reconstruct sentences and translate full dialogues accurately
            text.replace("\\s".toRegex(), "")
                .replace("\n", "")
                .replace("\r", "")
        } else {
            // For English/Latin: replace newlines with a space, strip double spaces and trim
            text.replace("\r", "")
                .replace("\n", " ")
                .replace("\\s+".toRegex(), " ")
                .trim()
        }
    }

    // Spatial Clustering Classes & Methods for Speech Bubble Grouping
    private class TextCluster(
        val rect: Rect,
        val textBlocks: MutableList<com.google.mlkit.vision.text.Text.TextBlock>
    ) {
        fun merge(other: TextCluster): TextCluster {
            val mergedRect = Rect(rect).apply { union(other.rect) }
            val mergedBlocks = mutableListOf<com.google.mlkit.vision.text.Text.TextBlock>().apply {
                addAll(textBlocks)
                addAll(other.textBlocks)
            }
            return TextCluster(mergedRect, mergedBlocks)
        }
    }

    private fun groupTextBlocks(
        blocks: List<com.google.mlkit.vision.text.Text.TextBlock>,
        density: Float
    ): List<TextCluster> {
        val threshold = 25 * density // 25dp proximity threshold to merge blocks in same bubble (avoids chaining separate bubbles)
        val clusters = blocks.map { TextCluster(Rect(it.boundingBox ?: Rect()), mutableListOf(it)) }.toMutableList()
        
        var merged = true
        while (merged) {
            merged = false
            var i = 0
            while (i < clusters.size) {
                var j = i + 1
                while (j < clusters.size) {
                    if (areClustersClose(clusters[i], clusters[j], threshold)) {
                        val mergedCluster = clusters[i].merge(clusters[j])
                        clusters[i] = mergedCluster
                        clusters.removeAt(j)
                        merged = true
                        continue
                    }
                    j++
                }
                i++
            }
        }
        return clusters
    }

    private fun areClustersClose(c1: TextCluster, c2: TextCluster, threshold: Float): Boolean {
        val r1 = c1.rect
        val r2 = c2.rect

        val xDist = if (r1.right < r2.left) {
            r2.left - r1.right
        } else if (r2.right < r1.left) {
            r1.left - r2.right
        } else {
            0
        }

        val yDist = if (r1.bottom < r2.top) {
            r2.top - r1.bottom
        } else if (r2.bottom < r1.top) {
            r1.top - r2.bottom
        } else {
            0
        }

        return xDist < threshold && yDist < threshold
    }

    // Language Filtering helpers to only translate the selected language
    private fun shouldTranslateBlock(text: String, sourceLang: String): Boolean {
        if (sourceLang == "ja") {
            return containsJapaneseCharacters(text)
        }
        if (sourceLang == "zh") {
            return text.any { it.code in 0x4E00..0x9FFF }
        }
        if (sourceLang == "ko") {
            return text.any { it.code in 0xAC00..0xD7A3 || it.code in 0x3130..0x318F }
        }
        return true
    }

    private fun containsJapaneseCharacters(text: String): Boolean {
        for (char in text) {
            val codePoint = char.code
            if (codePoint in 0x3040..0x309F) return true // Hiragana
            if (codePoint in 0x30A0..0x30FF) return true // Katakana
            if (codePoint in 0x4E00..0x9FFF) return true // Kanji
        }
        return false
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
