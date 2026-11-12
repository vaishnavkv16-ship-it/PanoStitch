package com.example.panostitch

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.KeyPoint
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class Mode(val label: String) {
    PERSPECTIVE("Panorama"),   // full homography
    FLAT("Flat / Scans")       // rotation + scale + shift only (no perspective)
}

data class StitchOptions(
    val mode: Mode = Mode.PERSPECTIVE,
    val matchMegapix: Double = 0.6,      // resolution used for feature matching
    val maxInputEdge: Int = 2400,        // inputs are downscaled to this longest edge
    val maxOutputEdge: Int = 8000,       // keep <= 8192 so the GPU can draw it
    val matchRatio: Double = 0.75,       // Lowe ratio: lower = stricter matching
    val balanceExposure: Boolean = true, // even out brightness between photos
    val autoCrop: Boolean = true,        // removes empty borders
    val png: Boolean = false,
    val jpegQuality: Int = 95
)

sealed interface StitchResult {
    data class Success(val bitmap: Bitmap) : StitchResult
    data class Failure(val message: String) : StitchResult
}

/**
 * Pure OpenCV (core / features2d / calib3d / imgproc) panorama stitcher.
 * Photos must be given in sweep order (left→right or right→left).
 */
object StitchEngine {

    private const val MAX_CANVAS_PIXELS = 40_000_000.0

    private class Feat(val kp: Array<KeyPoint>, val desc: Mat, val scale: Double)

    fun stitch(bitmaps: List<Bitmap>, o: StitchOptions): StitchResult {
        val imgs = ArrayList<Mat>()
        val canvas = Mat()
        val weights = Mat()
        try {
            for (b in bitmaps) {
                val rgba = Mat()
                Utils.bitmapToMat(b, rgba)
                val bgr = Mat()
                Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                rgba.release()
                imgs.add(bgr)
            }
            val n = imgs.size

            // 1) features
            val sift = SIFT.create(4000)
            val feats = imgs.map { detect(it, o.matchMegapix, sift) }
            val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE)

            // 2) pairwise transforms: image i -> image i-1, chained into one global frame
            val chain = ArrayList<Mat>()
            chain.add(Mat.eye(3, 3, CvType.CV_64F))
            for (i in 1 until n) {
                val h = pairTransform(feats[i], feats[i - 1], o, matcher)
                    ?: return StitchResult.Failure(
                        "Photos ${i} and ${i + 1} don't match well. Pick photos in sweep order with about " +
                            "30% overlap, remove blurry ones, or raise Match strictness."
                    )
                chain.add(chain[i - 1].matMul(h))
            }

            // 3) use the middle photo as reference to limit stretching
            val refInv = chain[n / 2].inv()
            val toRef = chain.map { refInv.matMul(it) }

            // 4) canvas bounds
            var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
            for (i in 0 until n) {
                for (p in projectCorners(imgs[i].cols(), imgs[i].rows(), toRef[i])) {
                    if (!p.x.isFinite() || !p.y.isFinite()) return unstable()
                    minX = min(minX, p.x); maxX = max(maxX, p.x)
                    minY = min(minY, p.y); maxY = max(maxY, p.y)
                }
            }
            val w0 = maxX - minX; val h0 = maxY - minY
            if (w0 < 8 || h0 < 8) return unstable()
            val refArea = imgs[n / 2].cols().toDouble() * imgs[n / 2].rows()
            if (w0 * h0 > refArea * n * n * 4) return unstable()

            var s = min(1.0, o.maxOutputEdge / max(w0, h0))
            s = min(s, sqrt(MAX_CANVAS_PIXELS / (w0 * h0)))
            val cw = ceil(w0 * s).toInt().coerceAtLeast(1)
            val ch = ceil(h0 * s).toInt().coerceAtLeast(1)

            val base = mat3(s, 0.0, -minX * s, 0.0, s, -minY * s, 0.0, 0.0, 1.0)
            canvas.create(ch, cw, CvType.CV_8UC3); canvas.setTo(Scalar(0.0, 0.0, 0.0))
            weights.create(ch, cw, CvType.CV_32F); weights.setTo(Scalar(0.0))

            // 5) warp + feather blend one photo at a time
            for (i in 0 until n) {
                val f = base.matMul(toRef[i])
                composite(imgs[i], f, canvas, weights, o.balanceExposure)
            }

            // 6) crop + convert
            var result: Mat = canvas
            if (o.autoCrop) {
                val valid = Mat()
                Core.compare(weights, Scalar(0.0), valid, Core.CMP_GT)
                result = cropEmpty(canvas, valid)
                valid.release()
            }
            val rgba = Mat()
            Imgproc.cvtColor(result, rgba, Imgproc.COLOR_BGR2RGBA)
            val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, bmp)
            rgba.release()
            if (result !== canvas) result.release()
            return StitchResult.Success(bmp)
        } finally {
            imgs.forEach { it.release() }
            canvas.release()
            weights.release()
        }
    }

    private fun unstable() = StitchResult.Failure(
        "The photos produced an unstable layout. Try Flat / Scans mode, fewer photos, or a smaller sweep angle."
    )

    // ───────────── features & matching ─────────────

    private fun detect(bgr: Mat, megapix: Double, sift: SIFT): Feat {
        val scale = min(1.0, sqrt(megapix * 1e6 / (bgr.cols().toDouble() * bgr.rows())))
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val small = Mat()
        if (scale < 1.0) Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA)
        else gray.copyTo(small)
        val kps = MatOfKeyPoint()
        val desc = Mat()
        sift.detectAndCompute(small, Mat(), kps, desc)
        gray.release(); small.release()
        return Feat(kps.toArray(), desc, scale)
    }

    /** Transform mapping points of [b] into the coordinate system of [a] (full-resolution pixels). */
    private fun pairTransform(b: Feat, a: Feat, o: StitchOptions, matcher: DescriptorMatcher): Mat? {
        if (a.desc.rows() < 8 || b.desc.rows() < 8) return null
        val knn = ArrayList<org.opencv.core.MatOfDMatch>()
        matcher.knnMatch(b.desc, a.desc, knn, 2)
        val src = ArrayList<Point>()
        val dst = ArrayList<Point>()
        for (m in knn) {
            val d = m.toArray()
            if (d.size == 2 && d[0].distance < o.matchRatio * d[1].distance) {
                val p = b.kp[d[0].queryIdx].pt
                val q = a.kp[d[0].trainIdx].pt
                src.add(Point(p.x / b.scale, p.y / b.scale))
                dst.add(Point(q.x / a.scale, q.y / a.scale))
            }
            m.release()
        }
        if (src.size < 12) return null

        val s = MatOfPoint2f().apply { fromList(src) }
        val d = MatOfPoint2f().apply { fromList(dst) }
        val h: Mat
        if (o.mode == Mode.FLAT) {
            val affine = Calib3d.estimateAffinePartial2D(s, d)
            if (affine.empty()) return null
            h = Mat.eye(3, 3, CvType.CV_64F)
            affine.copyTo(h.submat(0, 2, 0, 3))
        } else {
            val inliers = Mat()
            h = Calib3d.findHomography(s, d, Calib3d.RANSAC, 4.0, inliers)
            if (h.empty() || Core.countNonZero(inliers) < 10) return null
        }
        val det = h.get(0, 0)[0] * h.get(1, 1)[0] - h.get(0, 1)[0] * h.get(1, 0)[0]
        return if (det in 0.25..4.0) h else null
    }

    // ───────────── warping & blending ─────────────

    private fun composite(img: Mat, f: Mat, canvas: Mat, weights: Mat, balance: Boolean) {
        val corners = projectCorners(img.cols(), img.rows(), f)
        val x0 = floor(corners.minOf { it.x }).toInt().coerceIn(0, canvas.cols())
        val y0 = floor(corners.minOf { it.y }).toInt().coerceIn(0, canvas.rows())
        val x1 = ceil(corners.maxOf { it.x }).toInt().coerceIn(0, canvas.cols())
        val y1 = ceil(corners.maxOf { it.y }).toInt().coerceIn(0, canvas.rows())
        if (x1 - x0 < 2 || y1 - y0 < 2) return
        val roi = Rect(x0, y0, x1 - x0, y1 - y0)
        val size = Size(roi.width.toDouble(), roi.height.toDouble())
        val shift = mat3(1.0, 0.0, -x0.toDouble(), 0.0, 1.0, -y0.toDouble(), 0.0, 0.0, 1.0).matMul(f)

        val warped = Mat()
        Imgproc.warpPerspective(img, warped, shift, size, Imgproc.INTER_LINEAR)
        val ones = Mat(img.size(), CvType.CV_8UC1, Scalar(255.0))
        val mask = Mat()
        Imgproc.warpPerspective(ones, mask, shift, size, Imgproc.INTER_NEAREST)
        ones.release()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.erode(mask, mask, kernel)
        val dist = Mat()
        Imgproc.distanceTransform(mask, dist, Imgproc.DIST_L2, 3)   // feather weight = distance to edge
        mask.release()

        val outRoi = canvas.submat(roi)
        val wRoi = weights.submat(roi)

        if (balance) balanceGain(warped, outRoi, wRoi, dist)

        val w3 = Mat(); Core.merge(listOf(dist, dist, dist), w3)
        val a3 = Mat(); Core.merge(listOf(wRoi, wRoi, wRoi), a3)
        val outF = Mat(); outRoi.convertTo(outF, CvType.CV_32FC3)
        val imgF = Mat(); warped.convertTo(imgF, CvType.CV_32FC3)
        warped.release()

        Core.multiply(outF, a3, outF); a3.release()
        Core.multiply(imgF, w3, imgF); w3.release()
        Core.add(outF, imgF, outF); imgF.release()

        val den = Mat()
        Core.add(wRoi, dist, den)
        Core.add(den, Scalar(1e-6), den)
        val d3 = Mat(); Core.merge(listOf(den, den, den), d3); den.release()
        Core.divide(outF, d3, outF); d3.release()

        outF.convertTo(outRoi, CvType.CV_8UC3); outF.release()
        Core.add(wRoi, dist, wRoi)
        dist.release(); outRoi.release(); wRoi.release()
    }

    /** Scales [img] per channel so its brightness matches what is already on the canvas in the overlap. */
    private fun balanceGain(img: Mat, outRoi: Mat, wRoi: Mat, newW: Mat) {
        val a = Mat(); val b = Mat(); val overlap = Mat()
        Core.compare(wRoi, Scalar(0.0), a, Core.CMP_GT)
        Core.compare(newW, Scalar(0.0), b, Core.CMP_GT)
        Core.bitwise_and(a, b, overlap)
        a.release(); b.release()
        if (Core.countNonZero(overlap) > 500) {
            val have = Core.mean(outRoi, overlap).`val`
            val mine = Core.mean(img, overlap).`val`
            val g = DoubleArray(3) { ((have[it] + 1.0) / (mine[it] + 1.0)).coerceIn(0.75, 1.33) }
            Core.multiply(img, Scalar(g[0], g[1], g[2]), img)
        }
        overlap.release()
    }

    private fun projectCorners(w: Int, h: Int, m: Mat): List<Point> {
        val src = MatOfPoint2f(
            Point(0.0, 0.0), Point(w.toDouble(), 0.0),
            Point(w.toDouble(), h.toDouble()), Point(0.0, h.toDouble())
        )
        val dst = MatOfPoint2f()
        Core.perspectiveTransform(src, dst, m)
        return dst.toList()
    }

    private fun mat3(vararg v: Double): Mat {
        val m = Mat(3, 3, CvType.CV_64F)
        m.put(0, 0, *v)
        return m
    }

    /** Shrinks the bounding box of covered pixels until its edges are (almost) fully covered. */
    private fun cropEmpty(src: Mat, valid: Mat): Mat {
        val pts = Mat()
        Core.findNonZero(valid, pts)
        if (pts.empty()) return src
        val box = Imgproc.boundingRect(pts)
        pts.release()

        var l = box.x; var t = box.y
        var r = box.x + box.width; var b = box.y + box.height
        val step = max(2, min(box.width, box.height) / 400)

        fun emptyFraction(x: Int, y: Int, w: Int, h: Int): Double {
            val sub = valid.submat(Rect(x, y, w, h))
            val f = 1.0 - Core.countNonZero(sub).toDouble() / (w * h)
            sub.release()
            return f
        }

        while (r - l > 64 && b - t > 64) {
            val top = emptyFraction(l, t, r - l, 1)
            val bottom = emptyFraction(l, b - 1, r - l, 1)
            val left = emptyFraction(l, t, 1, b - t)
            val right = emptyFraction(r - 1, t, 1, b - t)
            val worst = max(max(top, bottom), max(left, right))
            if (worst <= 0.002) break
            when (worst) {
                top -> t += step
                bottom -> b -= step
                left -> l += step
                else -> r -= step
            }
        }
        return Mat(src, Rect(l, t, r - l, b - t)).clone()
    }
}
