package com.example.capture

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import com.example.model.RoiRect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer

/**
 * Manages MediaProjection + VirtualDisplay + ImageReader and crops ONLY the selected ROI
 * directly from the Image plane buffer to minimize RAM allocations.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val onProjectionStopped: () -> Unit
) {
    private val projectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var captureWidth: Int = 0
    private var captureHeight: Int = 0
    private var densityDpi: Int = 0

    // Reusable scratch buffers to keep GC pressure minimal during continuous capture
    private var scratchRowBytes: ByteArray = ByteArray(0)
    private var scratchRoiBuffer: ByteBuffer? = null
    private var reusableBitmap: Bitmap? = null

    @Volatile
    private var nextFrameSignal: CompletableDeferred<Unit>? = null

    @Volatile
    private var lastImageTimestampNs: Long = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            release()
            onProjectionStopped()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            if (width == captureWidth && height == captureHeight) return
            resizeCaptureSurface(width, height)
        }
    }

    fun start(resultCode: Int, resultData: Intent) {
        release()
        val bounds = ScreenMetricsHelper.getScreenBounds(context)
        captureWidth = bounds.width
        captureHeight = bounds.height
        densityDpi = bounds.densityDpi

        val projection = projectionManager.getMediaProjection(resultCode, resultData)
            ?: throw IllegalStateException("MediaProjection başlatılamadı")
        mediaProjection = projection

        // Android 14+ requires registering MediaProjection.Callback before createVirtualDisplay
        projection.registerCallback(projectionCallback, mainHandler)

        createOrResetVirtualDisplay(projection, captureWidth, captureHeight, densityDpi)
    }

    @SuppressLint("WrongConstant")
    private fun createOrResetVirtualDisplay(
        projection: MediaProjection,
        width: Int,
        height: Int,
        dpi: Int
    ) {
        imageReader?.close()
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        attachFrameListener(reader)
        imageReader = reader

        virtualDisplay?.release()
        virtualDisplay = projection.createVirtualDisplay(
            "SubLensCapture",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            mainHandler
        )
    }

    @SuppressLint("WrongConstant")
    private fun resizeCaptureSurface(newWidth: Int, newHeight: Int) {
        captureWidth = newWidth
        captureHeight = newHeight
        val oldReader = imageReader
        val newReader = ImageReader.newInstance(newWidth, newHeight, PixelFormat.RGBA_8888, 2)
        attachFrameListener(newReader)
        imageReader = newReader
        virtualDisplay?.resize(newWidth, newHeight, densityDpi)
        virtualDisplay?.surface = newReader.surface
        oldReader?.close()
    }

    private fun attachFrameListener(reader: ImageReader) {
        reader.setOnImageAvailableListener({
            lastImageTimestampNs = System.nanoTime()
            nextFrameSignal?.complete(Unit)
        }, mainHandler)
    }

    /**
     * Discards all stale frames sitting in the ImageReader buffer before a stealth capture pulse.
     */
    fun drainPendingFrame() {
        runCatching {
            while (true) {
                val img = imageReader?.acquireNextImage() ?: break
                img.close()
            }
        }
        val signal = CompletableDeferred<Unit>()
        nextFrameSignal = signal
    }

    /**
     * Waits past SurfaceFlinger's hide-overlay composite after [minValidTimestampNs] and acquires
     * ONLY a frame produced after the In-Place layer became invisible. Any older stale frame is
     * discarded so our own Turkish overlay text never leaks into OCR.
     */
    suspend fun awaitAndCaptureStealthRoi(
        roi: RoiRect,
        minValidTimestampNs: Long = 0L,
        settleMs: Long = 38L,
        maxWaitMs: Long = 110L
    ): Bitmap? {
        delay(settleMs)
        val deadlineMs = System.currentTimeMillis() + (maxWaitMs - settleMs).coerceAtLeast(16L)

        while (true) {
            val reader = imageReader ?: return null
            val image = reader.acquireLatestImage()
            if (image != null) {
                val ts = image.timestamp
                if (minValidTimestampNs <= 0L || ts <= 0L || ts >= minValidTimestampNs) {
                    nextFrameSignal = null
                    return try {
                        cropPlaneToBitmap(image, roi)
                    } finally {
                        image.close()
                    }
                }
                // Stale pre-hide frame containing our own overlay -> discard and wait for next VSync
                image.close()
            }

            val remaining = deadlineMs - System.currentTimeMillis()
            if (remaining <= 0L) {
                nextFrameSignal = null
                return null
            }

            val signal = CompletableDeferred<Unit>().also { nextFrameSignal = it }
            withTimeoutOrNull(remaining.coerceAtMost(24L)) {
                signal.await()
            }
        }
    }

    /**
     * Acquires the latest frame and crops only the ROI pixels into a small Bitmap.
     * Returns null if no new frame is available yet.
     */
    fun captureCroppedRoi(roi: RoiRect): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        return try {
            cropPlaneToBitmap(image, roi)
        } finally {
            image.close()
        }
    }

    private fun cropPlaneToBitmap(image: Image, roi: RoiRect): Bitmap? {
        val imgWidth = image.width
        val imgHeight = image.height
        if (imgWidth <= 0 || imgHeight <= 0) return null

        val clamped = roi.clampToScreen(imgWidth, imgHeight, minWidth = 32, minHeight = 32)
        val left = clamped.x.coerceIn(0, imgWidth - 1)
        val top = clamped.y.coerceIn(0, imgHeight - 1)
        val cropW = clamped.width.coerceIn(1, imgWidth - left)
        val cropH = clamped.height.coerceIn(1, imgHeight - top)

        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        if (pixelStride != 4) {
            // Safe fallback if device returns non-4-byte pixel stride
            val fullWidth = rowStride / pixelStride.coerceAtLeast(1)
            val fullBmp = Bitmap.createBitmap(fullWidth.coerceAtLeast(imgWidth), imgHeight, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            fullBmp.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(fullBmp, left, top, cropW, cropH)
            fullBmp.recycle()
            return cropped
        }

        val rowBytes = cropW * 4
        if (scratchRowBytes.size < rowBytes) {
            scratchRowBytes = ByteArray(rowBytes)
        }

        val neededCapacity = rowBytes * cropH
        var roiBuf = scratchRoiBuffer
        if (roiBuf == null || roiBuf.capacity() < neededCapacity) {
            roiBuf = ByteBuffer.allocateDirect(neededCapacity)
            scratchRoiBuffer = roiBuf
        }
        roiBuf.clear()
        roiBuf.limit(neededCapacity)

        val bufferLimit = buffer.limit()
        for (row in 0 until cropH) {
            val offset = (top + row) * rowStride + left * 4
            if (offset + rowBytes > bufferLimit) break
            buffer.position(offset)
            buffer.get(scratchRowBytes, 0, rowBytes)
            roiBuf.put(scratchRowBytes, 0, rowBytes)
        }
        roiBuf.rewind()

        var outBmp = reusableBitmap
        if (outBmp == null || outBmp.isRecycled || outBmp.width != cropW || outBmp.height != cropH) {
            outBmp?.recycle()
            outBmp = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
            reusableBitmap = outBmp
        }
        outBmp.copyPixelsFromBuffer(roiBuf)
        return outBmp
    }

    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
        } catch (_: Exception) {
        }
        mediaProjection?.stop()
        mediaProjection = null
        reusableBitmap?.recycle()
        reusableBitmap = null
        scratchRoiBuffer = null
    }
}
