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
         * Board poses recomputed from each image, independent of ARCore's world
         * tracking. [physicalTranslation]/[physicalQuaternion] retain the raw
         * image-camera frame for the laptop protocol. [displayTranslation]/
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
    private var trackedCentroid: PointF? = null
    private var solveAttemptsLeft = 0
    private var bestSolutionCameraPose: PlanarPoseSolver.Result? = null
    private var bestPhysicalToDisplayCameraPose: Pose? = null
    /** Camera-to-world pose captured with the source image for the current best solve. */
    private var bestPhysicalCameraWorldPose: Pose? = null
    private var bestSolutionError = Float.MAX_VALUE
    private var boardTrackingActive = false
    private var smoothedBoardPose: PlanarPoseSolver.Result? = null
    private val poseSmoother = PoseSmoother()
    private var lastOrderedCorners: FloatArray? = null
    private var lastFreshQuaternion: FloatArray? = null
    private var poseJumpStreak = 0
    private var lastSolveTimestampNs = 0L
    private var boardVisible = false
    private var centroidVelocity = PointF(0f, 0f)
    /** Area of the last accepted image quad, used only to reject obvious fragments. */
    private var lastTrackedAreaPx = 0f
    private var detectionPhysicalToDisplayCameraPose: Pose? = null
    /** Camera-to-world pose belonging to the asynchronously detected image. */
    private var detectionPhysicalCameraWorldPose: Pose? = null
    private var detectionIntrinsicsFocal: FloatArray? = null
    private var detectionIntrinsicsPrincipal: FloatArray? = null
    private var lastPoseLogTimestampNs = 0L
    private var boardAnchor: Anchor? = null
    private var boardLengthMeters = 0f
    private var boardWidthMeters = 0f
    private var lastPublishedBoardPose: Pose? = null
    /**
     * A short-lived ARCore fallback for verified visual tracking. It is deliberately
     * separate from the optional manual-calibration [boardAnchor], so it cannot
     * overwrite the user's calibration or spam calibration protocol messages.
     */
    private var fallbackBoardAnchor: Anchor? = null
    private var lastFallbackAnchorRefreshNs = 0L
    private var lastFallbackPublishNs = 0L
    /**
     * The rectangle has no real-world compass direction.  This is deliberately a
     * screen-relative convention: on first lock, "north" is the side above the
     * outline on the phone display, and it remains fixed until reset.
     */
    private var northAtNegativeY: Boolean? = null

    /**
     * A pose solve before it is committed to the tracker.  Keeping this immutable is
     * important: a rejected rectangle must not replace [lastOrderedCorners], because
     * that history is what prevents the board pose from flipping end-for-end.
     */
    private data class CandidateSolve(
        val index: Int,
        val orderedCorners: FloatArray,
        val result: PlanarPoseSolver.Result,
        val physicalToDisplayCamera: Pose,
        val physicalCameraWorld: Pose,
        val reprojectionErrorPx: Float,
        val centroid: PointF,
        val areaPx: Float,
        val cornerContinuityPx: Float,
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
            bestPhysicalToDisplayCameraPose = null
            bestPhysicalCameraWorldPose = null
            bestSolutionError = Float.MAX_VALUE
            boardTrackingActive = false
            smoothedBoardPose = null
            lastOrderedCorners = null
            lastFreshQuaternion = null
            centroidVelocity = PointF(0f, 0f)
            lastTrackedAreaPx = 0f
            poseJumpStreak = 0
            lastPublishedBoardPose = null
            northAtNegativeY = null
            detectionPhysicalToDisplayCameraPose = null
            detectionPhysicalCameraWorldPose = null
            boardAnchor?.detach()
            boardAnchor = null
            fallbackBoardAnchor?.detach()
            fallbackBoardAnchor = null
            lastFallbackAnchorRefreshNs = 0L
            lastFallbackPublishNs = 0L
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
            bestPhysicalToDisplayCameraPose = null
            bestPhysicalCameraWorldPose = null
            bestSolutionError = Float.MAX_VALUE
            boardTrackingActive = false
            smoothedBoardPose = null
            lastOrderedCorners = null
            lastFreshQuaternion = null
            centroidVelocity = PointF(0f, 0f)
            lastTrackedAreaPx = 0f
            poseJumpStreak = 0
            northAtNegativeY = null
            detectionPhysicalToDisplayCameraPose = null
            detectionPhysicalCameraWorldPose = null
            fallbackBoardAnchor?.detach()
            fallbackBoardAnchor = null
            lastFallbackAnchorRefreshNs = 0L
            lastFallbackPublishNs = 0L
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

            // Image solves remain the primary source of truth. If the white board's
            // outline is momentarily fragmented at an oblique view, switch promptly
            // to the trusted ARCore anchor made from the last visual solve. If ARCore
            // cannot track the anchor either, hide only after the usual grace period
            // rather than leaving an unverified pose on screen.
            val timeSinceVisualSolve = frame.timestamp - lastSolveTimestampNs
            if (boardTrackingActive && timeSinceVisualSolve > ANCHOR_FALLBACK_START_NS) {
                val anchorPublished = isTracking && publishBoardAnchorFallback(camera, frame.timestamp)
                if (anchorPublished) {
                    if (!boardVisible) {
                        boardVisible = true
                        post { listener?.onBoardVisibility(true) }
                    }
                } else if (boardVisible && timeSinceVisualSolve > BOARD_LOST_GRACE_NS) {
                    boardVisible = false
                    post {
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

        solveAttemptsLeft--
        val solved = solveCandidate(bestIndex)
        if (solved != null) {
            trackedCentroid = solved.centroid
            if (solved.reprojectionErrorPx < bestSolutionError) {
                commitInitialSolve(solved)
            }
        }

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
        // Looser than at lock-on: blur costs corner precision, and a slightly worse fit
        // on a board we are already tracking beats dropping the overlay entirely.
        val tolerance = maxOf(MIN_REPROJECTION_TOLERANCE_PX, REPROJECTION_STEPS * 2f) *
            TRACKING_TOLERANCE_FACTOR
        val selected = selectTrackingCandidate(predicted, tolerance) ?: return
        val fresh = selected.result
        val physicalToDisplayCamera = selected.physicalToDisplayCamera

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

        // Commit candidate history only after every quality guard accepts it. A bad
        // luma fragment must never influence the correspondence chosen next frame.
        lastOrderedCorners = selected.orderedCorners.copyOf()
        lastTrackedAreaPx = selected.areaPx
        refreshFallbackAnchorFromVisualSolve(selected)

        val matched = selected.centroid
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

        // The detection quad is the direct visual evidence for the yellow outline.
        // Keep the independently smoothed pose for the native model, but do not
        // reproject that pose back into the outline: smoothing can make its edges lag
        // several pixels behind the board that was actually detected this frame.
        val matchedImageCorners = selected.orderedCorners
        val smoothedPhysical = smoothBoardPose(fresh)
        smoothedBoardPose = smoothedPhysical
        val smoothedDisplay = CameraPoseFrames.boardInDisplayCamera(
            smoothedPhysical,
            physicalToDisplayCamera,
        )
        val north = publishMatchedCandidateOutline(matchedImageCorners) ?: return
        post {
            listener?.onBoardPoseInCamera(
                smoothedPhysical.translation.copyOf(),
                smoothedPhysical.quaternion.copyOf(),
                smoothedDisplay.translation.copyOf(),
                smoothedDisplay.quaternion.copyOf(),
                north,
            )
        }
    }

    /**
     * A tracked frame can contain several bright rectangles: the board, a rail, or
     * even a patch of the background. Previously we solved only the nearest centroid,
     * so one incorrect fragment could suppress a valid board candidate at an oblique
     * angle. Evaluate every plausible candidate and choose the best calibrated pose.
     */
    private fun selectTrackingCandidate(
        predictedCentroid: PointF,
        tolerancePx: Float,
    ): CandidateSolve? {
        var selected: CandidateSolve? = null
        var selectedDistance = Float.MAX_VALUE
        var selectedAreaChange = Float.MAX_VALUE

        candidateImageCorners.forEachIndexed { index, corners ->
            val centroid = centroidOf(corners)
            val distance = hypot(
                centroid.x - predictedCentroid.x,
                centroid.y - predictedCentroid.y,
            )
            // A generous radius while tracking: at roughly 30 solves per second even
            // a moderate hand movement can shift the board substantially between
            // source images.
            if (distance > TRACK_MATCH_RADIUS_PX) return@forEachIndexed

            val area = quadArea(corners)
            // The full board's projected area changes smoothly from one 30 Hz frame
            // to the next. This excludes an inner rail or carpet fragment without
            // rejecting normal perspective changes as the phone tilts.
            if (!hasPlausibleTrackingArea(area)) return@forEachIndexed

            val solved = solveCandidate(index) ?: return@forEachIndexed
            if (solved.reprojectionErrorPx > tolerancePx) return@forEachIndexed

            val areaChange = trackingAreaChange(area)
            val previous = selected
            val shouldSelect = previous == null ||
                isBetterTrackingCandidate(
                    candidate = solved,
                    candidateDistance = distance,
                    candidateAreaChange = areaChange,
                    current = previous,
                    currentDistance = selectedDistance,
                    currentAreaChange = selectedAreaChange,
                )
            if (shouldSelect) {
                selected = solved
                selectedDistance = distance
                selectedAreaChange = areaChange
            }
        }
        return selected
    }

    /** Residual is primary; continuity breaks near-equal residual ties safely. */
    private fun isBetterTrackingCandidate(
        candidate: CandidateSolve,
        candidateDistance: Float,
        candidateAreaChange: Float,
        current: CandidateSolve,
        currentDistance: Float,
        currentAreaChange: Float,
    ): Boolean {
        val residualDifference = candidate.reprojectionErrorPx - current.reprojectionErrorPx
        if (abs(residualDifference) > RESIDUAL_TIE_PX) return residualDifference < 0f

        val distanceDifference = candidateDistance - currentDistance
        if (abs(distanceDifference) > CENTROID_TIE_PX) return distanceDifference < 0f

        val areaDifference = candidateAreaChange - currentAreaChange
        if (abs(areaDifference) > AREA_CHANGE_TIE) return areaDifference < 0f

        // A final deterministic tie-break avoids candidate-list ordering flicker.
        return candidate.cornerContinuityPx < current.cornerContinuityPx
    }

    private fun hasPlausibleTrackingArea(areaPx: Float): Boolean {
        val previous = lastTrackedAreaPx
        if (previous <= 0f || areaPx <= 0f) return true
        val ratio = areaPx / previous
        return ratio in MIN_TRACKING_AREA_RATIO..MAX_TRACKING_AREA_RATIO
    }

    private fun trackingAreaChange(areaPx: Float): Float {
        val previous = lastTrackedAreaPx
        if (previous <= 0f || areaPx <= 0f) return 0f
        return abs(areaPx / previous - 1f)
    }

    private fun commitInitialSolve(solved: CandidateSolve) {
        val longSide = sideLength(solved.orderedCorners, 0)
        val shortSide = sideLength(solved.orderedCorners, 1)
        val observedAspect = if (shortSide > 0f) longSide / shortSide else 0f
        android.util.Log.d(
            "ArSolve",
            "index=${solved.index} aspect=$observedAspect modelAspect=${BOARD_LENGTH_M / BOARD_WIDTH_M} " +
                "errorPx=${solved.reprojectionErrorPx} depthM=${solved.result.translation[2]}",
        )
        bestSolutionCameraPose = solved.result
        bestPhysicalToDisplayCameraPose = solved.physicalToDisplayCamera
        bestPhysicalCameraWorldPose = solved.physicalCameraWorld
        bestSolutionError = solved.reprojectionErrorPx
        lastOrderedCorners = solved.orderedCorners.copyOf()
    }

    private fun finishBoardSolve() {
        val tolerance = maxOf(MIN_REPROJECTION_TOLERANCE_PX, REPROJECTION_STEPS * 2f)
        if (bestSolutionCameraPose == null ||
            bestPhysicalToDisplayCameraPose == null ||
            bestPhysicalCameraWorldPose == null ||
            bestSolutionError > tolerance
        ) {
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

        // Image solves remain the primary tracker, because they cannot inherit
        // ARCore world-map drift. The anchor below is retained only as a temporary
        // 6-DoF fallback when an oblique frame loses the board's bright outline.
        boardTrackingActive = true
        smoothedBoardPose = bestSolutionCameraPose
        boardLengthMeters = BOARD_LENGTH_M
        boardWidthMeters = BOARD_WIDTH_M
        lastTrackedAreaPx = lastOrderedCorners?.let(::quadArea) ?: 0f
        createOrReplaceFallbackAnchor(
            boardInPhysicalCamera = bestSolutionCameraPose,
            physicalCameraInWorld = bestPhysicalCameraWorldPose,
            timestampNs = latestFrame?.timestamp ?: 0L,
        )
        post { listener?.onCandidateRectangles(emptyList()) }
        post { listener?.onBoardTracked(bestSolutionError) }
    }

    /**
     * Rebase the fallback anchor occasionally while visual tracking is healthy. This
     * corrects slow ARCore world-map drift without making the native renderer depend
     * on world tracking for its ordinary, image-verified frames.
     */
    private fun refreshFallbackAnchorFromVisualSolve(solved: CandidateSolve) {
        val timestamp = latestFrame?.timestamp ?: return
        if (timestamp - lastFallbackAnchorRefreshNs < FALLBACK_ANCHOR_REFRESH_NS) return
        createOrReplaceFallbackAnchor(
            boardInPhysicalCamera = solved.result,
            physicalCameraInWorld = solved.physicalCameraWorld,
            timestampNs = timestamp,
        )
    }

    private fun createOrReplaceFallbackAnchor(
        boardInPhysicalCamera: PlanarPoseSolver.Result?,
        physicalCameraInWorld: Pose?,
        timestampNs: Long,
    ) {
        val board = boardInPhysicalCamera ?: return
        val camera = physicalCameraInWorld ?: return
        val session = arSession.get() ?: return
        val boardInWorld = camera.compose(Pose(board.translation, board.quaternion))
        val anchor = try {
            session.createAnchor(boardInWorld)
        } catch (error: Exception) {
            android.util.Log.w("ArAnchor", "Could not create board fallback anchor", error)
            return
        }
        fallbackBoardAnchor?.detach()
        fallbackBoardAnchor = anchor
        lastFallbackAnchorRefreshNs = timestampNs
        lastFallbackPublishNs = 0L
    }

    /**
     * Publishes an anchor-derived pose only while a recent visual solve has gone
     * stale. It preserves full 6-DoF parallax as the learner tilts the phone, while
     * [trackBoard] takes over immediately again when a good image quad returns.
     */
    private fun publishBoardAnchorFallback(camera: Camera, timestampNs: Long): Boolean {
        val anchor = fallbackBoardAnchor ?: return false
        if (anchor.trackingState != TrackingState.TRACKING) return false
        val north = northAtNegativeY ?: return false
        if (timestampNs - lastFallbackPublishNs < FALLBACK_PUBLISH_INTERVAL_NS) return true

        val boardInPhysicalCamera = camera.pose.inverse().compose(anchor.pose)
        val boardInDisplayCamera = camera.displayOrientedPose.inverse().compose(anchor.pose)
        val calibration = BoardCalibration(
            originMeters = anchor.pose.translation,
            xAxis = anchor.pose.xAxis,
            yAxis = anchor.pose.yAxis,
            zAxis = anchor.pose.zAxis,
            xExtentMeters = BOARD_LENGTH_M,
            yExtentMeters = BOARD_WIDTH_M,
        )
        val outline = projectBoardOutline(camera, calibration)
        if (outline.size != 4) return false

        lastFallbackPublishNs = timestampNs
        post {
            listener?.onBoardOutline(outline)
            listener?.onBoardPoseInCamera(
                boardInPhysicalCamera.translation.copyOf(),
                boardInPhysicalCamera.rotationQuaternion.copyOf(),
                boardInDisplayCamera.translation.copyOf(),
                boardInDisplayCamera.rotationQuaternion.copyOf(),
                north,
            )
        }
        return true
    }

    /**
     * Blends a fresh physical-camera solve into the running estimate. Each solve
     * carries independent corner noise, so using them raw makes the 3-D model
     * twitch. The 2-D outline deliberately stays unsmoothed, because it is direct
     * evidence from this frame and should not lag behind the real board.
     */
    private fun smoothBoardPose(fresh: PlanarPoseSolver.Result): PlanarPoseSolver.Result =
        poseSmoother.smooth(smoothedBoardPose, fresh)

    /**
     * Draws the yellow outline from the current matched image-space quad. This keeps
     * the overlay attached to the detected breadboard edge rather than to the smoothed
     * 3-D pose used by the native renderer.
     */
    private fun publishMatchedCandidateOutline(imageCorners: FloatArray): Boolean? {
        if (imageCorners.size != 8) return null

        val viewPoints = FloatArray(imageCorners.size)
        val frame = latestFrame ?: return null
        try {
            frame.transformCoordinates2d(
                Coordinates2d.IMAGE_PIXELS,
                imageCorners,
                Coordinates2d.VIEW,
                viewPoints,
            )
        } catch (_: Exception) {
            return null
        }

        val outline = (0 until 4).map { PointF(viewPoints[it * 2], viewPoints[it * 2 + 1]) }
        val north = northAtNegativeY ?: run {
            // orderedCorners keeps 0-1 and 2-3 as the two long edges of this matched
            // detection quad. Choose the edge closer to the top of the phone display
            // only once; doing this on every frame would make a nearly head-on board
            // swap sides.
            val negativeYEdge = (outline[0].y + outline[1].y) / 2f
            val positiveYEdge = (outline[2].y + outline[3].y) / 2f
            (negativeYEdge <= positiveYEdge).also { northAtNegativeY = it }
        }
        post { listener?.onBoardOutline(outline) }
        return north
    }

    /**
     * Solves one detected quad without changing tracker state. A detector may begin
     * a cyclic quad at any corner, and a steep perspective view can make the board's
     * long edge look shorter in pixels than its short edge. Trying all cyclic and
     * winding-preserving correspondence hypotheses makes the pose fit decide which
     * board edge is X instead of relying on that fragile screen-space assumption.
     */
    private fun solveCandidate(index: Int): CandidateSolve? {
        val raw = candidateImageCorners.getOrNull(index) ?: return null
        val physicalToDisplayCamera = detectionPhysicalToDisplayCameraPose ?: return null
        val physicalCameraWorld = detectionPhysicalCameraWorldPose ?: return null
        val focal = detectionIntrinsicsFocal ?: return null
        val principal = detectionIntrinsicsPrincipal ?: return null
        val model = boardModel()
        val centroid = centroidOf(raw)
        val area = quadArea(raw)
        val previous = lastOrderedCorners

        var best: CandidateSolve? = null
        for (direction in intArrayOf(1, -1)) {
            for (start in 0 until 4) {
                val corners = cyclicCornerOrder(raw, start, direction)
                val solution = PlanarPoseSolver.solve(corners, model, focal, principal) ?: continue
                val error = PlanarPoseSolver.reprojectionErrorPx(
                    corners,
                    model,
                    focal,
                    principal,
                    solution,
                )
                if (!error.isFinite()) continue
                val continuity = previous?.let { meanCornerDistance(corners, it) } ?: 0f
                val candidate = CandidateSolve(
                    index = index,
                    orderedCorners = corners,
                    result = solution,
                    physicalToDisplayCamera = physicalToDisplayCamera,
                    physicalCameraWorld = physicalCameraWorld,
                    reprojectionErrorPx = error,
                    centroid = centroid,
                    areaPx = area,
                    cornerContinuityPx = continuity,
                )
                val current = best
                if (current == null || isBetterCornerOrder(candidate, current)) {
                    best = candidate
                }
            }
        }
        return best
    }

    /** Prefer a calibrated fit, then preserve the pose correspondence over time. */
    private fun isBetterCornerOrder(candidate: CandidateSolve, current: CandidateSolve): Boolean {
        val residualDifference = candidate.reprojectionErrorPx - current.reprojectionErrorPx
        if (abs(residualDifference) > RESIDUAL_TIE_PX) return residualDifference < 0f
        return candidate.cornerContinuityPx < current.cornerContinuityPx
    }

    private fun cyclicCornerOrder(corners: FloatArray, start: Int, direction: Int): FloatArray {
        val ordered = FloatArray(8)
        for (corner in 0 until 4) {
            val source = (start + direction * corner + 4) % 4
            ordered[corner * 2] = corners[source * 2]
            ordered[corner * 2 + 1] = corners[source * 2 + 1]
        }
        return ordered
    }

    private fun meanCornerDistance(first: FloatArray, second: FloatArray): Float {
        var total = 0f
        for (corner in 0 until 4) {
            total += hypot(
                first[corner * 2] - second[corner * 2],
                first[corner * 2 + 1] - second[corner * 2 + 1],
            )
        }
        return total / 4f
    }

    private fun boardModel(): FloatArray = floatArrayOf(
        0f, 0f,
        BOARD_LENGTH_M, 0f,
        BOARD_LENGTH_M, BOARD_WIDTH_M,
        0f, BOARD_WIDTH_M,
    )

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

    private fun quadArea(quad: FloatArray): Float = abs(signedArea(quad))

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
        detectionPhysicalToDisplayCameraPose = CameraPoseFrames.physicalToDisplayCamera(
            physicalCameraPose = camera.pose,
            displayOrientedCameraPose = camera.displayOrientedPose,
        )
        detectionPhysicalCameraWorldPose = camera.pose
        detectionIntrinsicsFocal = camera.imageIntrinsics.focalLength
        detectionIntrinsicsPrincipal = camera.imageIntrinsics.principalPoint

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
        val projection = FloatArray(16)
        camera.getProjectionMatrix(projection, 0, FILAMENT_NEAR_M, FILAMENT_FAR_M)
        post {
            listener?.onCameraState(state)
            listener?.onCameraProjection(projection)
        }
    }

    private fun postError(message: String) {
        post { listener?.onArError(message) }
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
        const val RECTANGLE_INTERVAL_NS = 300_000_000L // Roughly three detections per second.

        /**
         * Outer dimensions of the physical demo breadboard, long edge first.
         *
         * This particular board is 165 mm × 65 mm. It must stay in sync with the
         * renderer: its aspect ratio is what lets the planar pose solver recover a
         * physically valid camera pose.
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

        /** Treat nearly-equal pose residuals as ties, then preserve motion continuity. */
        const val RESIDUAL_TIE_PX = 1f
        const val CENTROID_TIE_PX = 8f
        const val AREA_CHANGE_TIE = 0.05f

        /**
         * A full board cannot realistically change projected area by this much in one
         * 30 Hz detector step. The broad bounds only reject obvious inner fragments.
         */
        const val MIN_TRACKING_AREA_RATIO = 0.25f
        const val MAX_TRACKING_AREA_RATIO = 4f

        /** Move to the already-verified AR anchor before the visual grace period ends. */
        const val ANCHOR_FALLBACK_START_NS = 250_000_000L
        /** Rebase a fallback anchor only while visual tracking confirms the board. */
        const val FALLBACK_ANCHOR_REFRESH_NS = 1_000_000_000L
        const val FALLBACK_PUBLISH_INTERVAL_NS = 33_000_000L

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
