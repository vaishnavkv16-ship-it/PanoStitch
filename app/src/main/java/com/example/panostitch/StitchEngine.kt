package com.example.panostitch

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.KeyPoint
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgproc.Imgproc
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

enum class Mode(val label: String, val hint: String) {
    PANORAMA("Panorama", "Best for sweeping shots. Keeps lines natural, handles wide angles."),
    PERSPECTIVE("Perspective", "For buildings and short sweeps taken from one spot."),
    FLAT("Flat", "For documents, maps, whiteboards and walls.")
}

data class StitchOptions(
    val mode: Mode = Mode.PANORAMA,
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
    data class Success(val bitmap: Bitmap, val note: String?) : StitchResult
    data class Failure(val message: String) : StitchResult
}

/**
 * Panorama stitcher built only from OpenCV core / features2d / calib3d / imgproc.
 *  - Photos can be given in ANY order: all pairs are matched and a maximum spanning tree decides the layout.
 *  - Panorama mode warps every photo onto a cylinder first, so wide sweeps don't stretch.
 *  - Seams are blended with distance-weighted feathering and optional brightness balancing.
 */
object StitchEngine {

    private const val MAX_CANVAS_PIXELS = 40_000_000.0

    private class Layer(val img: Mat, val mask: Mat) {
        fun release() { img.release(); mask.release() }
    }
    private class Feat(val kp: Array<KeyPoint>, val desc: Mat, val scale: Double)
    private class Edge(val a: Int, val b: Int, val h: Mat, val score: Int) // h maps b -> a

    fun stitch(bitmaps: List<Bitmap>, focals35: List<Double?>, o: StitchOptions): StitchResult {
        val layers = ArrayList<Layer>()
        val canvas = Mat()
        val weights = Mat()
        try {
            val bgrs = bitmaps.map { toBgr(it) }
            val f = if (o.mode == Mode.PANORAMA) focalPx(bgrs, focals35) else 0.0
            for (bgr in bgrs) {
                if (o.mode == Mode.PANORAMA) {
                    layers.add(cylindrical(bgr, f)); bgr.release()
                } else {
                    layers.add(Layer(bgr, Mat(bgr.size(), CvType.CV_8UC1, Scalar(255.0))))
                }
            }
            val n = layers.size

            // 1) features + pairwise matching
            val sift = SIFT.create(3000)
            val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE)
            val feats = layers.map { detect(it.img, o.matchMegapix, sift) }
            val edges = ArrayList<Edge>()
            for (i in 0 until n) for (j in i + 1 until n) {
                if (n > 12 && j - i > 4) continue          // many photos: only compare near neighbours
                val r = pairTransform(feats[j], feats[i], o, matcher) ?: continue
                edges.add(Edge(i, j, r.first, r.second))
            }

            // 2) maximum spanning tree (strongest overlaps win)
            val parent = IntArray(n) { it }
            fun find(x: Int): Int {
                var a = x
                while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }
                return a
            }
            val tree = ArrayList<Edge>()
            for (e in edges.sortedByDescending { it.score }) {
                val ra = find(e.a); val rb = find(e.b)
                if (ra != rb) { parent[ra] = rb; tree.add(e) }
            }
            val comp = (0 until n).groupBy { find(it) }.values.maxByOrNull { it.size }!!
            if (comp.size < 2) return StitchResult.Failure(
                "None of the photos could be matched. Make sure neighbouring photos overlap by about 30%, " +
                    "avoid blurry shots, or raise Match strictness in Settings."
            )

            // 3) root = centre of the tree, so distortion is spread evenly
            val adj = Array(n) { ArrayList<Pair<Int, Edge>>() }
            for (e in tree) { adj[e.a].add(e.b to e); adj[e.b].add(e.a to e) }
            fun bfs(start: Int): Pair<IntArray, IntArray> {
                val dist = IntArray(n) { -1 }
                val par = IntArray(n) { -1 }
                val q = ArrayDeque<Int>()
                dist[start] = 0; q.add(start)
                while (q.isNotEmpty()) {
                    val u = q.removeFirst()
                    for ((v, _) in adj[u]) if (dist[v] < 0) { dist[v] = dist[u] + 1; par[v] = u; q.add(v) }
                }
                return dist to par
            }
            val (d0, _) = bfs(comp[0])
            val far = comp.maxByOrNull { d0[it] }!!
            val (d1, p1) = bfs(far)
            val far2 = comp.maxByOrNull { d1[it] }!!
            val path = ArrayList<Int>()
            var c = far2
            while (c != -1) { path.add(c); c = p1[c] }
            val root = path[path.size / 2]

            // 4) transforms of every photo into the root's frame (breadth-first)
            val toRoot = arrayOfNulls<Mat>(n)
            toRoot[root] = Mat.eye(3, 3, CvType.CV_64F)
            val order = ArrayList<Int>()
            order.add(root)
            val seen = BooleanArray(n); seen[root] = true
            var idx = 0
            while (idx < order.size) {
                val p = order[idx++]
                for ((child, e) in adj[p]) {
                    if (seen[child]) continue
                    seen[child] = true
                    val h = if (e.b == child) e.h else e.h.inv()   // child -> parent
                    toRoot[child] = toRoot[p]!!.matMul(h)
                    order.add(child)
                }
            }

            // 5) canvas bounds
            var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
            for (i in order) {
                for (p in projectCorners(layers[i].img.cols(), layers[i].img.rows(), toRoot[i]!!)) {
                    if (!p.x.isFinite() || !p.y.isFinite()) return unstable()
                    minX = min(minX, p.x); maxX = max(maxX, p.x)
                    minY = min(minY, p.y); maxY = max(maxY, p.y)
                }
            }
            val w0 = maxX - minX; val h0 = maxY - minY
            if (w0 < 8 || h0 < 8) return unstable()
            val refArea = layers[root].img.cols().toDouble() * layers[root].img.rows()
            if (w0 * h0 > refArea * order.size * order.size * 4) return unstable()

            var s = min(1.0, o.maxOutputEdge / max(w0, h0))
            s = min(s, sqrt(MAX_CANVAS_PIXELS / (w0 * h0)))
            val cw = ceil(w0 * s).toInt().coerceAtLeast(1)
            val ch = ceil(h0 * s).toInt().coerceAtLeast(1)
            val base = mat3(s, 0.0, -minX * s, 0.0, s, -minY * s, 0.0, 0.0, 1.0)

            canvas.create(ch, cw, CvType.CV_8UC3); canvas.setTo(Scalar(0.0, 0.0, 0.0))
            weights.create(ch, cw, CvType.CV_32F); weights.setTo(Scalar(0.0))

            // 6) blend, starting from the centre and growing outwards
            for (i in order) composite(layers[i], base.matMul(toRoot[i]!!), canvas, weights, o.balanceExposure)

            // 7) crop + convert
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

            val skipped = n - order.size
            val note = if (skipped > 0)
                "$skipped photo${if (skipped > 1) "s" else ""} didn't overlap enough and ${if (skipped > 1) "were" else "was"} left out."
            else null
            return StitchResult.Success(bmp, note)
        } finally {
            layers.forEach { it.release() }
            canvas.release()
            weights.release()
        }
    }

    private fun unstable() = StitchResult.Failure(
        "The photos produced an unstable layout. Try Flat mode, fewer photos, or photos with better overlap."
    )

    private fun toBgr(b: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(b, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        return bgr
    }

    // ───────────── cylindrical projection ─────────────

    /** Focal length in pixels from EXIF 35mm-equivalent values (default 26mm, typical phone camera). */
    private fun focalPx(imgs: List<Mat>, focals: List<Double?>): Double {
        val vals = imgs.indices.map { i ->
            ((focals.getOrNull(i) ?: 26.0) / 36.0) * max(imgs[i].cols(), imgs[i].rows())
        }.sorted()
        return vals[vals.size / 2]
    }

    private fun cylindrical(bgr: Mat, f: Double): Layer {
        val w = bgr.cols(); val h = bgr.rows()
        val cx = w / 2.0; val cy = h / 2.0
        val outW = max(2, Math.round(2 * f * atan(cx / f)).toInt())
        val ocx = outW / 2.0
        val rowX = FloatArray(outW)
        val invCos = DoubleArray(outW)
        for (x in 0 until outW) {
            val th = (x - ocx) / f
            rowX[x] = (f * tan(th) + cx).toFloat()
            invCos[x] = 1.0 / cos(th)
        }
        val mapX = Mat(h, outW, CvType.CV_32F)
        val mapY = Mat(h, outW, CvType.CV_32F)
        val rowY = FloatArray(outW)
        for (y in 0 until h) {
            for (x in 0 until outW) rowY[x] = ((y - cy) * invCos[x] + cy).toFloat()
            mapX.put(y, 0, rowX)
            mapY.put(y, 0, rowY)
        }
        val out = Mat()
        Imgproc.remap(bgr, out, mapX, mapY, Imgproc.INTER_LINEAR)
        val ones = Mat(bgr.size(), CvType.CV_8UC1, Scalar(255.0))
        val mask = Mat()
        Imgproc.remap(ones, mask, mapX, mapY, Imgproc.INTER_NEAREST)
        ones.release(); mapX.release(); mapY.release()
        return Layer(out, mask)
    }

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

    /** Transform mapping points of [b] into [a], plus its inlier count. Null when the photos don't match. */
    private fun pairTransform(b: Feat, a: Feat, o: StitchOptions, matcher: DescriptorMatcher): Pair<Mat, Int>? {
        if (a.desc.rows() < 8 || b.desc.rows() < 8) return null
        val knn = ArrayList<MatOfDMatch>()
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

        val sMat = MatOfPoint2f().apply { fromList(src) }
        val dMat = MatOfPoint2f().apply { fromList(dst) }
        val h: Mat
        val inliers: Int
        if (o.mode == Mode.PERSPECTIVE) {
            val mask = Mat()
            h = Calib3d.findHomography(sMat, dMat, Calib3d.RANSAC, 4.0, mask)
            if (h.empty()) return null
            inliers = Core.countNonZero(mask)
        } else {
            // cylindrical / flat: rotation + uniform scale + shift is enough and far more stable
            val aff = Calib3d.estimateAffinePartial2D(sMat, dMat)
            if (aff.empty()) return null
            h = Mat.eye(3, 3, CvType.CV_64F)
            aff.copyTo(h.submat(0, 2, 0, 3))
            val m = DoubleArray(6) { aff.get(it / 3, it % 3)[0] }
            var count = 0
            for (k in src.indices) {
                val x = src[k].x; val y = src[k].y
                val px = m[0] * x + m[1] * y + m[2]
                val py = m[3] * x + m[4] * y + m[5]
                if (hypot(px - dst[k].x, py - dst[k].y) < 4.0) count++
            }
            inliers = count
        }
        if (inliers < 12 || inliers < 0.25 * src.size) return null
        val det = h.get(0, 0)[0] * h.get(1, 1)[0] - h.get(0, 1)[0] * h.get(1, 0)[0]
        return if (det in 0.25..4.0) h to inliers else null
    }

    // ───────────── warping & blending ─────────────

    private fun composite(layer: Layer, f: Mat, canvas: Mat, weights: Mat, balance: Boolean) {
        val corners = projectCorners(layer.img.cols(), layer.img.rows(), f)
        val x0 = floor(corners.minOf { it.x }).toInt().coerceIn(0, canvas.cols())
        val y0 = floor(corners.minOf { it.y }).toInt().coerceIn(0, canvas.rows())
        val x1 = ceil(corners.maxOf { it.x }).toInt().coerceIn(0, canvas.cols())
        val y1 = ceil(corners.maxOf { it.y }).toInt().coerceIn(0, canvas.rows())
        if (x1 - x0 < 2 || y1 - y0 < 2) return
        val roi = Rect(x0, y0, x1 - x0, y1 - y0)
        val size = Size(roi.width.toDouble(), roi.height.toDouble())
        val shift = mat3(1.0, 0.0, -x0.toDouble(), 0.0, 1.0, -y0.toDouble(), 0.0, 0.0, 1.0).matMul(f)

        val warped = Mat()
        Imgproc.warpPerspective(layer.img, warped, shift, size, Imgproc.INTER_LINEAR)
        val mask = Mat()
        Imgproc.warpPerspective(layer.mask, mask, shift, size, Imgproc.INTER_NEAREST)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.erode(mask, mask, kernel)
        val dist = Mat()
        Imgproc.distanceTransform(mask, dist, Imgproc.DIST_L2, 3)
        Core.multiply(dist, dist, dist)               // squared: narrower, ghost-free transition
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
