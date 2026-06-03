package com.translator.realtime

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import android.content.SharedPreferences
import android.widget.TextView

class MainActivity : AppCompatActivity() {

    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var spinnerSource: AutoCompleteTextView
    private lateinit var spinnerTarget: AutoCompleteTextView
    private lateinit var modelStatusText: TextView
    private lateinit var modelProgressBar: LinearProgressIndicator
    private lateinit var btnDownloadModel: MaterialButton
    private lateinit var btnToggleService: MaterialButton

    private lateinit var prefs: SharedPreferences

    private val languages = listOf(
        LangItem("Japonés", "ja"),
        LangItem("Inglés", "en"),
        LangItem("Chino", "zh"),
        LangItem("Coreano", "ko"),
        LangItem("Español", "es"),
        LangItem("Francés", "fr"),
        LangItem("Alemán", "de"),
        LangItem("Portugués", "pt")
    )

    private val targetLanguages = listOf(
        LangItem("Español", "es"),
        LangItem("Inglés", "en"),
        LangItem("Portugués", "pt"),
        LangItem("Francés", "fr")
    )

    companion object {
        private const val REQ_OVERLAY_PERMISSION = 1001
        private const val REQ_POST_NOTIFICATIONS = 1002
        private const val REQ_SCREEN_CAPTURE = 1003
        
        const val ACTION_SERVICE_STATE_CHANGED = "com.translator.realtime.SERVICE_STATE_CHANGED"
    }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateServiceStatusUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState: Bundle?)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("translator_prefs", Context.MODE_PRIVATE)

        // Initialize UI Views
        statusIndicator = findViewById(R.id.status_indicator)
        statusText = findViewById(R.id.status_text)
        spinnerSource = findViewById(R.id.spinner_source_lang)
        spinnerTarget = findViewById(R.id.spinner_target_lang)
        modelStatusText = findViewById(R.id.model_status_text)
        modelProgressBar = findViewById(R.id.model_progress_bar)
        btnDownloadModel = findViewById(R.id.btn_download_model)
        btnToggleService = findViewById(R.id.btn_toggle_service)

        setupDropdowns()
        setupListeners()
        checkPermissions()
        checkModelsStatus()
        updateServiceStatusUI()

        // Register receiver for service status updates
        ContextCompat.registerReceiver(
            this,
            serviceReceiver,
            IntentFilter(ACTION_SERVICE_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(serviceReceiver)
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatusUI()
        checkModelsStatus()
    }

    private fun setupDropdowns() {
        // Source Spinner
        val sourceNames = languages.map { it.name }
        val sourceAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, sourceNames)
        spinnerSource.setAdapter(sourceAdapter)

        val savedSource = prefs.getString("source_lang", "ja") ?: "ja"
        val activeSourceItem = languages.firstOrNull { it.code == savedSource } ?: languages[0]
        spinnerSource.setText(activeSourceItem.name, false)

        spinnerSource.setOnItemClickListener { _, _, position, _ ->
            val selected = languages[position]
            prefs.edit().putString("source_lang", selected.code).apply()
            checkModelsStatus()
        }

        // Target Spinner
        val targetNames = targetLanguages.map { it.name }
        val targetAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, targetNames)
        spinnerTarget.setAdapter(targetAdapter)

        val savedTarget = prefs.getString("target_lang", "es") ?: "es"
        val activeTargetItem = targetLanguages.firstOrNull { it.code == savedTarget } ?: targetLanguages[0]
        spinnerTarget.setText(activeTargetItem.name, false)

        spinnerTarget.setOnItemClickListener { _, _, position, _ ->
            val selected = targetLanguages[position]
            prefs.edit().putString("target_lang", selected.code).apply()
            checkModelsStatus()
        }
    }

    private fun setupListeners() {
        btnDownloadModel.setOnClickListener {
            downloadActiveModels()
        }

        btnToggleService.setOnClickListener {
            if (TranslationService.isRunning) {
                stopTranslationService()
            } else {
                startTranslationProcess()
            }
        }
    }

    private fun checkPermissions() {
        // Overlay permission
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQ_OVERLAY_PERMISSION)
            Toast.makeText(this, "Por favor, otorga el permiso de superposición para mostrar traducciones", Toast.LENGTH_LONG).show()
        }

        // Notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) 
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQ_POST_NOTIFICATIONS
                )
            }
        }
    }

    private fun checkModelsStatus(onComplete: (Boolean) -> Unit = {}) {
        val sourceCode = prefs.getString("source_lang", "ja") ?: "ja"
        val targetCode = prefs.getString("target_lang", "es") ?: "es"

        // If source matches target, they are the same language - translation is trivial
        if (sourceCode == targetCode) {
            modelStatusText.text = "Idiomas idénticos. No se requiere descarga."
            btnDownloadModel.isEnabled = false
            onComplete(true)
            return
        }

        val modelManager = RemoteModelManager.getInstance()
        val sourceModel = TranslateRemoteModel.Builder(TranslateLanguage.fromLanguageTag(sourceCode)!!).build()
        val targetModel = TranslateRemoteModel.Builder(TranslateLanguage.fromLanguageTag(targetCode)!!).build()

        modelManager.isModelDownloaded(sourceModel).addOnSuccessListener { sourceOk ->
            modelManager.isModelDownloaded(targetModel).addOnSuccessListener { targetOk ->
                val bothOk = sourceOk && targetOk
                runOnUiThread {
                    if (bothOk) {
                        modelStatusText.text = getString(R.string.model_status_downloaded)
                        btnDownloadModel.isEnabled = false
                    } else {
                        modelStatusText.text = getString(R.string.model_status_not_downloaded)
                        btnDownloadModel.isEnabled = true
                    }
                    onComplete(bothOk)
                }
            }
        }
    }

    private fun downloadActiveModels() {
        val sourceCode = prefs.getString("source_lang", "ja") ?: "ja"
        val targetCode = prefs.getString("target_lang", "es") ?: "es"

        if (sourceCode == targetCode) return

        modelStatusText.text = "Descargando modelos de traducción..."
        modelProgressBar.visibility = View.VISIBLE
        modelProgressBar.isIndeterminate = true
        btnDownloadModel.isEnabled = false

        val modelManager = RemoteModelManager.getInstance()
        val sourceModel = TranslateRemoteModel.Builder(TranslateLanguage.fromLanguageTag(sourceCode)!!).build()
        val targetModel = TranslateRemoteModel.Builder(TranslateLanguage.fromLanguageTag(targetCode)!!).build()

        val conditions = DownloadConditions.Builder().build() // Allow cellular too to avoid blocks, user initiated

        modelManager.download(sourceModel, conditions)
            .addOnSuccessListener {
                modelManager.download(targetModel, conditions)
                    .addOnSuccessListener {
                        runOnUiThread {
                            modelProgressBar.visibility = View.GONE
                            modelStatusText.text = getString(R.string.model_status_downloaded)
                            Toast.makeText(this, "Modelos descargados con éxito", Toast.LENGTH_SHORT).show()
                            checkModelsStatus()
                        }
                    }
                    .addOnFailureListener { e ->
                        runOnUiThread {
                            modelProgressBar.visibility = View.GONE
                            modelStatusText.text = "Error al descargar modelo destino: ${e.message}"
                            btnDownloadModel.isEnabled = true
                        }
                    }
            }
            .addOnFailureListener { e ->
                runOnUiThread {
                    modelProgressBar.visibility = View.GONE
                    modelStatusText.text = "Error al descargar modelo origen: ${e.message}"
                    btnDownloadModel.isEnabled = true
                }
            }
    }

    private fun startTranslationProcess() {
        // Ensure models are downloaded
        checkModelsStatus { ready ->
            if (!ready) {
                Toast.makeText(this, "Por favor descarga los modelos de idioma primero", Toast.LENGTH_LONG).show()
                return@checkModelsStatus
            }

            // Ensure overlay permission is granted
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Se requiere el permiso de superposición para continuar", Toast.LENGTH_LONG).show()
                checkPermissions()
                return@checkModelsStatus
            }

            // Start screen capture permission prompt
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE)
        }
    }

    private fun stopTranslationService() {
        val serviceIntent = Intent(this, TranslationService::class.java)
        stopService(serviceIntent)
        updateServiceStatusUI()
    }

    private fun updateServiceStatusUI() {
        if (TranslationService.isRunning) {
            statusIndicator.setBackgroundColor(ContextCompat.getColor(this, R.color.status_active))
            statusText.text = getString(R.string.status_running)
            btnToggleService.text = getString(R.string.btn_stop)
            btnToggleService.setBackgroundColor(ContextCompat.getColor(this, R.color.status_inactive))
        } else {
            statusIndicator.setBackgroundColor(ContextCompat.getColor(this, R.color.status_inactive))
            statusText.text = getString(R.string.status_stopped)
            btnToggleService.text = getString(R.string.btn_start)
            btnToggleService.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY_PERMISSION) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Permiso de superposición otorgado", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permiso denegado", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == REQ_SCREEN_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val serviceIntent = Intent(this, TranslationService::class.java).apply {
                    putExtra("resultCode", resultCode)
                    putExtra("data", data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                
                // Close activity so user is ready to translate in other apps
                moveTaskToBack(true)
                Toast.makeText(this, "Servicio de traducción iniciado. Toca el botón flotante en pantalla.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Permiso de captura de pantalla denegado. No se puede iniciar la traducción.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private data class LangItem(val name: String, val code: String)
}
