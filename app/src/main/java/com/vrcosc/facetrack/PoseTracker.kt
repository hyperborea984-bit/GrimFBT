package com.vrcosc.facetrack

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.io.ByteArrayOutputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.RandomAccessFile

/**
 * Landmark indices in MediaPipe's 33-point BlazePose topology that we care
 * about for full-body OSC tracking. Reference: MediaPipe Pose documentation.
 */
private object Lm {
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28
    const val LEFT_FOOT_INDEX = 31
    const val RIGHT_FOOT_INDEX = 32
}

/**
 * VRChat OSC Tracker slot convention. VRChat reads these as generic
 * SteamVR-style trackers and runs its own full-body IK/calibration on top —
 * we just need to feed stable positions consistently to the same slot per
 * body part every frame. Slot count (3/5/6/7/8) determines which calibration
 * VRChat applies; using all 7 below (no chest) lines up with the common
 * "hip + 2 feet + 2 knees + 2 elbows" 7-point layout. Adjust SLOT_MAP if your
 * setup differs.
 */
private object SlotMap {
    const val HIP = 1
    const val LEFT_FOOT = 2
    const val RIGHT_FOOT = 3
    const val LEFT_KNEE = 4
    const val RIGHT_KNEE = 5
    const val LEFT_ELBOW = 6
    const val RIGHT_ELBOW = 7
    const val CHEST = 8
}

class PoseTracker(
    context: Context,
    modelPath: String,
    private val osc: OscClient,
    private val onFpsUpdate: (Double) -> Unit,
    private val onTrackingQuality: (visibleJointCount: Int, totalJoints: Int) -> Unit
) : ImageAnalysis.Analyzer {

    private val landmarker: PoseLandmarker
    private val visibilityThreshold = 0.5f

    // One filter chain per joint we forward to VRChat. Keeping raw World
    // landmark units (meters, hip-relative) matches what SteamVR-style
    // trackers expect in scale terms; VRChat's own calibration handles offset.
    private val filters = mapOf(
        SlotMap.HIP to Vec3Filter(minCutoff = 0.7, beta = 0.4),
        SlotMap.CHEST to Vec3Filter(minCutoff = 0.7, beta = 0.4),
        SlotMap.LEFT_FOOT to Vec3Filter(minCutoff = 1.0, beta = 0.6),
        SlotMap.RIGHT_FOOT to Vec3Filter(minCutoff = 1.0, beta = 0.6),
        SlotMap.LEFT_KNEE to Vec3Filter(minCutoff = 1.0, beta = 0.6),
        SlotMap.RIGHT_KNEE to Vec3Filter(minCutoff = 1.0, beta = 0.6),
        SlotMap.LEFT_ELBOW to Vec3Filter(minCutoff = 1.0, beta = 0.6),
        SlotMap.RIGHT_ELBOW to Vec3Filter(minCutoff = 1.0, beta = 0.6),
    )

    // Last-known-good smoothed value per slot, held steady when confidence drops
    // (this is what keeps seated/occluded joints from jittering wildly).
    private val lastGood = mutableMapOf<Int, FloatArray>()

    private var lastFrameTimeNs = 0L
    private var frameCount = 0
    private var fpsWindowStartNs = 0L

    init {
        val modelBuffer = loadModelAsMappedBuffer(modelPath)
        val baseOptions = BaseOptions.builder().setModelAssetBuffer(modelBuffer).build()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputSegmentationMasks(false)
            .setResultListener(::onResult)
            .setErrorListener { e -> Log.e("PoseTracker", "MediaPipe error", e) }
            .build()

        landmarker = PoseLandmarker.createFromOptions(context, options)
    }

    override fun analyze(imageProxy: ImageProxy) {
        val timestampMs = System.currentTimeMillis()
        try {
            val bitmap = imageProxyToBitmap(imageProxy, mirror = true)
            val mpImage = BitmapImageBuilder(bitmap).build()
            landmarker.detectAsync(mpImage, timestampMs)
        } catch (e: Exception) {
            Log.e("PoseTracker", "Frame analysis failed", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun onResult(result: PoseLandmarkerResult, input: com.google.mediapipe.framework.image.MPImage) {
        val nowNs = System.nanoTime()
        trackFps(nowNs)

        val worldPoses = result.worldLandmarks()
        val imagePoses = result.landmarks()
        if (worldPoses.isEmpty() || imagePoses.isEmpty()) {
            osc.sendBool("/avatar/parameters/TrackingActive", false)
            return
        }
        val world = worldPoses[0]
        val image = imagePoses[0] // used only for visibility/presence scores

        fun visible(i: Int): Boolean =
            (image.getOrNull(i)?.visibility()?.orElse(0f) ?: 0f) >= visibilityThreshold

        fun midpoint(a: Int, b: Int): Triple<Float, Float, Float> {
            val pa = world[a]; val pb = world[b]
            return Triple((pa.x() + pb.x()) / 2f, (pa.y() + pb.y()) / 2f, (pa.z() + pb.z()) / 2f)
        }

        var visibleCount = 0
        val totalJoints = filters.size

        fun sendSlot(slot: Int, x: Float, y: Float, z: Float, isVisible: Boolean) {
            val filter = filters.getValue(slot)
            val result3d: FloatArray
            if (isVisible) {
                visibleCount++
                result3d = filter.filter(x, y, z, nowNs)
                lastGood[slot] = result3d
            } else {
                // Hold last smoothed value instead of feeding noisy/occluded data.
                result3d = lastGood[slot] ?: filter.filter(x, y, z, nowNs)
            }
            osc.sendFloat("/tracking/trackers/$slot/position/x", result3d[0])
            osc.sendFloat("/tracking/trackers/$slot/position/y", result3d[1])
            osc.sendFloat("/tracking/trackers/$slot/position/z", result3d[2])
            // No reliable per-joint rotation from a single monocular camera;
            // VRChat's FBT solver derives limb rotation from tracker positions.
            osc.sendFloat("/tracking/trackers/$slot/rotation/x", 0f)
            osc.sendFloat("/tracking/trackers/$slot/rotation/y", 0f)
            osc.sendFloat("/tracking/trackers/$slot/rotation/z", 0f)
        }

        val (hipX, hipY, hipZ) = midpoint(Lm.LEFT_HIP, Lm.RIGHT_HIP)
        sendSlot(SlotMap.HIP, hipX, hipY, hipZ, visible(Lm.LEFT_HIP) && visible(Lm.RIGHT_HIP))

        val (chestX, chestY, chestZ) = midpoint(Lm.LEFT_SHOULDER, Lm.RIGHT_SHOULDER)
        sendSlot(SlotMap.CHEST, chestX, chestY, chestZ, visible(Lm.LEFT_SHOULDER) && visible(Lm.RIGHT_SHOULDER))

        world[Lm.LEFT_ANKLE].let { sendSlot(SlotMap.LEFT_FOOT, it.x(), it.y(), it.z(), visible(Lm.LEFT_ANKLE)) }
        world[Lm.RIGHT_ANKLE].let { sendSlot(SlotMap.RIGHT_FOOT, it.x(), it.y(), it.z(), visible(Lm.RIGHT_ANKLE)) }
        world[Lm.LEFT_KNEE].let { sendSlot(SlotMap.LEFT_KNEE, it.x(), it.y(), it.z(), visible(Lm.LEFT_KNEE)) }
        world[Lm.RIGHT_KNEE].let { sendSlot(SlotMap.RIGHT_KNEE, it.x(), it.y(), it.z(), visible(Lm.RIGHT_KNEE)) }
        world[Lm.LEFT_ELBOW].let { sendSlot(SlotMap.LEFT_ELBOW, it.x(), it.y(), it.z(), visible(Lm.LEFT_ELBOW)) }
        world[Lm.RIGHT_ELBOW].let { sendSlot(SlotMap.RIGHT_ELBOW, it.x(), it.y(), it.z(), visible(Lm.RIGHT_ELBOW)) }

        osc.sendBool("/avatar/parameters/TrackingActive", true)
        onTrackingQuality(visibleCount, totalJoints)
    }

    private fun trackFps(nowNs: Long) {
        frameCount++
        if (fpsWindowStartNs == 0L) fpsWindowStartNs = nowNs
        val elapsedS = (nowNs - fpsWindowStartNs) / 1_000_000_000.0
        if (elapsedS >= 1.0) {
            onFpsUpdate(frameCount / elapsedS)
            frameCount = 0
            fpsWindowStartNs = nowNs
        }
    }

    fun close() {
        landmarker.close()
    }

    // --- Frame conversion ---------------------------------------------------

    /** YUV_420_888 (CameraX default) -> ARGB Bitmap, with optional horizontal mirror for front camera. */
    private fun imageProxyToBitmap(imageProxy: ImageProxy, mirror: Boolean): Bitmap {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 90, out)
        val jpegBytes = out.toByteArray()
        var bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

        val rotation = imageProxy.imageInfo.rotationDegrees
        val matrix = android.graphics.Matrix()
        if (rotation != 0) matrix.postRotate(rotation.toFloat())
        if (mirror) matrix.postScale(-1f, 1f)
        if (rotation != 0 || mirror) {
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        return bitmap
    }

    private fun loadModelAsMappedBuffer(path: String): MappedByteBuffer {
        RandomAccessFile(path, "r").use { raf ->
            return raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
        }
    }
}

private fun <T> List<T>.getOrNull(index: Int): T? = if (index in indices) this[index] else null
