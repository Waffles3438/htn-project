package com.htn.breadboardar.ar

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.Surface
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArCameraPreview @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer {

    interface Listener {
        fun onCameraState(state: CameraState)
        fun onCalibrationHit(pose: com.google.ar.core.Pose)
        fun onArError(message: String)
    }

    var listener: Listener? = null

    private val arSession = AtomicReference<Session?>(null)
    private var backgroundRenderer: CameraBackgroundRenderer? = null
    private var cameraTextureId: Int? = null
    private var latestFrame: Frame? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var lastCameraStateTimestampNs = 0L

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
            val hit = frame.hitTest(x, y).firstOrNull(::isUsableHit)
            if (hit == null) {
                postError("Point the camera at the breadboard and tap a detected surface.")
            } else {
                post { listener?.onCalibrationHit(hit.hitPose) }
            }
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
        arSession.get()?.setDisplayGeometry(display?.rotation ?: Surface.ROTATION_0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = arSession.get() ?: return
        val frame = try {
            session.update()
        } catch (_: CameraNotAvailableException) {
            postError("Camera is unavailable. Close other camera apps and reconnect.")
            return
        }

        latestFrame = frame
        backgroundRenderer?.draw(frame)
        val camera = frame.camera
        if (camera.trackingState == TrackingState.TRACKING &&
            frame.timestamp - lastCameraStateTimestampNs >= CAMERA_STATE_INTERVAL_NS
        ) {
            lastCameraStateTimestampNs = frame.timestamp
            postCameraState(frame, camera)
        }
    }

    private fun configureCameraTexture(session: Session) {
        val textureId = cameraTextureId ?: return
        session.setCameraTextureNames(intArrayOf(textureId))
        if (surfaceWidth > 0 && surfaceHeight > 0) {
            session.setDisplayGeometry(display?.rotation ?: Surface.ROTATION_0, surfaceWidth, surfaceHeight)
        }
    }

    private fun isUsableHit(hit: HitResult): Boolean = when (val trackable = hit.trackable) {
        is Plane -> trackable.isPoseInPolygon(hit.hitPose)
        is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
        else -> false
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
        const val CAMERA_STATE_INTERVAL_NS = 100_000_000L // 10 messages per second.
    }
}

