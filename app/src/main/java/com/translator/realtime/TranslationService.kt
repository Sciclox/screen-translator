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

class TranslationService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    
    private var floatingBubble: FloatingControlView? = null
    private lateinit var overlayView: OverlayView
    
    private var translationsShowing = false
    
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
        val proj = mediaProjection
        if (proj == null) {
            Toast.makeText(this, "Captura de pantalla no disponible", Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }

        // Dynamically update translation configuration if changed in settings
        setupTranslationModels()

        Toast.makeText(this, "Traduciendo pantalla...", Toast.LENGTH_SHORT).show()

        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val density = displayMetrics.densityDpi

        // Capture screen image via ImageReader
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val virtualDisplay = proj.createVirtualDisplay(
            "TranslatorCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null, null
        )

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                try {
                    // Extract bitmap
                    val bitmap = getCleanBitmap(image, width, height)
                    image.close()

                    // Cleanup virtual display immediately
                    virtualDisplay.release()
                    imageReader.close()

                    // Perform OCR and Translate
                    runOcrAndTranslate(bitmap)
                } catch (e: Exception) {
                    Toast.makeText(this, "Error al capturar frame: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }, Handler(Looper.getMainLooper()))
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
                val blocks = visionText.textBlocks
                if (blocks.isEmpty()) {
                    Toast.makeText(this, "No se detectó texto en la pantalla", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                overlayView.clearTranslations()
                translationsShowing = true

                // Translate each text block and render it on screen overlay
                for (block in blocks) {
                    val originalText = block.text
                    val bounds = block.boundingBox ?: continue
                    val cleanedText = cleanTextForTranslation(originalText, activeSourceLang)
                    if (cleanedText.isBlank()) continue

                    trans.translate(cleanedText)
                        .addOnSuccessListener { translatedText ->
                            // Confirm overlays are still active before displaying
                            if (translationsShowing) {
                                overlayView.addTranslation(translatedText, bounds)
                            }
                        }
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

    override fun onBind(intent: Intent?): IBinder? = null
}
