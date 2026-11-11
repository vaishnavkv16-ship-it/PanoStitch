package com.example.panostitch

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.stitching.Stitcher
import kotlin.math.max
import kotlin.math.min

enum class Mode(val label: String, val cv: Int) {
    PANORAMA("Panorama", Stitcher.PANORAMA),
    SCANS("Flat / Scans", Stitcher.SCANS)
}

data class StitchOptions(
    val mode: Mode = Mode.PANORAMA,
    val matchMegapix: Double = 0.6,      // resolution used for feature matching
    val maxInputEdge: Int = 2400,        // inputs are downscaled to this longest edge
    val maxOutputEdge: Int = 8000,       // keep <= 8192 so the GPU can draw it
    val confidence: Double = 1.0,        // lower = accept weaker matches
    val waveCorrect: Boolean = true,     // straightens horizon drift
    val autoCrop: Boolean = true,        // removes black borders
    val png: Boolean = false,
    val jpegQuality: Int = 95
)

sealed interface StitchResult {
    data class Success(val bitmap: Bitmap) : StitchResult
    data class Failure(val message: String) : StitchResult
}

object StitchEngine {

    fun stitch(bitmaps: List<Bitmap>, o: StitchOptions): StitchResult {
        val mats = ArrayList<Mat>()
        val pano = Mat()
        try {
            for (b in bitmaps) {
                val rgba = Mat()
                Utils.bitmapToMat(b, rgba)
                val bgr = Mat()
                Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                rgba.release()
                mats.add(bgr)
            }

            val stitcher = Stitcher.create(o.mode.cv).apply {
                setRegistrationResol(o.matchMegapix)
                setSeamEstimationResol(0.1)
                setCompositingResol(-1.0)
                setPanoConfidenceThresh(o.confidence)
                setWaveCorrection(o.waveCorrect)
            }

            val status = stitcher.stitch(mats, pano)
            if (status != Stitcher.OK) return StitchResult.Failure(explain(status))

            var out = pano
            if (o.autoCrop) out = cropBlackBorders(out)
            out = limitSize(out, o.maxOutputEdge)

            val rgba = Mat()
            Imgproc.cvtColor(out, rgba, Imgproc.COLOR_BGR2RGBA)
            val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, bmp)
            rgba.release()
            if (out !== pano) out.release()
            return StitchResult.Success(bmp)
        } finally {
            mats.forEach { it.release() }
            pano.release()
        }
    }

    private fun explain(status: Int) = when (status) {
        Stitcher.ERR_NEED_MORE_IMGS ->
            "Not enough overlap between photos. Use shots that overlap by about 30%, or lower the confidence."
        Stitcher.ERR_HOMOGRAPHY_EST_FAIL ->
            "Couldn't match the photos. Try removing blurry shots or switch the mode."
        Stitcher.ERR_CAMERA_PARAMS_ADJUST_FAIL ->
            "Camera alignment failed. Try turning off wave correction or using fewer photos."
        else -> "Stitching failed (code $status)."
    }

    private fun limitSize(src: Mat, maxEdge: Int): Mat {
        val longest = max(src.cols(), src.rows())
        if (longest <= maxEdge) return src
        val f = maxEdge.toDouble() / longest
        val dst = Mat()
        Imgproc.resize(src, dst, Size(src.cols() * f, src.rows() * f), 0.0, 0.0, Imgproc.INTER_AREA)
        return dst
    }

    /** Shrinks the bounding box of valid pixels until its edges are (almost) free of black. */
    private fun cropBlackBorders(src: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
        val mask = Mat()
        Imgproc.threshold(gray, mask, 1.0, 255.0, Imgproc.THRESH_BINARY)
        gray.release()

        val pts = Mat()
        Core.findNonZero(mask, pts)
        if (pts.empty()) { mask.release(); return src }
        val box = Imgproc.boundingRect(pts)
        pts.release()

        var l = box.x; var t = box.y
        var r = box.x + box.width; var b = box.y + box.height
        val step = max(2, min(box.width, box.height) / 400)
        val tolerance = 0.002

        fun blackFraction(x: Int, y: Int, w: Int, h: Int): Double {
            val sub = mask.submat(Rect(x, y, w, h))
            val f = 1.0 - Core.countNonZero(sub).toDouble() / (w * h)
            sub.release()
            return f
        }

        while (r - l > 64 && b - t > 64) {
            val top = blackFraction(l, t, r - l, 1)
            val bottom = blackFraction(l, b - 1, r - l, 1)
            val left = blackFraction(l, t, 1, b - t)
            val right = blackFraction(r - 1, t, 1, b - t)
            val worst = max(max(top, bottom), max(left, right))
            if (worst <= tolerance) break
            when (worst) {
                top -> t += step
                bottom -> b -= step
                left -> l += step
                else -> r -= step
            }
        }
        mask.release()
        val cropped = Mat(src, Rect(l, t, r - l, b - t)).clone()
        return cropped
    }
}
