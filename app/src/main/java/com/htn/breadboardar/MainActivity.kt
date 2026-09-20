package com.htn.breadboardar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.htn.breadboardar.circuit.BoardGeometry
import com.htn.breadboardar.circuit.CircuitDefinition
import com.htn.breadboardar.network.ApiConfig
import com.htn.breadboardar.network.CircuitApiClient
import com.htn.breadboardar.ui.BreadboardView
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var board: BoardGeometry
    private lateinit var schematic: BreadboardView
    private lateinit var api: CircuitApiClient
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchArViewer() else MaterialAlertDialogBuilder(this)
            .setTitle("Camera access is needed for AR")
            .setMessage("Your schematic is still available. Allow Camera access in Android settings to place it beside your board.")
            .setNegativeButton("Keep reviewing", null)
            .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            .show()
    }
    private var circuit: CircuitDefinition? = null
    private var step = -1
    private var showingReview = false
    private var generating = false
    private val prefs by lazy { getSharedPreferences("circuit", MODE_PRIVATE) }
    private val prompt get() = findViewById<EditText>(R.id.prompt_input)
    private val status get() = findViewById<TextView>(R.id.circuit_status)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.app_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this) {
            if (showingReview) showReview(false) else finish()
        }
        board = BoardGeometry(assets.open("board-map.json").bufferedReader().use { it.readText() })
        schematic = findViewById<BreadboardView>(R.id.breadboard_view).also { it.board = board }
        // One-time upgrade from the former USB-only default. Preserve custom service origins.
        if (ApiConfig.baseUrl.startsWith("https://") && !prefs.getBoolean("hosted_api_migrated", false)) {
            val previous = prefs.getString("api_url", null)?.trimEnd('/')
            val edit = prefs.edit().putBoolean("hosted_api_migrated", true)
            if (previous in setOf("http://127.0.0.1:8000", "http://localhost:8000", "http://10.0.2.2:8000")) edit.remove("api_url")
            edit.apply()
        }
        api = CircuitApiClient(assets.open("default-kit.json").bufferedReader().use { it.readText() }) { prefs.getString("api_url", ApiConfig.baseUrl).orEmpty() }
        findViewById<Button>(R.id.settings_button).setOnClickListener { settings() }
        findViewById<Button>(R.id.generate_button).setOnClickListener { generate() }
        findViewById<Button>(R.id.example_led).setOnClickListener { setPrompt("Turn on a red LED") }
        findViewById<Button>(R.id.example_button).setOnClickListener { setPrompt("Turn on a red LED while I hold a button") }
        findViewById<Button>(R.id.example_uno).setOnClickListener { setPrompt("Blink an external red LED using an Arduino Uno") }
        findViewById<Button>(R.id.demo_button).setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle("Explore an example")
                .setItems(arrayOf("Red LED", "Button + LED", "Arduino Uno + LED")) { _, choice ->
                    val name = listOf("led", "button_led", "arduino_led")[choice]
                    accept(assets.open("$name.placement.json").bufferedReader().use { it.readText() })
                }.show()
        }
        findViewById<Button>(R.id.return_button).setOnClickListener { showReview(true) }
        findViewById<Button>(R.id.new_circuit_button).setOnClickListener { showReview(false) }
        findViewById<Button>(R.id.fit_button).setOnClickListener { schematic.fit() }
        findViewById<Button>(R.id.previous_step).setOnClickListener { step--; renderStep() }
        findViewById<Button>(R.id.next_step).setOnClickListener { step++; renderStep() }
        findViewById<Button>(R.id.preview_3d_button).setOnClickListener {
            circuit?.let { model ->
                File(filesDir, "ar-circuit.json").writeText(model.rawJson)
                startActivity(Intent(this, CircuitPreviewActivity::class.java))
            }
        }
        findViewById<Button>(R.id.show_ar_button).setOnClickListener { openAr() }
        findViewById<Button>(R.id.firmware_button).setOnClickListener { showFirmware() }
        prompt.setText(prefs.getString("prompt", ""))
        val last = File(filesDir, "last-circuit.json")
        if (last.isFile) runCatching { accept(last.readText(), persist = false) }
            .onFailure { status.text = "Your saved circuit needs to be regenerated." }
        if (savedInstanceState != null) {
            step = savedInstanceState.getInt("step", -1)
            showReview(savedInstanceState.getBoolean("review") && circuit != null)
            renderStep()
        }
    }
    private fun setPrompt(text: String) { prompt.setText(text); prompt.setSelection(text.length) }
    private fun showReview(review: Boolean) {
        showingReview = review
        findViewById<View>(R.id.design_panel).visibility = if (review) View.GONE else View.VISIBLE
        findViewById<View>(R.id.review_panel).visibility = if (review) View.VISIBLE else View.GONE
        findViewById<View>(R.id.return_button).visibility = if (circuit != null) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.progress_label).text = if (review) "01  DESIGN     /     02  REVIEW  •     /     03  AR" else "01  DESIGN  •     /     02  REVIEW     /     03  AR"
        findViewById<ScrollView>(R.id.content_scroll).post { findViewById<ScrollView>(R.id.content_scroll).smoothScrollTo(0, 0) }
    }
    private fun generate() {
        if (generating) return
        val text = prompt.text.toString().trim()
        if (text.length < 3) { prompt.error = "Describe your circuit first"; return }
        if (prefs.getString("api_url", ApiConfig.baseUrl).isNullOrBlank()) {
            status.text = "Set your hosted circuit service URL in Settings, or explore an offline example."
            settings(); return
        }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(prompt.windowToken, 0)
        prefs.edit().putString("prompt", text).apply()
        generating = true
        findViewById<Button>(R.id.generate_button).apply { isEnabled = false; this.text = "Designing your circuit…" }
        findViewById<View>(R.id.demo_button).isEnabled = false
        status.text = "Choosing parts and checking connections. This can take about a minute."
        api.generate(text, "android-${UUID.randomUUID()}") { result ->
            if (isDestroyed) return@generate
            generating = false
            findViewById<Button>(R.id.generate_button).apply { isEnabled = true; this.text = "Generate circuit" }
            findViewById<View>(R.id.demo_button).isEnabled = true
            when (result) {
                is CircuitApiClient.Result.Success -> runCatching { accept(result.circuit.rawJson) }
                    .onFailure { status.text = "${it.message} Your previous circuit is still saved." }
                is CircuitApiClient.Result.Failure -> status.text = result.userMessage + if (circuit != null) " Your previous circuit is still saved." else ""
            }
        }
    }
    private fun accept(json: String, persist: Boolean = true) {
        val next = CircuitDefinition.parse(json)
        board.validate(next)
        if (persist) {
            val file = androidx.core.util.AtomicFile(File(filesDir, "last-circuit.json"))
            val stream = file.startWrite()
            try { stream.write(json.toByteArray()); file.finishWrite(stream) } catch (error: Exception) { file.failWrite(stream); throw error }
        }
        circuit = next; step = -1
        schematic.circuit = next
        findViewById<TextView>(R.id.circuit_title).text = next.title
        findViewById<TextView>(R.id.circuit_summary).text = "${if (next.source == "fixture") "Saved example" else "Connections checked"} · ${next.components.size + next.externalDevices.size} parts · ${next.jumperWires.size} wires"
        findViewById<TextView>(R.id.parts_text).text = next.requiredParts.joinToString("\n") { it.replace('_', ' ').replace("220ohm", "220 Ω") }
        findViewById<View>(R.id.firmware_button).visibility = if (next.firmwareCode.isNullOrBlank()) View.GONE else View.VISIBLE
        status.text = ""
        renderStep(); showReview(true)
    }
    private fun renderStep() {
        val model = circuit ?: return
        step = step.coerceIn(-1, model.instructions.lastIndex)
        val instruction = model.instructions.getOrNull(step)
        findViewById<TextView>(R.id.step_label).text = if (instruction == null) "Build it, one connection at a time" else "Step ${step + 1} of ${model.instructions.size}"
        findViewById<TextView>(R.id.step_text).text = instruction?.text ?: "Start with power disconnected. Tap Next to highlight each part and its exact breadboard holes."
        schematic.highlightedIds = instruction?.componentIds?.toSet() ?: emptySet()
        findViewById<View>(R.id.previous_step).isEnabled = step >= 0
        findViewById<View>(R.id.next_step).isEnabled = step < model.instructions.lastIndex
    }
    private fun settings() {
        val field = EditText(this).apply {
            hint = "https://your-circuit-service.vercel.app"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(); setText(prefs.getString("api_url", ApiConfig.baseUrl))
            setPadding(32, 24, 32, 24)
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Circuit service")
            .setMessage("Use your hosted HTTPS circuit API. For a debug APK over USB, run scripts/run-android-usb.sh and use http://127.0.0.1:8000.")
            .setView(field).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val text = field.text.toString().trim().trimEnd('/')
            val url = text.toHttpUrlOrNull()
            if (url == null || (url.scheme != "https" && !BuildConfig.DEBUG) || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null || url.encodedPath != "/") {
                field.error = "Enter a service origin, such as https://example.vercel.app"
            } else { prefs.edit().putString("api_url", text).apply(); dialog.dismiss(); status.text = "Circuit service saved." }
        } }
        dialog.show()
    }
    private fun openAr() {
        val model = circuit ?: return
        board.validate(model)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchArViewer()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    private fun launchArViewer() {
        val model = circuit ?: return
        // Retain the selected circuit as private app data for the AR handoff. This
        // avoids Binder's payload limit. The native viewer validates this exact JSON
        // and assembles the bundled Unity meshes and wires on the phone.
        File(filesDir, "ar-circuit.json").writeText(model.rawJson)
        startActivity(Intent(this, ArViewerActivity::class.java))
    }
    private fun showFirmware() {
        val model = circuit ?: return
        val code = TextView(this).apply {
            text = "Upload from a computer using Arduino IDE. Disconnect USB before wiring; verify polarity before reconnecting.\n\n${model.firmwareCode}"
            setTextIsSelectable(true); setPadding(32, 24, 32, 24); typeface = android.graphics.Typeface.MONOSPACE
        }
        MaterialAlertDialogBuilder(this).setTitle("Arduino sketch").setView(ScrollView(this).apply { addView(code) })
            .setPositiveButton("Done", null).show()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("review", showingReview); outState.putInt("step", step); super.onSaveInstanceState(outState) }
    override fun onPause() { prefs.edit().putString("prompt", prompt.text.toString()).apply(); super.onPause() }
    override fun onDestroy() { api.cancel(); super.onDestroy() }
}
