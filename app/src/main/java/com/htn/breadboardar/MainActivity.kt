package com.htn.breadboardar

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.htn.breadboardar.ar.ArCameraPreview
import com.htn.breadboardar.ar.BoardCalibration
import com.htn.breadboardar.ar.CameraState
import com.htn.breadboardar.ar.ThreePointCalibrator
import com.htn.breadboardar.network.LaptopSocket
import com.htn.breadboardar.ui.CalibrationOverlayView

class MainActivity : AppCompatActivity(), ArCameraPreview.Listener, LaptopSocket.Listener {
    private lateinit var preview: ArCameraPreview
    private lateinit var overlay: CalibrationOverlayView
    private lateinit var statusText: TextView
    private lateinit var serverUrl: EditText
    private lateinit var sessionId: EditText
    private lateinit var connectButton: Button
    private lateinit var calibrateButton: Button
    private lateinit var resetButton: Button

    private val calibrator = ThreePointCalibrator()
    private val laptopSocket = LaptopSocket(this)
    private var arSession: Session? = null
    private var socketConnected = false
    private var activeCalibration: BoardCalibration? = null
    private var pendingCalibrationTap: PointF? = null
    private var userRequestedArInstall = true

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startAr()
        } else {
            showStatus(getString(R.string.camera_permission_required))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.ar_preview)
        overlay = findViewById(R.id.calibration_overlay)
        statusText = findViewById(R.id.status_text)
        serverUrl = findViewById(R.id.server_url)
        sessionId = findViewById(R.id.session_id)
        connectButton = findViewById(R.id.connect_button)
        calibrateButton = findViewById(R.id.calibrate_button)
        resetButton = findViewById(R.id.reset_button)

        preview.listener = this
        overlay.listener = object : CalibrationOverlayView.Listener {
            override fun onCalibrationTap(x: Float, y: Float) {
                pendingCalibrationTap = PointF(x, y)
                preview.requestCalibrationTap(x, y)
            }
        }

        connectButton.setOnClickListener(::connectToLaptop)
        calibrateButton.setOnClickListener(::beginCalibration)
        resetButton.setOnClickListener { resetCalibration("Calibration reset. Tap Calibrate to begin again.") }
        showStatus("Connect to the laptop, then calibrate the breadboard.")
    }

    override fun onResume() {
        super.onResume()
        startAr()
    }

    override fun onPause() {
        preview.onPause()
        arSession?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        laptopSocket.close()
        arSession?.close()
        super.onDestroy()
    }

    override fun onCameraState(state: CameraState) {
        if (activeCalibration != null && socketConnected) {
            laptopSocket.sendCameraState(state)
        }
    }

    override fun onCalibrationHit(pose: Pose) {
        try {
            pendingCalibrationTap?.let { overlay.addTapMarker(it.x, it.y) }
            pendingCalibrationTap = null
            val calibration = calibrator.addPoint(pose)
            val remaining = 3 - calibrator.pointCount
            if (calibration == null) {
                showStatus("Point saved. Tap $remaining more breadboard reference point${if (remaining == 1) "" else "s"}.")
            } else {
                activeCalibration = calibration
                overlay.endCalibration()
                laptopSocket.sendCalibration(calibration)
                showStatus("Calibrated. Waiting for Unity overlay frames.")
            }
        } catch (error: IllegalArgumentException) {
            pendingCalibrationTap = null
            resetCalibration(error.message ?: "Calibration failed. Try again with three separated points.")
        }
    }

    override fun onArError(message: String) {
        pendingCalibrationTap = null
        showStatus(message)
    }

    override fun onSocketStatus(message: String, connected: Boolean) {
        socketConnected = connected
        calibrateButton.isEnabled = connected && arSession != null
        resetButton.isEnabled = arSession != null
        // Keep the primary action compact enough to remain on one line alongside
        // the calibration controls on narrow phone screens. Tapping it always
        // opens a fresh WebSocket connection, whether this is the first attempt
        // or a reconnect.
        connectButton.text = "Connect"
        showStatus(message)
    }

    override fun onOverlayFrame(bitmap: Bitmap) {
        if (activeCalibration != null) {
            overlay.showRemoteOverlay(bitmap)
        }
    }

    private fun connectToLaptop(@Suppress("UNUSED_PARAMETER") view: View) {
        val url = serverUrl.text.toString().trim()
        val requestedSessionId = sessionId.text.toString().trim()
        if (url.isBlank() || requestedSessionId.isBlank()) {
            showStatus("Enter the laptop WebSocket URL and a session ID.")
            return
        }
        runCatching { laptopSocket.connect(url, requestedSessionId) }
            .onFailure { showStatus(it.message ?: "Could not start laptop connection.") }
    }

    private fun beginCalibration(@Suppress("UNUSED_PARAMETER") view: View) {
        if (!socketConnected) {
            showStatus("Connect to the laptop before calibrating.")
            return
        }
        activeCalibration = null
        pendingCalibrationTap = null
        calibrator.reset()
        overlay.beginCalibration()
        showStatus("Tap the board origin, then an X-direction point, then a Y-direction point.")
    }

    private fun resetCalibration(message: String) {
        activeCalibration = null
        calibrator.reset()
        overlay.resetCalibration()
        showStatus(message)
    }

    private fun startAr() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        try {
            if (arSession == null) {
                when (ArCoreApk.getInstance().requestInstall(this, userRequestedArInstall)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        userRequestedArInstall = false
                        return
                    }

                    ArCoreApk.InstallStatus.INSTALLED -> Unit
                }

                arSession = Session(this).also { session ->
                    session.configure(
                        Config(session).apply {
                            focusMode = Config.FocusMode.AUTO
                            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        },
                    )
                    preview.attachSession(session)
                }
            }

            arSession?.resume()
            preview.onResume()
            calibrateButton.isEnabled = socketConnected
            resetButton.isEnabled = true
        } catch (exception: UnavailableArcoreNotInstalledException) {
            showStatus("Google Play Services for AR must be installed.")
        } catch (exception: UnavailableApkTooOldException) {
            showStatus("Update Google Play Services for AR, then reopen the app.")
        } catch (exception: UnavailableSdkTooOldException) {
            showStatus("This AR viewer needs an update.")
        } catch (exception: UnavailableDeviceNotCompatibleException) {
            showStatus(getString(R.string.ar_unavailable))
        } catch (exception: CameraNotAvailableException) {
            showStatus("Camera is unavailable. Close other camera apps and reopen this app.")
        } catch (exception: Exception) {
            showStatus("Could not start AR: ${exception.message ?: "unknown error"}")
        }
    }

    private fun showStatus(message: String) {
        statusText.text = message
    }
}
