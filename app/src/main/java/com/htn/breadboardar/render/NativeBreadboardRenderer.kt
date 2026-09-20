package com.htn.breadboardar.render

import android.content.res.AssetManager
import com.htn.breadboardar.circuit.BoardGeometry
import com.htn.breadboardar.circuit.CircuitDefinition
import android.opengl.Matrix
import android.os.Looper
import android.view.Choreographer
import android.view.TextureView
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import kotlin.math.*
import com.google.android.filament.IndirectLight
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.Renderer
import com.google.android.filament.View
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer

/**
 * Draws the reviewed circuit, assembled from Unity meshes, into a transparent [TextureView] above the ARCore camera
 * image.
 *
 * The pose received from [showBoardNorthOfOutline] is intentionally camera-relative
 * and already expressed in ARCore's display-oriented camera axes. It therefore
 * matches the display-oriented projection supplied by [updateArCameraProjection],
 * so the Filament camera can remain at identity while the board model receives the
 * board-to-camera transform directly.
 *
 * All public methods must be called on the main thread. The current AR preview posts its listener
 * callbacks to that thread, and Filament requires every resource belonging to one engine to be
 * touched from one thread.
 */
internal class NativeBreadboardRenderer(
    textureView: TextureView,
    assets: AssetManager,
    circuit: CircuitDefinition,
    private val previewMode: Boolean = false,
) {
    private val overlay = textureView
    private val choreographer = Choreographer.getInstance()
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
        // This must happen before ModelViewer attaches the helper to the TextureView. It makes
        // the swap chain use CONFIG_TRANSPARENT rather than erasing the camera preview to black.
        isOpaque = false
    }

    private lateinit var modelViewer: ModelViewer
    private var sourceBuffer: ByteBuffer? = null
    private var boardPose: BoardPose? = null
    private var cameraProjection: FloatArray? = null
    private var assetLocalTransform: FloatArray? = null
    private var modelWidthMeters = 0f
    private var physicalBoardWidthMeters = 0.055f
    private var modelHeightMeters = 0f
    private var lowPolyProxyHidden = false
    private var frameScheduled = false
    private var paused = false
    private var released = false
    private var ambient: IndirectLight? = null
    private var previewDistance = .36
    private var previewYaw = 1.1
    private var previewPitch = .85

    fun setPhysicalBoardWidth(widthMeters: Float) {
        checkMainThread()
        require(widthMeters.isFinite() && widthMeters > 0f)
        physicalBoardWidthMeters = widthMeters
    }

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        frameScheduled = false
        if (!released && !paused) {
            applyLatestState()
            modelViewer.render(frameTimeNanos)
            // ModelViewer adds GLB renderables as their asynchronous resources become
            // ready. A second check after rendering catches LP on the exact frame it
            // first receives its RenderableManager component.
            hideLowPolyProxyIfReady()
            scheduleFrame()
        }
    }

    init {
        checkMainThread()
        // ModelViewer uses all three native packages. Filament.init() alone only
        // loads libfilament-jni; GLB material loading also requires gltfio and the
        // utility package before ModelViewer constructs its UbershaderProvider.
        Filament.init()
        Gltfio.init()
        Utils.init()

        overlay.isOpaque = false
        overlay.alpha = 0f // Do not show ModelViewer's default camera before ARCore supplies one.
        modelViewer = ModelViewer(
            textureView = overlay,
            engine = Engine.create(),
            uiHelper = uiHelper,
            manipulator = null,
        )

        configureTransparentView()
        configureCameraRelativeLighting()
        ambient = IndirectLight.Builder().irradiance(1, floatArrayOf(.8f,.8f,.8f)).intensity(12000f).build(modelViewer.engine)
        modelViewer.scene.indirectLight = ambient
        if (previewMode) {
            val scale = ScaleGestureDetector(overlay.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    previewDistance = (previewDistance / detector.scaleFactor).coerceIn(.07,.65); return true
                }
            })
            var x=0f; var y=0f
            overlay.setOnTouchListener { _,event ->
                scale.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_MOVE && !scale.isInProgress && event.pointerCount == 1) {
                    previewYaw -= (event.x-x)*.008; previewPitch=(previewPitch+(event.y-y)*.005).coerceIn(.12,1.5)
                }
                x=event.x; y=event.y
                if (event.actionMasked == MotionEvent.ACTION_UP) overlay.performClick()
                true
            }
        }
        sourceBuffer = CircuitGlbBuilder(
            BoardGeometry(assets.open("board-map.json").bufferedReader().use { it.readText() }),
            assets.open(MODEL_ASSET_PATH).use { it.readBytes() },
            assets.open("models/components.json").bufferedReader().use { it.readText() },
        ).build(circuit)
        loadModelOrRestorePrevious(sourceBuffer!!)
        updateOverlayVisibility()
        scheduleFrame()
    }

    /** Supplies ARCore's display-oriented projection matrix (near=.02m, far=20m). */
    fun updateArCameraProjection(projection: FloatArray) {
        checkMainThread()
        if (released || projection.size < 16) return
        cameraProjection = projection.copyOf()
        updateOverlayVisibility()
    }

    /**
     * Moves the virtual GLB to the side of the detected physical board that was visually higher
     * when tracking first locked. [northAtNegativeY] is therefore fixed for a calibration session,
     * rather than reevaluated while the camera moves.
     */
    fun showBoardNorthOfOutline(
        translation: FloatArray,
        quaternion: FloatArray,
        northAtNegativeY: Boolean,
    ) {
        checkMainThread()
        if (released || translation.size < 3 || quaternion.size < 4) return
        boardPose = BoardPose(
            translation = translation.copyOf(3),
            quaternion = quaternion.copyOf(4),
            northAtNegativeY = northAtNegativeY,
        )
        updateOverlayVisibility()
    }

    /** Hides the model on reset or loss of ARCore anchor tracking. */
    fun hide() {
        checkMainThread()
        boardPose = null
        updateOverlayVisibility()
    }

    /** Stops rendering while the activity is backgrounded without destroying the TextureView. */
    fun pause() {
        checkMainThread()
        paused = true
        if (frameScheduled) {
            choreographer.removeFrameCallback(frameCallback)
            frameScheduled = false
        }
    }

    /** Resumes the Choreographer-driven render loop after [pause]. */
    fun resume() {
        checkMainThread()
        if (released) return
        paused = false
        scheduleFrame()
    }

    /**
     * Ends this wrapper's rendering loop.
     *
     * ModelViewer registers its own TextureView detach listener and destroys its engine from that
     * callback. Do not call ModelViewer.destroy() here: an Activity's view subsequently detaches,
     * and double-destroying a Filament engine can crash native code.
     */
    fun destroy() {
        checkMainThread()
        if (released) return
        released = true
        modelViewer.scene.indirectLight = null
        ambient?.let { modelViewer.engine.destroyIndirectLight(it) }; ambient = null
        boardPose = null
        overlay.alpha = 0f
        if (frameScheduled) {
            choreographer.removeFrameCallback(frameCallback)
            frameScheduled = false
        }
    }

    private fun configureTransparentView() {
        modelViewer.scene.skybox = null
        modelViewer.view.blendMode = View.BlendMode.TRANSLUCENT
        modelViewer.renderer.clearOptions = Renderer.ClearOptions().apply {
            clear = true
            clearColor = floatArrayOf(0f, 0f, 0f, 0f)
        }

        // Filament 1.71+ has a native crash path for a translucent TextureView with TAA or
        // SSAO enabled. They are not useful for this small instructional overlay, so disable
        // both explicitly while retaining ordinary tone mapping and direct lighting.
        modelViewer.view.ambientOcclusionOptions = View.AmbientOcclusionOptions().apply {
            enabled = false
        }
        modelViewer.view.temporalAntiAliasingOptions = View.TemporalAntiAliasingOptions().apply {
            enabled = false
        }
    }

    /**
     * ModelViewer creates its sun pointing straight down in its own world. Our model
     * is camera-relative, so point the sun from the phone toward the scene instead:
     * after the asset-axis correction below, the breadboard's printable top faces
     * camera +Z and therefore receives this -Z-directed light.
     */
    private fun configureCameraRelativeLighting() {
        val lightManager = modelViewer.engine.lightManager
        val lightInstance = lightManager.getInstance(modelViewer.light)
        if (lightInstance != 0) {
            if (previewMode) lightManager.setDirection(lightInstance, -.4f, -1f, -.3f)
            else lightManager.setDirection(lightInstance, 0f, 0f, -1f)
        }
    }

    private fun scheduleFrame() {
        if (!released && !paused && !frameScheduled) {
            frameScheduled = true
            choreographer.postFrameCallback(frameCallback)
        }
    }

    private fun updateOverlayVisibility() {
        overlay.alpha = if (!released && (previewMode || (boardPose != null && cameraProjection != null))) 1f else 0f
    }

    /**
     * `ModelViewer.loadModelGlb` destroys its current asset before loading. Retain the
     * prior direct source and restore it if a structurally valid but non-renderable
     * remote model is rejected by Filament.
     */
    private fun loadModelOrRestorePrevious(replacement: ByteBuffer) {
        val previous = sourceBuffer
        try {
            resetModelDerivedState()
            replacement.rewind()
            modelViewer.loadModelGlb(replacement)
            val asset = checkNotNull(modelViewer.asset) { "Filament could not parse this GLB." }
            // Validate the existing renderer's documented source-axis contract now,
            // rather than allowing a bad bounds value to fail inside a frame callback.
            assetLocalTransform = buildAssetLocalTransform(asset)
            sourceBuffer = replacement
        } catch (error: Exception) {
            if (previous != null && previous !== replacement) {
                runCatching {
                    resetModelDerivedState()
                    previous.rewind()
                    modelViewer.loadModelGlb(previous)
                    modelViewer.asset?.let { assetLocalTransform = buildAssetLocalTransform(it) }
                }
                sourceBuffer = previous
            }
            throw IllegalArgumentException(
                "Filament could not load that GLB. The previous model was restored.",
                error,
            )
        }
    }

    private fun resetModelDerivedState() {
        assetLocalTransform = null
        modelWidthMeters = 0f
        modelHeightMeters = 0f
        lowPolyProxyHidden = false
    }

    private fun applyLatestState() {
        if (previewMode) {
            modelViewer.camera.lookAt(previewDistance*cos(previewPitch)*sin(previewYaw), previewDistance*sin(previewPitch), previewDistance*cos(previewPitch)*cos(previewYaw), 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
            modelViewer.camera.setProjection(42.0, overlay.width.toDouble() / overlay.height.coerceAtLeast(1), .01, 5.0, com.google.android.filament.Camera.Fov.VERTICAL)
            modelViewer.asset?.let { asset ->
                val transforms = modelViewer.engine.transformManager
                transforms.setTransform(transforms.getInstance(asset.root), IDENTITY_MATRIX)
            }
            return
        }
        val projection = cameraProjection ?: return
        // Camera-relative board poses deliberately use an identity Filament camera. Its
        // projection is the exact display-oriented matrix ARCore used for the camera background.
        modelViewer.camera.setModelMatrix(IDENTITY_MATRIX)
        modelViewer.camera.setCustomProjection(
            DoubleArray(16) { index -> projection[index].toDouble() },
            FILAMENT_NEAR_M.toDouble(),
            FILAMENT_FAR_M.toDouble(),
        )

        // This GLB contains a detailed high-poly breadboard (HP) and a coincident
        // low-poly proxy (LP). Rendering both lets the opaque LP cuboid cover the
        // detailed mesh, which appears as the black slab seen in the AR view.
        // Resource loading is asynchronous, so keep trying until LP has a renderable
        // instance, then hide it on every Filament view layer.
        hideLowPolyProxyIfReady()

        val pose = boardPose ?: return
        val asset = modelViewer.asset ?: return
        val localTransform = assetLocalTransform ?: buildAssetLocalTransform(asset).also {
            assetLocalTransform = it
        }

        val boardToCamera = pose.toMatrix()
        val northCenterY = if (pose.northAtNegativeY) {
            -(NORTH_GAP_M + modelWidthMeters / 2f)
        } else {
            physicalBoardWidthMeters + NORTH_GAP_M + modelWidthMeters / 2f
        }

        // Calibration accepts only front-facing board-local -Z. Its direction stays
        // fixed in the world as the phone moves; never billboard the model at the phone.
        // Put the virtual board
        // directly north of the outline and just camera-side of the physical-board plane,
        // so its printable top is visible and it does not z-fight with the table.
        val northOfBoard = FloatArray(16)
        Matrix.setIdentityM(northOfBoard, 0)
        Matrix.translateM(
            northOfBoard,
            0,
            BOARD_LENGTH_M / 2f,
            northCenterY,
            -modelHeightMeters / 2f - SURFACE_CLEARANCE_M,
        )

        val placedInCamera = FloatArray(16)
        val finalTransform = FloatArray(16)
        Matrix.multiplyMM(placedInCamera, 0, boardToCamera, 0, northOfBoard, 0)
        Matrix.multiplyMM(finalTransform, 0, placedInCamera, 0, localTransform, 0)

        val transformManager = modelViewer.engine.transformManager
        transformManager.setTransform(transformManager.getInstance(asset.root), finalTransform)
    }

    private fun hideLowPolyProxyIfReady() {
        if (lowPolyProxyHidden) return
        val asset = modelViewer.asset ?: return
        val renderableManager = modelViewer.engine.renderableManager
        var hidAny = false
        for (entity in asset.getEntitiesByName(LOW_POLY_PROXY_NODE)) {
            // An entity exists as soon as glTF is parsed, but it receives a
            // RenderableManager component only after its asynchronous resources load.
            if (renderableManager.hasComponent(entity)) {
                renderableManager.setLayerMask(
                    renderableManager.getInstance(entity),
                    ALL_RENDER_LAYERS,
                    NO_RENDER_LAYERS,
                )
                hidAny = true
            }
        }
        lowPolyProxyHidden = hidAny
    }

    /**
     * The assembled scene already uses meters, with X along rows and Y above the board.
     * A -90° X rotation maps Y to board normal -Z and Z to board +Y.
     */
    private fun buildAssetLocalTransform(@Suppress("UNUSED_PARAMETER") asset: FilamentAsset): FloatArray {
        // CircuitGlbBuilder already outputs meters with board surface at Y=0.
        // Never normalize by the full scene bounds: an Uno or raised wire must not shrink the board.
        modelWidthMeters = VIRTUAL_BOARD_WIDTH_M
        modelHeightMeters = 0f
        return FloatArray(16).also { transform ->
            Matrix.setIdentityM(transform, 0)
            Matrix.rotateM(transform, 0, -90f, 1f, 0f, 0f)
        }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "NativeBreadboardRenderer must be used from the main thread."
        }
    }

    private data class BoardPose(
        val translation: FloatArray,
        val quaternion: FloatArray,
        val northAtNegativeY: Boolean,
    ) {
        /** Converts ARCore's x/y/z/w quaternion and translation to an OpenGL column-major mat4. */
        fun toMatrix(): FloatArray {
            val x = quaternion[0]
            val y = quaternion[1]
            val z = quaternion[2]
            val w = quaternion[3]
            val xx = x * x
            val yy = y * y
            val zz = z * z
            val xy = x * y
            val xz = x * z
            val yz = y * z
            val xw = x * w
            val yw = y * w
            val zw = z * w

            return floatArrayOf(
                1f - 2f * (yy + zz), 2f * (xy + zw), 2f * (xz - yw), 0f,
                2f * (xy - zw), 1f - 2f * (xx + zz), 2f * (yz + xw), 0f,
                2f * (xz + yw), 2f * (yz - xw), 1f - 2f * (xx + yy), 0f,
                translation[0], translation[1], translation[2], 1f,
            )
        }
    }

    private companion object {
        const val MODEL_ASSET_PATH = "models/breadboard.glb"
        const val BOARD_LENGTH_M = 0.165f
        // Scene layout width is independent of the physical profile selected by tracking.
        const val VIRTUAL_BOARD_WIDTH_M = 0.065f
        const val NORTH_GAP_M = 0.03f
        const val SURFACE_CLEARANCE_M = 0.001f
        const val FILAMENT_NEAR_M = 0.02f
        const val FILAMENT_FAR_M = 20f
        const val LOW_POLY_PROXY_NODE = "LP"
        const val ALL_RENDER_LAYERS = 0xFF
        const val NO_RENDER_LAYERS = 0x00

        val IDENTITY_MATRIX = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
    }
}
