package com.htn.breadboardar.ar

import android.content.Context
import android.graphics.PointF
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import android.view.Surface
import com.google.ar.core.Anchor
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.hypot
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
        fun onBoardTracked(reprojectionErrorPx: Float)

        /**
         * Whether the board is currently being solved. False means the overlay has been
         * withdrawn because the board left the frame, rather than shown at a stale pose.
         */
        fun onBoardVisibility(visible: Boolean)

        /**
         * Board pose relative to the camera, recomputed from each image. Independent
         * of ARCore's world tracking, so usable even when that has diverged.
         */
        fun onBoardPoseInCamera(translation: FloatArray, quaternion: FloatArray)

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
    private var candidateStepPx: List<Int> = emptyList()
    private var trackedCentroid: PointF? = null
    private var solveAttemptsLeft = 0
    private var bestSolutionCameraPose: PlanarPoseSolver.Result? = null
    private var bestSolutionError = Float.MAX_VALUE
    private var boardTrackingActive = false
    private var smoothedBoardPose: PlanarPoseSolver.Result? = null
    private var lastOrderedCorners: FloatArray? = null
    private var lastFreshQuaternion: FloatArray? = null
    private var poseJumpStreak = 0
    private var lastSolveTimestampNs = 0L
    private var boardVisible = false
    private var centroidVelocity = PointF(0f, 0f)
    private var detectionCameraPose: Pose? = null
    private var detectionIntrinsicsFocal: FloatArray? = null
    private var detectionIntrinsicsPrincipal: FloatArray? = null
    private var lastPoseLogTimestampNs = 0L
    private var boardAnchor: Anchor? = null
    private var boardLengthMeters = 0f
    private var boardWidthMeters = 0f
    private var lastPublishedBoardPose: Pose? = null

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
                post { listener?.onCalibrationHit(hit.hitPose) }
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
            if (!active) post { listener?.onCandidateRectangles(emptyList()) }
        }
    }

    /** Releases the detection thread. Call from the owning activity's onDestroy. */
    fun release() {
        detectionExecutor.shutdownNow()
    }

    fun setBoardOutline(calibration: BoardCalibration) {
        queueEvent { boardCalibration = calibration }
    }

    fun clearBoardOutline() {
        queueEvent {
            boardCalibration = null
            trackedCentroid = null
            solveAttemptsLeft = 0
            bestSolutionCameraPose = null
            bestSolutionError = Float.MAX_VALUE
            boardTrackingActive = false
            smoothedBoardPose = null
            lastOrderedCorners = null
            lastFreshQuaternion = null
            centroidVelocity = PointF(0f, 0f)
            poseJumpStreak = 0
            lastPublishedBoardPose = null
            boardAnchor?.detach()
            boardAnchor = null
        }
    }

    /**
     * Solves the board pose from candidate [index] and anchors it in the world.
     * Deferred to a frame where ARCore is tracking, because the solve composes with
     * the camera pose and this device reports PAUSED on individual frames even while
     * tracking is healthy.
     */
    fun selectRectangle(index: Int) {
        queueEvent {
            val corners = candidateImageCorners.getOrNull(index) ?: return@queueEvent
            trackedCentroid = centroidOf(corners)
            solveAttemptsLeft = SOLVE_ATTEMPTS
            bestSolutionCameraPose = null
            bestSolutionError = Float.MAX_VALUE
            boardTrackingActive = false
            smoothedBoardPose = null
            lastOrderedCorners = null
            lastFreshQuaternion = null
            centroidVelocity = PointF(0f, 0f)
            poseJumpStreak = 0
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
                    post { listener?.onSurfaceTargets(targets) }
                }
            }
            boardCalibration?.let { calibration ->
                if (isTracking && frame.timestamp - lastBoardOutlineTimestampNs >= BOARD_OUTLINE_INTERVAL_NS) {
                    lastBoardOutlineTimestampNs = frame.timestamp
                    val outline = projectBoardOutline(camera, calibration)
                    post { listener?.onBoardOutline(outline) }
                }
            }
            if (isTracking) refreshAnchoredBoard()

            // Withdraw the outline once solves stop arriving. A short grace period rides
            // out the odd dropped pass; beyond that, drawing the last pose would assert a
            // position we no longer have evidence for.
            if (boardTrackingActive &&
                boardVisible &&
                frame.timestamp - lastSolveTimestampNs > BOARD_LOST_GRACE_NS
            ) {
                boardVisible = false
                post {
                    listener?.onBoardOutline(emptyList())
                    listener?.onBoardVisibility(false)
                }
            }

            // Temporary drift instrumentation: with the phone held still these numbers
            // should barely change. If they wander, ARCore's world estimate is the
            // problem rather than anything downstream of it.
            if (frame.timestamp - lastPoseLogTimestampNs >= 1_000_000_000L) {
                lastPoseLogTimestampNs = frame.timestamp
                val t = camera.pose.translation
                val anchorInfo = boardAnchor?.let { anchor ->
                    val a = anchor.pose.translation
                    val dx = a[0] - t[0]
                    val dy = a[1] - t[1]
                    val dz = a[2] - t[2]
                    " boardDistM=%.3f".format(kotlin.math.sqrt(dx * dx + dy * dy + dz * dz))
                } ?: ""
                android.util.Log.d(
                    "ArDrift",
                    "state=${camera.trackingState} cam=%.3f,%.3f,%.3f".format(t[0], t[1], t[2]) + anchorInfo,
                )
            }
            val detectionInterval =
                if (boardTrackingActive) TRACKING_INTERVAL_NS else RECTANGLE_INTERVAL_NS
            if (rectangleDetectionActive &&
                !detectionInFlight &&
                frame.timestamp - lastRectangleTimestampNs >= detectionInterval
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
        post { listener?.onTrackingHint(hint) }
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
        camera.getProjectionMatrix(projection, 0, 0.1f, 100f)
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
     * Turns four image corners into a world anchor.
     *
     * A planar quad plus intrinsics only determines pose up to scale, so the board's
     * real dimensions supply the metric. Which standard board it is gets chosen from
     * the observed aspect ratio rather than hard-coded, since 3:1 and 1.5:1 are far
     * enough apart to tell confidently even under perspective.
     */
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

        trackedCentroid = centroidOf(candidateImageCorners[bestIndex])
        solveAttemptsLeft--
        solveOnce(bestIndex)

        if (solveAttemptsLeft <= 0) finishBoardSolve()
    }

    /**
     * Re-solves the board from the newest detection pass while tracking. The board is
     * located afresh from each image, so this holds steady regardless of what ARCore's
     * world estimate is doing.
     */
    private fun trackBoard() {
        val centroid = trackedCentroid ?: return
        // Search around where the board is heading, not where it was. During a fast pan
        // the board can move most of a frame width between passes, and a window centred
        // on the previous position loses it even when detection worked perfectly.
        val predicted = PointF(
            centroid.x + centroidVelocity.x,
            centroid.y + centroidVelocity.y,
        )
        var bestIndex = -1
        var bestDistance = Float.MAX_VALUE
        candidateImageCorners.forEachIndexed { index, corners ->
            val c = centroidOf(corners)
            val distance = hypot(c.x - predicted.x, c.y - predicted.y)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        // Lost sight of it: keep the last good pose rather than snapping to something
        // else on screen, and let the outline go stale until the board is back.
        // A generous radius while tracking: at roughly 8 solves per second even a
        // moderate hand movement shifts the board well over a hundred image pixels
        // between passes, and a tight radius reads to the user as "it stopped tracking".
        if (bestIndex < 0 || bestDistance > TRACK_MATCH_RADIUS_PX) return

        bestSolutionError = Float.MAX_VALUE
        solveOnce(bestIndex)
        val fresh = bestSolutionCameraPose ?: return
        // Looser than at lock-on: blur costs corner precision, and a slightly worse fit
        // on a board we are already tracking beats dropping the overlay entirely.
        val tolerance = maxOf(MIN_REPROJECTION_TOLERANCE_PX, REPROJECTION_STEPS * 2f) *
            TRACKING_TOLERANCE_FACTOR
        if (bestSolutionError > tolerance) return

        // A nearly head-on planar target is poorly conditioned in tilt: two mirrored
        // poses reproject almost identically, so noise can flip between them. Ignore a
        // large jump unless several consecutive solves agree on it, which distinguishes
        // noise from the board genuinely being moved.
        // Compare each fresh solve against the previous *fresh* one rather than against
        // the smoothed estimate. Two successive solves that agree with each other are
        // real movement however fast it was; a lone outlier is the tilt flip. Judging
        // against the smoothed pose instead suppressed genuine quick motion.
        val previousFresh = lastFreshQuaternion
        val isOutlier = previousFresh != null &&
            angleBetween(previousFresh, fresh.quaternion) > MAX_POSE_JUMP_RAD
        lastFreshQuaternion = fresh.quaternion.copyOf()
        if (isOutlier) {
            poseJumpStreak++
            if (poseJumpStreak < POSE_JUMP_STREAK_TO_ACCEPT) return
            smoothedBoardPose = fresh
        }
        poseJumpStreak = 0

        val matched = centroidOf(candidateImageCorners[bestIndex])
        centroidVelocity = PointF(
            centroidVelocity.x * (1f - VELOCITY_SMOOTHING) +
                (matched.x - centroid.x) * VELOCITY_SMOOTHING,
            centroidVelocity.y * (1f - VELOCITY_SMOOTHING) +
                (matched.y - centroid.y) * VELOCITY_SMOOTHING,
        )
        trackedCentroid = matched
        lastSolveTimestampNs = latestFrame?.timestamp ?: lastSolveTimestampNs
        if (!boardVisible) {
            boardVisible = true
            post { listener?.onBoardVisibility(true) }
        }

        val smoothed = smoothBoardPose(fresh)
        smoothedBoardPose = smoothed
        publishTrackedOutline(smoothed)
        post { listener?.onBoardPoseInCamera(smoothed.translation.copyOf(), smoothed.quaternion.copyOf()) }
    }

    private fun finishBoardSolve() {
        val tolerance = maxOf(MIN_REPROJECTION_TOLERANCE_PX, REPROJECTION_STEPS * 2f)
        if (bestSolutionCameraPose == null || bestSolutionError > tolerance) {
            trackedCentroid = null
            rectangleDetectionActive = false
            post { listener?.onCandidateRectangles(emptyList()) }
            postError(
                "Could not fit a breadboard to that shape (best miss " +
                    "${if (bestSolutionError == Float.MAX_VALUE) "none" else bestSolutionError.toInt().toString()}px). " +
                    "Check the board dimensions, or try a straighter-on view.",
            )
            return
        }

        // Keep solving from here on. ARCore's world tracking diverges badly on this
        // device, so an anchored pose would slide away; a pose recomputed from each
        // image cannot drift because nothing is integrated over time.
        boardTrackingActive = true
        smoothedBoardPose = bestSolutionCameraPose
        boardLengthMeters = BOARD_LENGTH_M
        boardWidthMeters = BOARD_WIDTH_M
        post { listener?.onCandidateRectangles(emptyList()) }
        post { listener?.onBoardTracked(bestSolutionError) }
    }

    /**
     * Blends a fresh solve into the running estimate. Each solve carries independent
     * corner noise, so using them raw makes the outline twitch; smoothing trades a
     * little lag for a stable overlay.
     */
    private fun smoothBoardPose(fresh: PlanarPoseSolver.Result): PlanarPoseSolver.Result {
        val previous = smoothedBoardPose ?: return fresh
        val blend = POSE_SMOOTHING
        val translation = FloatArray(3) {
            previous.translation[it] * (1f - blend) + fresh.translation[it] * blend
        }

        // Quaternions are a double cover of rotation, so align signs before blending
        // or an equivalent rotation can average to something nonsensical.
        var dot = 0f
        for (i in 0 until 4) dot += previous.quaternion[i] * fresh.quaternion[i]
        val sign = if (dot < 0f) -1f else 1f
        val quaternion = FloatArray(4) {
            previous.quaternion[it] * (1f - blend) + fresh.quaternion[it] * sign * blend
        }
        var magnitude = 0f
        for (value in quaternion) magnitude += value * value
        magnitude = kotlin.math.sqrt(magnitude)
        if (magnitude < 1e-6f) return fresh
        for (i in 0 until 4) quaternion[i] /= magnitude

        return PlanarPoseSolver.Result(translation, quaternion)
    }

    /** Draws the outline by reprojecting the board model through the current pose. */
    private fun publishTrackedOutline(pose: PlanarPoseSolver.Result) {
        val focal = detectionIntrinsicsFocal ?: return
        val principal = detectionIntrinsicsPrincipal ?: return
        val model = boardModel()
        val imagePoints = PlanarPoseSolver.projectBoardCorners(model, focal, principal, pose) ?: return

        val viewPoints = FloatArray(imagePoints.size)
        val frame = latestFrame ?: return
        try {
            frame.transformCoordinates2d(
                Coordinates2d.IMAGE_PIXELS,
                imagePoints,
                Coordinates2d.VIEW,
                viewPoints,
            )
        } catch (_: Exception) {
            return
        }

        val outline = (0 until 4).map { PointF(viewPoints[it * 2], viewPoints[it * 2 + 1]) }
        post { listener?.onBoardOutline(outline) }
    }

    private fun boardModel(): FloatArray = floatArrayOf(
        0f, 0f,
        BOARD_LENGTH_M, 0f,
        BOARD_LENGTH_M, BOARD_WIDTH_M,
        0f, BOARD_WIDTH_M,
    )

    private fun solveOnce(index: Int) {
        val raw = candidateImageCorners.getOrNull(index) ?: return
        val stepPx = candidateStepPx.getOrElse(index) { 1 }.toFloat()
        val capturePose = detectionCameraPose ?: return
        val focal = detectionIntrinsicsFocal ?: return
        val principal = detectionIntrinsicsPrincipal ?: return

        val corners = orderedCorners(raw)
        val longSide = sideLength(corners, 0)
        val shortSide = sideLength(corners, 1)
        if (shortSide <= 0f) return
        val observedAspect = longSide / shortSide

        val lengthMeters = BOARD_LENGTH_M
        val widthMeters = BOARD_WIDTH_M

        val model = floatArrayOf(
            0f, 0f,
            lengthMeters, 0f,
            lengthMeters, widthMeters,
            0f, widthMeters,
        )
        val solution = PlanarPoseSolver.solve(
            corners,
            model,
            focal,
            principal,
        ) ?: run {
            postError("Could not work out the board's position. Move slightly and try again.")
            return
        }

        val error = PlanarPoseSolver.reprojectionErrorPx(
            corners,
            model,
            focal,
            principal,
            solution,
        )
        if (error >= bestSolutionError) return

        android.util.Log.d(
            "ArSolve",
            "step=$stepPx aspect=$observedAspect modelAspect=${lengthMeters / widthMeters} " +
                "errorPx=$error depthM=${solution.translation[2]}",
        )

        bestSolutionCameraPose = solution
        bestSolutionError = error
    }

    private fun centroidOf(corners: FloatArray): PointF {
        var x = 0f
        var y = 0f
        for (i in 0 until 4) {
            x += corners[i * 2]
            y += corners[i * 2 + 1]
        }
        return PointF(x / 4f, y / 4f)
    }

    /**
     * Rebuilds the board frame from the anchor every frame, so the outline follows
     * ARCore's drift corrections instead of being frozen at calibration time.
     */
    private fun refreshAnchoredBoard() {
        val anchor = boardAnchor ?: return
        if (anchor.trackingState != TrackingState.TRACKING) return

        val pose = anchor.pose
        val calibration = BoardCalibration(
            originMeters = pose.translation,
            xAxis = pose.xAxis,
            yAxis = pose.yAxis,
            zAxis = pose.zAxis,
            xExtentMeters = boardLengthMeters,
            yExtentMeters = boardWidthMeters,
        )
        boardCalibration = calibration

        if (hasMovedSincePublish(pose)) {
            lastPublishedBoardPose = pose
            post { listener?.onBoardCalibrated(calibration) }
        }
    }

    /** Avoids republishing the board frame on every single frame for no reason. */
    private fun hasMovedSincePublish(pose: Pose): Boolean {
        val previous = lastPublishedBoardPose ?: return true
        val dx = pose.tx() - previous.tx()
        val dy = pose.ty() - previous.ty()
        val dz = pose.tz() - previous.tz()
        if (dx * dx + dy * dy + dz * dz > ANCHOR_REPUBLISH_DISTANCE_M * ANCHOR_REPUBLISH_DISTANCE_M) {
            return true
        }
        val dot = pose.qx() * previous.qx() + pose.qy() * previous.qy() +
            pose.qz() * previous.qz() + pose.qw() * previous.qw()
        return abs(dot) < ANCHOR_REPUBLISH_ROTATION_DOT
    }

    /**
     * Puts the corners into the order the solver's board model expects: first edge
     * along the board's long axis, and a consistent winding so the recovered plane
     * normal faces the camera rather than away from it.
     */
    private fun orderedCorners(corners: FloatArray): FloatArray {
        val longFirst = if (sideLength(corners, 0) >= sideLength(corners, 1)) {
            corners.copyOf()
        } else {
            floatArrayOf(
                corners[2], corners[3],
                corners[4], corners[5],
                corners[6], corners[7],
                corners[0], corners[1],
            )
        }
        val wound = if (signedArea(longFirst) >= 0f) {
            longFirst
        } else {
            // Reverse while keeping a long edge first: 0,1,2,3 becomes 3,2,1,0.
            floatArrayOf(
                longFirst[6], longFirst[7],
                longFirst[4], longFirst[5],
                longFirst[2], longFirst[3],
                longFirst[0], longFirst[1],
            )
        }

        // "Long edge first with this winding" still admits two orderings, because a
        // rectangle has two long edges: corner 0 may be either end of the board. Both
        // are geometrically valid but they differ by 180 degrees, so picking freely
        // each frame makes the pose flip end over end. Stay with whichever matches the
        // previous frame's choice.
        val flipped = floatArrayOf(
            wound[4], wound[5],
            wound[6], wound[7],
            wound[0], wound[1],
            wound[2], wound[3],
        )
        val previous = lastOrderedCorners
        val chosen = if (previous == null) {
            wound
        } else {
            val keep = hypot(wound[0] - previous[0], wound[1] - previous[1])
            val swap = hypot(flipped[0] - previous[0], flipped[1] - previous[1])
            if (keep <= swap) wound else flipped
        }
        lastOrderedCorners = chosen
        return chosen
    }

    /** Angle between two quaternions in radians, ignoring their double cover. */
    private fun angleBetween(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in 0 until 4) dot += a[i] * b[i]
        return 2f * kotlin.math.acos(abs(dot).coerceAtMost(1f))
    }

    private fun signedArea(quad: FloatArray): Float {
        var total = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            total += quad[i * 2] * quad[j * 2 + 1] - quad[j * 2] * quad[i * 2 + 1]
        }
        return total / 2f
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
        detectionCameraPose = frame.camera.pose
        detectionIntrinsicsFocal = frame.camera.imageIntrinsics.focalLength
        detectionIntrinsicsPrincipal = frame.camera.imageIntrinsics.principalPoint

        val luma = lumaCopy
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
                if (rectangleDetectionActive) publishRectangles(candidates)
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
            post { listener?.onCandidateRectangles(emptyList()) }
            return
        }

        val input = FloatArray(candidates.size * 8)
        candidates.forEachIndexed { index, candidate ->
            candidate.corners.copyInto(input, index * 8)
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
        candidateStepPx = candidates.map { it.stepPx }

        if (solveAttemptsLeft > 0) {
            attemptBoardSolve()
        } else if (boardTrackingActive) {
            trackBoard()
        }

        // Once a board is being tracked the candidate boxes are just noise on screen.
        if (boardTrackingActive) return

        val rectangles = candidates.indices.map { index ->
            val base = index * 8
            (0 until 4).map { corner ->
                PointF(output[base + corner * 2], output[base + corner * 2 + 1])
            }
        }
        post { listener?.onCandidateRectangles(rectangles) }
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
        post { listener?.onCameraState(state) }
    }

    private fun postError(message: String) {
        post { listener?.onArError(message) }
    }

    private companion object {
        /** Distinct from null so the first "tracking is fine" result is still reported. */
        const val NOT_YET_REPORTED = "\u0000"
        const val CAMERA_STATE_INTERVAL_NS = 100_000_000L // 10 messages per second.
        const val TRACKING_HINT_STABLE_NS = 1_000_000_000L // Ignore anything shorter than a second.
        const val TRACKING_HINT_MIN_VISIBLE_NS = 1_500_000_000L
        const val RECTANGLE_INTERVAL_NS = 300_000_000L // Roughly three detections per second.

        /**
         * Outer dimensions of the demo breadboard, long edge first.
         *
         * MEASURE THESE. They set the metric scale of the entire board frame, so an
         * error here scales everything Unity places from it, and their ratio decides
         * whether the pose solve converges at all. The current values are inferred
         * from an observed image aspect of about 2.5:1, not from a ruler.
         *
         * Guessing between several standard models was worse than one correct model:
         * a near-square observation got classified as a half-size board and produced
         * a wildly wrong scale.
         */
        const val BOARD_LENGTH_M = 0.165f
        const val BOARD_WIDTH_M = 0.065f

        /** A worse fit than this is not a rectangle we can trust a pose from. */
        const val REPROJECTION_STEPS = 4f
        const val MIN_REPROJECTION_TOLERANCE_PX = 8f

        /** Detection passes to solve across before committing to the best fit. */
        const val SOLVE_ATTEMPTS = 6

        /** How far a candidate may sit from the tracked one and still be the same board. */
        const val CANDIDATE_MATCH_RADIUS_PX = 90f
        const val TRACK_MATCH_RADIUS_PX = 260f

        /** Weight of each fresh solve in the running pose. Lower is steadier but laggier. */
        const val POSE_SMOOTHING = 0.6f

        /**
         * Once tracking, solve on every camera frame. Measured detection cost is 2-9 ms
         * on a background thread, so 30 Hz is comfortably affordable and the limiting
         * factor is the camera rather than us.
         */
        const val TRACKING_INTERVAL_NS = 30_000_000L

        /** Rotation change treated as suspicious rather than real movement. */
        const val MAX_POSE_JUMP_RAD = 1.0f // about 57 degrees between consecutive solves
        const val POSE_JUMP_STREAK_TO_ACCEPT = 2

        /**
         * How long to keep showing the outline after solves stop arriving. Generous
         * enough to ride out a burst of motion blur, short enough that a genuinely
         * stale outline is not left asserting a position we cannot justify.
         */
        const val BOARD_LOST_GRACE_NS = 900_000_000L

        /** Fit tolerance multiplier while tracking, where blur degrades corners. */
        const val TRACKING_TOLERANCE_FACTOR = 2.5f

        /** Weight of the newest centroid step in the velocity estimate. */
        const val VELOCITY_SMOOTHING = 0.5f

        const val ANCHOR_REPUBLISH_DISTANCE_M = 0.002f
        const val ANCHOR_REPUBLISH_ROTATION_DOT = 0.9999f
        const val SURFACE_TARGET_INTERVAL_NS = 250_000_000L
        const val BOARD_OUTLINE_INTERVAL_NS = 66_000_000L
        const val SURFACE_TARGET_COLUMNS = 8
        const val SURFACE_TARGET_ROWS = 12
    }
}
