package com.htn.breadboardar

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PointF
import android.os.Bundle
import android.view.View
import android.view.WindowManager
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
import com.htn.breadboardar.ar.CircuitOverlayFeature
import com.htn.breadboardar.ar.ThreePointCalibrator
import com.htn.breadboardar.circuit.BoardGeometry
import com.htn.breadboardar.circuit.CircuitDefinition
import com.htn.breadboardar.circuit.CircuitOverlayGeometry
import com.htn.breadboardar.network.GlbModelDownloader
import com.htn.breadboardar.render.CircuitOverlayBuilder
import com.htn.breadboardar.render.NativeBreadboardRenderer
import com.htn.breadboardar.ui.CalibrationOverlayView
import java.io.File

class ArViewerActivity : AppCompatActivity(), ArCameraPreview.Listener, GlbModelDownloader.Listener {
    private lateinit var preview: ArCameraPreview
    private lateinit var overlay: CalibrationOverlayView
    private lateinit var nativeBreadboardRenderer: NativeBreadboardRenderer
    private lateinit var statusText: TextView
    private lateinit var modelUrl: EditText
    private lateinit var loadModelButton: Button
    private lateinit var calibrateButton: Button
    private lateinit var resetButton: Button

    private val calibrator = ThreePointCalibrator()
    private val modelDownloader = GlbModelDownloader(this)
    private var arSession: Session? = null
    private var activeCalibration: BoardCalibration? = null
    private var pendingCalibrationTap: PointF? = null
    private var userRequestedArInstall = true
    private var baseStatus = ""
    private var trackingHint: String? = null
    private var selectedBoardCorners: List<PointF>? = null
    private var boardFitPx = 0

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
        setContentView(R.layout.activity_ar_viewer)
        // The viewer is watched continuously while the learner builds the circuit, so
        // the screen must not blank. This is also a stability requirement: letting the
        // device doze starves ARCore's IMU feed, which made its native motion-stereo
        // depth thread abort and take the process with it.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        preview = findViewById(R.id.ar_preview)
        overlay = findViewById(R.id.calibration_overlay)
        nativeBreadboardRenderer = NativeBreadboardRenderer(
            textureView = findViewById(R.id.model_overlay),
            assets = assets,
        )
        statusText = findViewById(R.id.status_text)
        modelUrl = findViewById(R.id.model_url)
        loadModelButton = findViewById(R.id.load_model_button)
        calibrateButton = findViewById(R.id.calibrate_button)
        resetButton = findViewById(R.id.reset_button)

        preview.listener = this
        overlay.listener = object : CalibrationOverlayView.Listener {
            override fun onCalibrationTap(x: Float, y: Float) {
                pendingCalibrationTap = PointF(x, y)
                preview.requestCalibrationTap(x, y)
            }

            override fun onRectangleSelected(index: Int, corners: List<PointF>) {
                selectBreadboardRectangle(index, corners)
            }
        }

        loadModelButton.setOnClickListener(::loadGlbFromUrl)
        calibrateButton.setOnClickListener(::beginCalibration)
        resetButton.setOnClickListener { resetCalibration("Calibration reset. Tap Calibrate to begin again.") }
        showStatus("Tap Calibrate to find your breadboard. Optionally load a .glb model from your backend.")
        loadCircuitOverlay()
    }

    override fun onResume() {
        super.onResume()
        nativeBreadboardRenderer.resume()
        startAr()
    }

    override fun onPause() {
        nativeBreadboardRenderer.pause()
        preview.onPause()
        arSession?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        nativeBreadboardRenderer.destroy()
        preview.release()
        modelDownloader.close()
        arSession?.close()
        super.onDestroy()
    }

    override fun onCameraState(@Suppress("UNUSED_PARAMETER") state: CameraState) = Unit

    override fun onCircuitOverlay(features: List<CircuitOverlayFeature>) {
        overlay.showCircuitOverlay(features)
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
                preview.setCalibrationActive(false)
                preview.setBoardOutline(calibration)
                showStatus("Board calibrated. Tap Reset to locate it again.")
            }
        } catch (error: IllegalArgumentException) {
            pendingCalibrationTap = null
            resetCalibration(error.message ?: "Calibration failed. Try again with three separated points.")
        }
    }

    override fun onArError(message: String) {
        pendingCalibrationTap = null
        overlay.clearSelectedRectangle()
        showStatus(message)
    }

    override fun onTrackingHint(message: String?) {
        trackingHint = message
        renderStatus()
    }

    override fun onSurfaceTargets(targets: List<PointF>) {
        overlay.showSurfaceTargets(targets)
    }

    override fun onBoardOutline(outline: List<PointF>) {
        overlay.showBoardOutline(outline, smoothDetection = false)
    }

    override fun onCandidateRectangles(rectangles: List<List<PointF>>) {
        overlay.showCandidateRectangles(rectangles)
    }

    private fun selectBreadboardRectangle(index: Int, corners: List<PointF>) {
        selectedBoardCorners = corners
        overlay.lockSelectedRectangle(corners)
        preview.setRectangleDetectionActive(false)
        preview.setCalibrationActive(false)
        preview.selectRectangle(index)
        showStatus("Working out the board position, hold the phone steady.")
    }

    override fun onBoardTracked(reprojectionErrorPx: Float, boardWidthMeters: Float) {
        nativeBreadboardRenderer.setPhysicalBoardWidth(boardWidthMeters)
        overlay.clearSelectedRectangle()
        overlay.endCalibration()
        boardFitPx = reprojectionErrorPx.toInt()
        showStatus("Board tracked. Move slowly around it to view the model in 3D; keep the board visible.")
    }

    override fun onBoardVisibility(visible: Boolean) {
        if (!visible) nativeBreadboardRenderer.hide()
        showStatus(
            if (visible) {
                "Board tracked. Move slowly around it to view the model in 3D; keep the board visible."
            } else {
                "Looking for the board again. Bring its full outline into view; tracking resumes automatically."
            },
        )
    }

    override fun onBoardPoseInCamera(
        @Suppress("UNUSED_PARAMETER") physicalTranslation: FloatArray,
        @Suppress("UNUSED_PARAMETER") physicalQuaternion: FloatArray,
        displayTranslation: FloatArray,
        displayQuaternion: FloatArray,
        northAtNegativeY: Boolean,
    ) {
        nativeBreadboardRenderer.showBoardNorthOfOutline(
            translation = displayTranslation,
            quaternion = displayQuaternion,
            northAtNegativeY = northAtNegativeY,
        )
    }

    override fun onCameraProjection(projection: FloatArray) {
        nativeBreadboardRenderer.updateArCameraProjection(projection)
    }

    override fun onBoardCalibrated(@Suppress("UNUSED_PARAMETER") calibration: BoardCalibration) {
        val isFirst = activeCalibration == null
        activeCalibration = calibration
        // The yellow outline is drawn from the anchor every frame, so if it stays on
        // the real board as you move, the pose is right.
        overlay.clearSelectedRectangle()
        if (isFirst) {
            showStatus("Board anchored. The yellow outline should stay on the breadboard as you move.")
        }
    }

    override fun onGlbDownloaded(buffer: java.nio.ByteBuffer, sourceUrl: String) {
        loadModelButton.isEnabled = true
        runCatching { nativeBreadboardRenderer.replaceModelGlb(buffer) }
            .onSuccess {
                showStatus("Loaded .glb model from ${sourceUrl.substringAfterLast('/')}. Calibrate to place it.")
            }
            .onFailure { error ->
                showStatus(error.message ?: "The downloaded GLB could not be rendered.")
            }
    }

    override fun onGlbDownloadFailed(message: String) {
        loadModelButton.isEnabled = true
        showStatus(message)
    }

    private fun loadGlbFromUrl(@Suppress("UNUSED_PARAMETER") view: View) {
        val url = modelUrl.text.toString().trim()
        if (url.isBlank()) {
            showStatus("Enter the direct HTTP(S) URL of a .glb file.")
            return
        }
        runCatching { modelDownloader.download(url) }
            .onSuccess {
                loadModelButton.isEnabled = false
                showStatus("Downloading .glb model…")
            }
            .onFailure { error ->
                showStatus(error.message ?: "Could not start the GLB download.")
            }
    }

    private fun beginCalibration(@Suppress("UNUSED_PARAMETER") view: View) {
        activeCalibration = null
        pendingCalibrationTap = null
        calibrator.reset()
        selectedBoardCorners = null
        preview.clearBoardOutline()
        nativeBreadboardRenderer.hide()
        preview.setCalibrationActive(true)
        preview.setRectangleDetectionActive(true)
        overlay.beginCalibration()
        showStatus("Start above the board. Tap its rectangular outline; after calibration it follows perspective.")
    }

    private fun resetCalibration(message: String) {
        activeCalibration = null
        calibrator.reset()
        selectedBoardCorners = null
        preview.clearBoardOutline()
        nativeBreadboardRenderer.hide()
        preview.setCalibrationActive(false)
        preview.setRectangleDetectionActive(false)
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
            calibrateButton.isEnabled = arSession != null
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
        baseStatus = message
        renderStatus()
    }

    /**
     * Renders the selected circuit in two layers: the 3-D copy beside the board gains
     * component meshes, and the scanned photo of the physical board itself is marked
     * with the same circuit at its hole positions. [MainActivity] saves the circuit as
     * private app data before launching this viewer; without it the copy stays a bare
     * breadboard. A broken circuit degrades to the bare board, never a crash, because
     * the physical-board tracking flow must keep working.
     */
    private fun loadCircuitOverlay() {
        val file = File(filesDir, "ar-circuit.json")
        if (!file.isFile) return
        runCatching {
            val circuit = CircuitDefinition.parse(file.readText())
            val board = BoardGeometry(assets.open("board-map.json").bufferedReader().use { it.readText() })
            board.validate(circuit)
            nativeBreadboardRenderer.setCircuitOverlay(CircuitOverlayBuilder(board, circuit).buildGlb())
            val marks = CircuitOverlayGeometry.of(circuit, board)
            preview.setCircuitOverlay(marks.points, marks.specs)
            circuit
        }.onSuccess { circuit ->
            showStatus(
                "${circuit.title} ready: ${circuit.components.size + circuit.externalDevices.size} parts, " +
                    "${circuit.jumperWires.size} wires beside your board and marked on it. Tap Calibrate to place it.",
            )
        }.onFailure { error ->
            showStatus("Could not render the circuit components: ${error.message}. Showing the bare breadboard.")
        }
    }

    /** The AR tracking hint sits above whatever step the user is on, never replacing it. */
    private fun renderStatus() {
        val hint = trackingHint
        statusText.text = if (hint.isNullOrBlank()) baseStatus else "$hint\n$baseStatus"
    }
}
