package com.htn.breadboardar.ar

import android.content.Context
import android.graphics.PointF
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import android.view.Surface
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.Coordinates2d
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.DeadlineExceededException
import com.google.ar.core.exceptions.NotTrackingException
import com.google.ar.core.exceptions.NotYetAvailableException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot
import kotlin.math.abs
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArCameraPreview @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer {

    interface Listener {
        fun onCameraState(state: CameraState)
        fun onCalibrationHit(pose: com.google.ar.core.Pose)
        fun onSurfaceTargets(targets: List<PointF>)
        fun onBoardOutline(outline: List<PointF>)

        /** Bright rectangles found in the camera image, as view-space quads. */
        fun onCandidateRectangles(rectangles: List<List<PointF>>)

        /** A world-anchored board frame, republished whenever ARCore adjusts the anchor. */
        fun onBoardCalibrated(calibration: BoardCalibration)

        /** The board was located and continuous tracking has started. */
        fun onBoardTracked(reprojectionErrorPx: Float, boardWidthMeters: Float)

        /** Whether a recent, verified camera-space board pose is available. */
        fun onBoardVisibility(visible: Boolean)

        /**
         * The visually corrected board pose in the current camera frame.
         * [physicalTranslation]/[physicalQuaternion] use image-camera axes.
         * [displayTranslation]/
         * [displayQuaternion] use the display-oriented camera frame that matches
         * [onCameraProjection], and must be used by the native renderer.
         *
         * [northAtNegativeY] is locked from the matched detection quad when the board
         * is first tracked. It tells the native 3-D layer which long edge was visually
         * higher on screen, so a model placed "north" of the outline cannot jump to
         * the other side as the phone moves.
         */
        fun onBoardPoseInCamera(
            physicalTranslation: FloatArray,
            physicalQuaternion: FloatArray,
            displayTranslation: FloatArray,
            displayQuaternion: FloatArray,
            northAtNegativeY: Boolean,
        )

        /** Projection matching ARCore's display-oriented camera background. */
        fun onCameraProjection(projection: FloatArray)

        /** Non-null while ARCore cannot track, explaining what the user should change. */
        fun onTrackingHint(message: String?)
        fun onArError(message: String)
    }

    var listener: Listener? = null

    private val arSession = AtomicReference<Session?>(null)
    private var backgroundRenderer: CameraBackgroundRenderer? = null
    private var cameraTextureId: Int? = null
    private var latestFrame: Frame? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var appliedDisplayRotation = -1
    private var lastCameraStateTimestampNs = 0L
    private var lastSurfaceTargetsTimestampNs = 0L
    private var lastBoardOutlineTimestampNs = 0L
    private var boardCalibration: BoardCalibration? = null
    private var calibrationActive = false
    private var lastTrackingHint: String? = NOT_YET_REPORTED
    private var nonTrackingSinceNs = 0L
    private var hintShownAtNs = 0L
    private val rectangleDetector = RectangleDetector()
    private val detectionExecutor = Executors.newSingleThreadExecutor()
    private var rectangleDetectionActive = false
    private var detectionInFlight = false
    private var lastRectangleTimestampNs = 0L
    private var lumaCopy = ByteArray(0)
    private var candidateImageCorners: List<FloatArray> = emptyList()
    private var candidateMeasuredCorners: List<Boolean> = emptyList()
    private var trackedCentroid: PointF? = null
    private var solveAttemptsLeft = 0
    private var solveStartedAtNs = 0L
    private var bestSolutionCameraPose: PlanarPoseSolver.Result? = null
    /** Camera-to-world pose captured with the source image for the current best solve. */
    private var bestPhysicalCameraWorldPose: Pose? = null
    private var bestSolutionTimestampNs = 0L
    private var bestSolutionError = Float.MAX_VALUE
    private var boardTrackingActive = false
    private var lastOrderedCorners: FloatArray? = null
    private var bestNorthAtNegativeY: Boolean? = null
    private var lastPublishedPoseTimestampNs = 0L
    private var boardVisible = false
    private val visualTracker = VisualBoardTracker()
    /** Camera-to-world pose belonging to the asynchronously detected image. */
    private var detectionPhysicalCameraWorldPose: Pose? = null
    private var detectionIntrinsicsFocal: FloatArray? = null
    private var detectionIntrinsicsPrincipal: FloatArray? = null
    private var detectionCameraWasTracking = false
    private var detectionTimestampNs = 0L
    /** Invalidates asynchronous detections when the learner resets/reselects. */
    private val calibrationGeneration = AtomicInteger()
    /** State on the GL thread belongs to this generation until the reset event executes. */
    private var activeGeneration = 0
    private var lastPoseLogTimestampNs = 0L
    /**
     * The rectangle has no real-world compass direction.  This is deliberately a
     * screen-relative convention: on first lock, "north" is the side above the
     * outline on the phone display, and it remains fixed until reset.
     */
    private var northAtNegativeY: Boolean? = null
    private var boardGeometry = BoardGeometry.STANDARD

    /**
     * A pose solve before it is committed to the tracker.  Keeping this immutable is
     * important: a rejected rectangle must not replace [lastOrderedCorners], because
     * that history is what prevents the board pose from flipping end-for-end.
     */
    private data class CandidateSolve(
        val index: Int,
        val orderedCorners: FloatArray,
        val result: PlanarPoseSolver.Result,
        val physicalCameraWorld: Pose?,
        val reprojectionErrorPx: Float,
        val centroid: PointF,
        val geometry: BoardGeometry,
    )

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun attachSession(session: Session) {
        arSession.set(session)
        queueEvent { configureCameraTexture(session) }
    }

    fun requestCalibrationTap(x: Float, y: Float) {
        queueEvent {
            val frame = latestFrame ?: return@queueEvent
            // Do not pre-check trackingState: it flips between TRACKING and PAUSED
            // every few frames on some devices, so gating on it rejected roughly
            // half of all valid taps. Attempt the hit test and only fall back when
            // ARCore itself refuses, which also keeps NotTrackingException from
            // taking down the GL thread.
            val hit = try {
                frame.hitTest(x, y).firstOrNull(::isUsableHit)
            } catch (_: NotTrackingException) {
                postError("AR tracking is still starting. Move the phone slowly over the board, then tap again.")
                return@queueEvent
            }
            if (hit == null) {
                postError("Point the camera at the breadboard and tap a detected surface.")
            } else {
                postCalibration { listener?.onCalibrationHit(hit.hitPose) }
            }
        }
    }

    /** Surface sampling is only useful while the learner is picking reference points. */
    fun setCalibrationActive(active: Boolean) {
        queueEvent { calibrationActive = active }
    }

    /** Starts or stops the search for bright rectangles in the camera image. */
    fun setRectangleDetectionActive(active: Boolean) {
        queueEvent {
            rectangleDetectionActive = active
            if (!active) postCalibration { listener?.onCandidateRectangles(emptyList()) }
        }
    }

    /** Releases the detection thread. Call from the owning activity's onDestroy. */
    fun release() {
        calibrationGeneration.incrementAndGet()
        detectionExecutor.shutdownNow()
    }

    fun setBoardOutline(calibration: BoardCalibration) {
        queueEvent { boardCalibration = calibration }
    }

    fun clearBoardOutline() {
        val generation = calibrationGeneration.incrementAndGet()
        queueEvent {
            activeGeneration = generation
            boardCalibration = null
            trackedCentroid = null
            solveAttemptsLeft = 0
            bestSolutionCameraPose = null
            bestPhysicalCameraWorldPose = null
            bestSolutionError = Float.MAX_VALUE
            boardTrackingActive = false
            boardGeometry = BoardGeometry.STANDARD
            lastOrderedCorners = null
            bestNorthAtNegativeY = null
            lastPublishedPoseTimestampNs = 0L
            boardVisible = false
            northAtNegativeY = null
            detectionPhysicalCameraWorldPose = null
            visualTracker.reset()
        }
    }

    /**
     * Selects a board for continuous image-based pose estimation. The calibrated
     * camera intrinsics remain usable even while world tracking is paused.
     */
    fun selectRectangle(index: Int) {
        val generation = calibrationGeneration.incrementAndGet()
        queueEvent {
            activeGeneration = generation
            val corners = candidateImageCorners.getOrNull(index) ?: return@queueEvent
            trackedCentroid = centroidOf(corners)
            solveAttemptsLeft = SOLVE_ATTEMPTS
            solveStartedAtNs = System.nanoTime()
            bestSolutionCameraPose = null
            bestPhysicalCameraWorldPose = null
            bestSolutionError = Float.MAX_VALUE
            boardTrackingActive = false
            boardGeometry = BoardGeometry.STANDARD
            lastOrderedCorners = null
            bestNorthAtNegativeY = null
            boardVisible = false
            lastPublishedPoseTimestampNs = 0L
            northAtNegativeY = null
            detectionPhysicalCameraWorldPose = null
            visualTracker.reset()
            // Detection deliberately keeps running: a single pass gives a noisy aspect
            // ratio, so we solve on several and keep the best fit.
            rectangleDetectionActive = true
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer = CameraBackgroundRenderer()
        cameraTextureId = backgroundRenderer!!.createOnGlThread()
        arSession.get()?.let(::configureCameraTexture)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)
        appliedDisplayRotation = -1
        arSession.get()?.let(::updateDisplayGeometry)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = arSession.get() ?: return
        updateDisplayGeometry(session)
        val frame = try {
            session.update()
        } catch (_: CameraNotAvailableException) {
            postError("Camera is unavailable. Close other camera apps and reconnect.")
            return
        }

        latestFrame = frame
        // Anything below runs on the GLSurfaceView render thread, where an
        // uncaught exception kills the process. Surface problems to the UI
        // instead so the viewer can recover during the demo.
        try {
            backgroundRenderer?.draw(frame)
            val camera = frame.camera
            val isTracking = camera.trackingState == TrackingState.TRACKING

            updateTrackingHint(camera, frame.timestamp)

            if (calibrationActive &&
                frame.timestamp - lastSurfaceTargetsTimestampNs >= SURFACE_TARGET_INTERVAL_NS
            ) {
                lastSurfaceTargetsTimestampNs = frame.timestamp
                detectedSurfaceTargets(frame)?.let { targets ->
                    postCalibration { listener?.onSurfaceTargets(targets) }
                }
            }
            boardCalibration?.let { calibration ->
                if (isTracking && frame.timestamp - lastBoardOutlineTimestampNs >= BOARD_OUTLINE_INTERVAL_NS) {
                    lastBoardOutlineTimestampNs = frame.timestamp
                    val outline = projectBoardOutline(camera, calibration)
                    postCalibration { listener?.onBoardOutline(outline) }
                }
            }
            if (!boardTrackingActive && trackedCentroid != null &&
                System.nanoTime() - solveStartedAtNs > CALIBRATION_TIMEOUT_NS
            ) {
                solveAttemptsLeft = 0
                bestSolutionCameraPose = null
                trackedCentroid = null
                rectangleDetectionActive = false
                postError("Calibration timed out. Move slowly over the surface, then tap Calibrate to try again.")
            }
            // Images correct the board on every detection. A world map reporting
            // TRACKING can still drift metres on this device, so it cannot own the
            // board position; only bounded, visually verified motion is interpolated.
            if (!boardTrackingActive && solveAttemptsLeft == 0 && bestSolutionCameraPose != null) {
                finishBoardSolve()
            }
            if (boardTrackingActive) {
                val posePublished = publishVisualBoard(frame)
                if (posePublished) {
                    lastPublishedPoseTimestampNs = frame.timestamp
                    if (!boardVisible) {
                        boardVisible = true
                        postCalibration { listener?.onBoardVisibility(true) }
                    }
                } else if (boardVisible && frame.timestamp - lastPublishedPoseTimestampNs > TRACKING_LOST_GRACE_NS) {
                    boardVisible = false
                    postCalibration {
                        listener?.onBoardOutline(emptyList())
                        listener?.onBoardVisibility(false)
                    }
                }
            }

            // Temporary drift instrumentation: with the phone held still these numbers
            // should barely change. If they wander, ARCore's world estimate is the
            // problem rather than anything downstream of it.
            if (frame.timestamp - lastPoseLogTimestampNs >= 1_000_000_000L) {
                lastPoseLogTimestampNs = frame.timestamp
                val t = camera.pose.translation
                android.util.Log.d(
                    "ArDrift",
                    "state=${camera.trackingState} cam=%.3f,%.3f,%.3f".format(t[0], t[1], t[2]),
                )
            }
            if (rectangleDetectionActive &&
                !detectionInFlight &&
                frame.timestamp - lastRectangleTimestampNs >=
                    (if (boardTrackingActive) VISUAL_TRACKING_INTERVAL_NS else RECTANGLE_INTERVAL_NS)
            ) {
                lastRectangleTimestampNs = frame.timestamp
                dispatchRectangleDetection(frame)
            }
            if (isTracking && frame.timestamp - lastCameraStateTimestampNs >= CAMERA_STATE_INTERVAL_NS) {
                lastCameraStateTimestampNs = frame.timestamp
                postCameraState(frame, camera)
            }
        } catch (_: NotTrackingException) {
            // Tracking dropped between the check and the hit test; the next frame retries.
        } catch (error: Exception) {
            postError("AR frame error: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun configureCameraTexture(session: Session) {
        val textureId = cameraTextureId ?: return
        session.setCameraTextureNames(intArrayOf(textureId))
        updateDisplayGeometry(session)
    }

    /** Mirrors the current Android display orientation into ARCore before every frame update. */
    private fun updateDisplayGeometry(session: Session) {
        if (surfaceWidth == 0 || surfaceHeight == 0) return
        val rotation = display?.rotation ?: Surface.ROTATION_0
        if (rotation != appliedDisplayRotation) {
            session.setDisplayGeometry(rotation, surfaceWidth, surfaceHeight)
            appliedDisplayRotation = rotation
        }
    }

    private fun isUsableHit(hit: HitResult): Boolean = when (val trackable = hit.trackable) {
        is Plane -> trackable.isPoseInPolygon(hit.hitPose)
        is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
        else -> false
    }

    /**
     * Samples the view so the learner can see the screen regions ARCore can place on.
     * Uses the same predicate as a calibration tap, so a cyan dot always means
     * "tapping here works". Returns null when ARCore refuses to hit test, which
     * leaves the previous dots on screen instead of blinking them off.
     */
    private fun detectedSurfaceTargets(frame: Frame): List<PointF>? {
        if (surfaceWidth == 0 || surfaceHeight == 0) return null

        val targets = mutableListOf<PointF>()
        for (row in 1 until SURFACE_TARGET_ROWS) {
            for (column in 1 until SURFACE_TARGET_COLUMNS) {
                val x = surfaceWidth * column.toFloat() / SURFACE_TARGET_COLUMNS
                val y = surfaceHeight * row.toFloat() / SURFACE_TARGET_ROWS
                val hits = try {
                    frame.hitTest(x, y)
                } catch (_: NotTrackingException) {
                    return null
                }
                if (hits.any(::isUsableHit)) {
                    targets += PointF(x, y)
                }
            }
        }
        return targets
    }

    /**
     * ARCore on some devices alternates TRACKING and PAUSED every two or three
     * frames with no failure reason, which made the status line flicker at roughly
     * 15 Hz. The two directions are deliberately asymmetric:
     *
     * A single TRACKING frame is positive proof that tracking works, so the hint
     * clears immediately. PAUSED proves nothing on its own, so a complaint only
     * appears after ARCore has failed to track for [TRACKING_HINT_STABLE_NS]
     * straight. Debouncing both directions equally would freeze the hint, because
     * under flapping neither state ever holds long enough to win.
     */
    private fun updateTrackingHint(camera: Camera, timestampNs: Long) {
        if (camera.trackingState == TrackingState.TRACKING) {
            nonTrackingSinceNs = 0L
            reportTrackingHint(null, timestampNs)
            return
        }
        if (nonTrackingSinceNs == 0L) {
            nonTrackingSinceNs = timestampNs
            return
        }
        if (timestampNs - nonTrackingSinceNs >= TRACKING_HINT_STABLE_NS) {
            reportTrackingHint(trackingHint(camera), timestampNs)
        }
    }

    private fun reportTrackingHint(hint: String?, timestampNs: Long) {
        if (hint == lastTrackingHint) return
        // Hold a hint on screen long enough to read even if tracking recovers a
        // frame later. Every tracking frame retries this, so the clear is not lost.
        if (hint == null && timestampNs - hintShownAtNs < TRACKING_HINT_MIN_VISIBLE_NS) return
        lastTrackingHint = hint
        if (hint != null) hintShownAtNs = timestampNs
        postCalibration { listener?.onTrackingHint(hint) }
    }

    /** Turns ARCore's tracking failure reason into an instruction the learner can act on. */
    private fun trackingHint(camera: Camera): String {
        return when (camera.trackingFailureReason) {
            TrackingFailureReason.INSUFFICIENT_LIGHT ->
                "Too dark for AR tracking. Turn on more light."

            TrackingFailureReason.EXCESSIVE_MOTION ->
                "Phone is moving too fast. Slow down."

            TrackingFailureReason.INSUFFICIENT_FEATURES ->
                "Not enough detail to track. Aim at the breadboard and move the phone slowly side to side."

            TrackingFailureReason.CAMERA_UNAVAILABLE ->
                "Another app is using the camera."

            TrackingFailureReason.BAD_STATE ->
                "AR tracking is recovering. Hold the phone still."

            else -> "Starting AR tracking. Move the phone slowly over the board."
        }
    }

    private fun projectBoardOutline(camera: Camera, calibration: BoardCalibration): List<PointF> {
        if (surfaceWidth == 0 || surfaceHeight == 0) return emptyList()

        val corners = listOf(
            boardPoint(calibration, 0f, 0f),
            boardPoint(calibration, calibration.xExtentMeters, 0f),
            boardPoint(calibration, calibration.xExtentMeters, calibration.yExtentMeters),
            boardPoint(calibration, 0f, calibration.yExtentMeters),
        )
        val view = FloatArray(16)
        val projection = FloatArray(16)
        val modelViewProjection = FloatArray(16)
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(projection, 0, FILAMENT_NEAR_M, FILAMENT_FAR_M)
        Matrix.multiplyMM(modelViewProjection, 0, projection, 0, view, 0)

        return corners.mapNotNull { worldPoint ->
            val clip = FloatArray(4)
            Matrix.multiplyMV(
                clip,
                0,
                modelViewProjection,
                0,
                floatArrayOf(worldPoint[0], worldPoint[1], worldPoint[2], 1f),
                0,
            )
            if (clip[3] <= 0f) return@mapNotNull null
            val normalizedX = clip[0] / clip[3]
            val normalizedY = clip[1] / clip[3]
            PointF(
                (normalizedX + 1f) * surfaceWidth / 2f,
                (1f - normalizedY) * surfaceHeight / 2f,
            )
        }.takeIf { it.size == 4 } ?: emptyList()
    }

    private fun boardPoint(calibration: BoardCalibration, x: Float, y: Float): FloatArray = floatArrayOf(
        calibration.originMeters[0] + calibration.xAxis[0] * x + calibration.yAxis[0] * y,
        calibration.originMeters[1] + calibration.xAxis[1] * x + calibration.yAxis[1] * y,
        calibration.originMeters[2] + calibration.xAxis[2] * x + calibration.yAxis[2] * y,
    )

    /**
     * Runs one solve attempt against whichever current candidate best matches the
     * rectangle the learner picked, keeping the lowest-residual result. Called once
     * per detection pass until the attempt budget runs out.
     */
    private fun attemptBoardSolve() {
        val centroid = trackedCentroid ?: return
        if (candidateImageCorners.isEmpty()) return

        var bestIndex = -1
        var bestDistance = Float.MAX_VALUE
        candidateImageCorners.forEachIndexed { index, corners ->
            val c = centroidOf(corners)
            val distance = hypot(c.x - centroid.x, c.y - centroid.y)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        if (bestIndex < 0 || bestDistance > CANDIDATE_MATCH_RADIUS_PX) return

        solveAttemptsLeft--
        val solved = solveCandidate(bestIndex)
        if (solved != null) {
            trackedCentroid = solved.centroid
            if (solved.reprojectionErrorPx < bestSolutionError ||
                solved.reprojectionErrorPx <= MIN_REPROJECTION_TOLERANCE_PX
            ) {
                commitInitialSolve(solved)
            }
        }

        if (solveAttemptsLeft <= 0) finishBoardSolve()
    }

    private fun commitInitialSolve(solved: CandidateSolve) {
        val north = northOfImageCorners(solved.orderedCorners) ?: return
        val longSide = sideLength(solved.orderedCorners, 0)
        val shortSide = sideLength(solved.orderedCorners, 1)
        val observedAspect = if (shortSide > 0f) longSide / shortSide else 0f
        android.util.Log.d(
            "ArSolve",
            "index=${solved.index} aspect=$observedAspect modelAspect=${solved.geometry.lengthMeters / solved.geometry.widthMeters} " +
                "errorPx=${solved.reprojectionErrorPx} depthM=${solved.result.translation[2]}",
        )
        bestSolutionCameraPose = solved.result
        boardGeometry = solved.geometry
        bestPhysicalCameraWorldPose = solved.physicalCameraWorld
        bestSolutionTimestampNs = detectionTimestampNs
        bestNorthAtNegativeY = north
        bestSolutionError = solved.reprojectionErrorPx
        lastOrderedCorners = solved.orderedCorners.copyOf()
    }

    private fun finishBoardSolve() {
        val tolerance = maxOf(MIN_REPROJECTION_TOLERANCE_PX, REPROJECTION_STEPS * 2f)
        if (bestSolutionCameraPose == null || bestSolutionError > tolerance) {
            bestSolutionCameraPose = null
            trackedCentroid = null
            solveAttemptsLeft = 0
            lastOrderedCorners = null
            rectangleDetectionActive = true
            postCalibration { listener?.onCandidateRectangles(emptyList()) }
            postError("Edges are unclear. Start above the board with all four corners visible, then tap its rectangle again.")
            return
        }
        val solved = bestSolutionCameraPose ?: return
        val north = bestNorthAtNegativeY ?: return
        northAtNegativeY = north
        visualTracker.observe(solved, bestPhysicalCameraWorldPose, bestSolutionTimestampNs)
        boardTrackingActive = true
        // Keep measuring the physical board. ARCore TRACKING alone is not proof
        // that its world position is correct on this phone.
        rectangleDetectionActive = true
        calibrationActive = false
        lastPublishedPoseTimestampNs = latestFrame?.timestamp ?: 0L
        postCalibration {
            listener?.onCandidateRectangles(emptyList())
            listener?.onBoardTracked(bestSolutionError, boardGeometry.widthMeters)
        }
    }

    private fun trackVisualBoard() {
        val previous = lastOrderedCorners ?: return
        val previousCenter = centroidOf(previous)
        val previousArea = quadArea(previous)
        val candidates = candidateImageCorners.indices.mapNotNull { index ->
            val raw = candidateImageCorners[index]
            val center = centroidOf(raw)
            val distance = hypot(center.x - previousCenter.x, center.y - previousCenter.y)
            val areaRatio = quadArea(raw) / previousArea.coerceAtLeast(1f)
            if (distance > TRACK_MATCH_RADIUS_PX || areaRatio !in 0.45f..2.2f) return@mapNotNull null
            val solved = solveCandidate(index) ?: return@mapNotNull null
            if (solved.reprojectionErrorPx > MAX_TRACKED_REPROJECTION_PX) return@mapNotNull null
            // Nearby full-board contours win over a bright carpet patch or one
            // power rail. Pose fitting remains an independent quality gate.
            Pair(solved, distance + 40f * abs(areaRatio - 1f) + 2f * solved.reprojectionErrorPx)
        }
        val selected = candidates.minByOrNull { it.second }?.first ?: return
        if (visualTracker.observe(selected.result, selected.physicalCameraWorld, detectionTimestampNs) == null) return
        lastOrderedCorners = selected.orderedCorners.copyOf()
        trackedCentroid = selected.centroid
    }

    private fun publishVisualBoard(frame: Frame): Boolean {
        val camera = frame.camera
        val physical = visualTracker.poseAt(
            camera.pose.takeIf { camera.trackingState == TrackingState.TRACKING },
            frame.timestamp,
        ) ?: return false
        val north = northAtNegativeY ?: return false
        // Physical/display camera frames differ only by display rotation. Do not
        // subtract enormous corrupted world translations to obtain that rotation.
        val displayPose = CameraPoseFrames.boardInDisplayCamera(
            physical, CameraPoseFrames.physicalToDisplayCamera(camera.pose, camera.displayOrientedPose),
        )
        val intrinsics = camera.imageIntrinsics
        val imagePoints = PlanarPoseSolver.projectBoardCorners(
            boardModel(), intrinsics.focalLength, intrinsics.principalPoint, physical,
        ) ?: return false
        val viewPoints = FloatArray(8)
        frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, imagePoints, Coordinates2d.VIEW, viewPoints)
        val outline = List(4) { PointF(viewPoints[it * 2], viewPoints[it * 2 + 1]) }
        val projection = FloatArray(16)
        camera.getProjectionMatrix(projection, 0, FILAMENT_NEAR_M, FILAMENT_FAR_M)
        postCalibration {
            listener?.onCameraProjection(projection)
            listener?.onBoardOutline(outline)
            listener?.onBoardPoseInCamera(
                physical.translation, physical.quaternion,
                displayPose.translation, displayPose.quaternion, north,
            )
        }
        return true
    }

    private fun quadArea(corners: FloatArray): Float {
        var twiceArea = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            twiceArea += corners[i * 2] * corners[j * 2 + 1] - corners[j * 2] * corners[i * 2 + 1]
        }
        return abs(twiceArea) / 2f
    }

    /** Choose a side once, from the SAME corner order used for the initial pose. */
    private fun northOfImageCorners(imageCorners: FloatArray): Boolean? {
        val frame = latestFrame ?: return null
        val viewPoints = FloatArray(8)
        try {
            frame.transformCoordinates2d(
                Coordinates2d.IMAGE_PIXELS, imageCorners, Coordinates2d.VIEW, viewPoints,
            )
        } catch (_: Exception) {
            return null
        }
        return viewPoints[1] + viewPoints[3] <= viewPoints[5] + viewPoints[7]
    }

    private fun solveCandidate(index: Int): CandidateSolve? {
        if (candidateMeasuredCorners.getOrNull(index) != true) return null
        val raw = candidateImageCorners.getOrNull(index) ?: return null
        val cameraWorld = detectionPhysicalCameraWorldPose.takeIf { detectionCameraWasTracking }
        val fit = BoardGeometry.fit(
            raw,
            detectionIntrinsicsFocal ?: return null,
            detectionIntrinsicsPrincipal ?: return null,
            boardGeometry.takeIf { boardTrackingActive },
            lastOrderedCorners,
        ) ?: return null
        val estimate = fit.estimate
        return CandidateSolve(
            index, estimate.orderedCorners, estimate.result,
            cameraWorld, estimate.reprojectionErrorPx, centroidOf(raw), fit.geometry,
        )
    }

    private fun boardModel(): FloatArray = boardGeometry.corners()

    private fun centroidOf(corners: FloatArray): PointF {
        var x = 0f
        var y = 0f
        for (i in 0 until 4) {
            x += corners[i * 2]
            y += corners[i * 2 + 1]
        }
        return PointF(x / 4f, y / 4f)
    }

    private fun sideLength(quad: FloatArray, index: Int): Float {
        val a = index * 2
        val b = ((index + 1) % 4) * 2
        val dx = quad[b] - quad[a]
        val dy = quad[b + 1] - quad[a + 1]
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * Copies the luma plane on the GL thread, then detects off it on a worker.
     * The camera image must be acquired and closed while the frame is current, but
     * connected-component labelling is far too slow to run inside a draw call.
     */
    private fun dispatchRectangleDetection(frame: Frame) {
        val image = try {
            frame.acquireCameraImage()
        } catch (_: NotYetAvailableException) {
            return
        } catch (_: DeadlineExceededException) {
            return
        }

        val width: Int
        val height: Int
        val rowStride: Int
        try {
            width = image.width
            height = image.height
            val plane = image.planes[0]
            rowStride = plane.rowStride
            val buffer = plane.buffer
            val size = buffer.remaining()
            // Safe to reuse: the in-flight guard means no worker is reading this.
            if (lumaCopy.size < size) lumaCopy = ByteArray(size)
            buffer.get(lumaCopy, 0, size)
        } finally {
            image.close()
        }

        // Capture the pose belonging to THIS image. Detection is asynchronous, so by
        // the time the solve runs the latest frame has a newer pose, and composing
        // with that would bake in however far the phone moved in between.
        val camera = frame.camera
        detectionCameraWasTracking = camera.trackingState == TrackingState.TRACKING
        detectionPhysicalCameraWorldPose = camera.pose
        detectionTimestampNs = frame.timestamp
        detectionIntrinsicsFocal = camera.imageIntrinsics.focalLength
        detectionIntrinsicsPrincipal = camera.imageIntrinsics.principalPoint

        val luma = lumaCopy
        val generation = activeGeneration
        val lenient = boardTrackingActive
        detectionInFlight = true
        detectionExecutor.execute {
            val startedNs = System.nanoTime()
            val candidates = try {
                rectangleDetector.detect(luma, width, height, rowStride, lenient)
            } catch (_: Exception) {
                emptyList()
            }
            android.util.Log.d(
                "ArDetect",
                "detectMs=${(System.nanoTime() - startedNs) / 1_000_000} candidates=${candidates.size}",
            )
            queueEvent {
                detectionInFlight = false
                if (rectangleDetectionActive && generation == calibrationGeneration.get()) publishRectangles(candidates)
            }
        }
    }

    /**
     * Detection works in image pixels; the overlay draws in view pixels. ARCore's
     * own transform handles display rotation and camera crop, so the two never
     * disagree about where a corner is.
     */
    private fun publishRectangles(candidates: List<RectangleDetector.Candidate>) {
        val frame = latestFrame
        if (frame == null || candidates.isEmpty()) {
            postCalibration { listener?.onCandidateRectangles(emptyList()) }
            return
        }

        val input = FloatArray(candidates.size * 8)
        candidates.forEachIndexed { index, candidate ->
            candidate.selectionCorners.copyInto(input, index * 8)
        }
        val output = FloatArray(input.size)
        try {
            frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, input, Coordinates2d.VIEW, output)
        } catch (_: Exception) {
            return
        }

        // Keep the image-space corners too: the overlay needs view space to draw, but
        // the pose solve needs image space, and the two must stay index-aligned.
        candidateImageCorners = candidates.map { it.corners }
        candidateMeasuredCorners = candidates.map { it.hasMeasuredCorners }

        if (solveAttemptsLeft > 0) {
            attemptBoardSolve()
        } else if (boardTrackingActive) {
            trackVisualBoard()
        }

        // Once a board is being tracked the candidate boxes are just noise on screen.
        if (boardTrackingActive) return

        val rectangles = candidates.indices.map { index ->
            val base = index * 8
            (0 until 4).map { corner ->
                PointF(output[base + corner * 2], output[base + corner * 2 + 1])
            }
        }
        postCalibration { listener?.onCandidateRectangles(rectangles) }
    }

    private fun postCameraState(frame: Frame, camera: Camera) {
        val intrinsics = camera.imageIntrinsics
        val pose = camera.pose
        val state = CameraState(
            timestampNs = frame.timestamp,
            translationMeters = pose.translation.copyOf(),
            rotationQuaternion = pose.rotationQuaternion.copyOf(),
            focalLengthPixels = intrinsics.focalLength.copyOf(),
            principalPointPixels = intrinsics.principalPoint.copyOf(),
            imageDimensions = intrinsics.imageDimensions.copyOf(),
            viewportWidth = surfaceWidth,
            viewportHeight = surfaceHeight,
        )
        val projection = FloatArray(16)
        camera.getProjectionMatrix(projection, 0, FILAMENT_NEAR_M, FILAMENT_FAR_M)
        postCalibration {
            listener?.onCameraState(state)
            listener?.onCameraProjection(projection)
        }
    }

    /** Queued UI work from a reset/reselected calibration must never revive its model. */
    private fun postCalibration(action: () -> Unit) {
        val generation = activeGeneration
        post {
            if (generation == calibrationGeneration.get()) action()
        }
    }

    private fun postError(message: String) {
        postCalibration { listener?.onArError(message) }
    }

    private companion object {
        /** Distinct from null so the first "tracking is fine" result is still reported. */
        const val NOT_YET_REPORTED = "\u0000"
        const val CAMERA_STATE_INTERVAL_NS = 100_000_000L // 10 messages per second.
        /** Must match the transparent native renderer's Filament camera planes. */
        const val FILAMENT_NEAR_M = 0.02f
        const val FILAMENT_FAR_M = 20f
        const val TRACKING_HINT_STABLE_NS = 1_000_000_000L // Ignore anything shorter than a second.
        const val TRACKING_HINT_MIN_VISIBLE_NS = 1_500_000_000L
        const val VISUAL_TRACKING_INTERVAL_NS = 33_000_000L
        const val TRACK_MATCH_RADIUS_PX = 200f
        const val MAX_TRACKED_REPROJECTION_PX = 18f
        const val RECTANGLE_INTERVAL_NS = 300_000_000L // Roughly three detections per second.

        /** A worse fit than this is not a rectangle we can trust a pose from. */
        const val REPROJECTION_STEPS = 4f
        const val MIN_REPROJECTION_TOLERANCE_PX = 8f

        /** Detection passes to solve across before committing to the best fit. */
        const val SOLVE_ATTEMPTS = 6
        const val CALIBRATION_TIMEOUT_NS = 12_000_000_000L

        /** How far a candidate may sit from the tracked one and still be the same board. */
        const val CANDIDATE_MATCH_RADIUS_PX = 90f
        /** Additional grace after the last recent visual pose expires. */
        const val TRACKING_LOST_GRACE_NS = 300_000_000L
        const val SURFACE_TARGET_INTERVAL_NS = 250_000_000L
        const val BOARD_OUTLINE_INTERVAL_NS = 66_000_000L
        const val SURFACE_TARGET_COLUMNS = 8
        const val SURFACE_TARGET_ROWS = 12
    }
}
