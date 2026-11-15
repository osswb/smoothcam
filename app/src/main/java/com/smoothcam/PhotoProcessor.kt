package com.smoothcam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.ExifInterface
import android.media.Image
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Turns a short burst of zero-shutter-lag YUV frames (or a single 50 MP frame) into an iPhone-style photo, saved as a
 * plain SDR JPEG (no HDR gain map, so it never "brightens up" later in the gallery).
 *
 * Pipeline:
 *  1. Multi-frame merge (a light take on Deep Fusion / HDR+): align each frame to the newest one,
 *     then average them. Pixels that don't match the reference (motion, misalignment) are taken
 *     from the reference only, so edges stay sharp while flat areas get cleaner.
 *  2. Local tone mapping: lift shadows based on a blurred brightness map, pull very bright regions
 *     down slightly, the "bright, open shadows" iPhone look.
 *  3. Global tone curve: slightly brighter midtones, plus a soft shoulder so highlights roll off
 *     smoothly instead of clipping.
 *  4. Color: slightly warm white balance and gentle vibrance (boosts muted colors more than
 *     already saturated ones, which keeps skin tones natural).
 *  5. Fine-detail sharpening on luma, skipping tiny differences so noise isn't amplified.
 */
object PhotoProcessor {
    private const val TAG = "PhotoProcessor"

    // Look tuning
    private const val SHADOW_LIFT = 0.45f      // max extra gain in the darkest regions (~+0.5 EV)
    private const val HIGHLIGHT_PULL = 0.08f   // gain reduction in the brightest regions
    private const val MIDTONE_GAMMA = 0.95     // < 1 brightens midtones (128 -> ~142)
    private const val SHOULDER_KNEE = 0.80     // where highlight roll-off starts (0..1)
    private const val BLACK_POINT = 0.008
    private const val WARM_R = 1.025f
    private const val WARM_B = 0.975f
    private const val VIBRANCE = 0.18f
    private const val SHARPEN = 0.45f
    private const val SHARPEN_THRESHOLD = 2f
    private const val JPEG_QUALITY = 95

    private val threadCount = max(2, Runtime.getRuntime().availableProcessors())

    /** A camera frame copied out of its Image, so the camera's buffer can be returned at once. */
    class YuvFrame(val width: Int, val height: Int, val y: ByteArray, val u: ByteArray, val v: ByteArray) {
        companion object {
            fun copyOf(img: Image): YuvFrame {
                val w = img.width
                val h = img.height
                val y = ByteArray(w * h)
                val u = ByteArray((w / 2) * (h / 2))
                val v = ByteArray((w / 2) * (h / 2))
                PhotoProcessor.copyPlane(img.planes[0], w, h, y)
                PhotoProcessor.copyPlane(img.planes[1], w / 2, h / 2, u)
                PhotoProcessor.copyPlane(img.planes[2], w / 2, h / 2, v)
                return YuvFrame(w, h, y, u, v)
            }
        }
    }

    /**
     * frames[0] must be the newest frame (the moment the shutter was pressed).
     * Returns the saved photo's content URI. The frames' arrays are reused as scratch space.
     */
    fun processAndSave(context: Context, frames: List<YuvFrame>, iso: Int, orientationDeg: Int): Uri {
        val w = frames[0].width
        val h = frames[0].height
        val nv21 = render(frames, iso)
        return save(context, nv21, w, h, orientationDeg)
    }

    /**
     * Applies the look to an already developed 8-bit YUV image (from the RAW+ pipeline) and saves it.
     * RAW+ has restored brightness itself, so the midtone lift is skipped there.
     */
    fun finishAndSave(context: Context, y: ByteArray, u: ByteArray, v: ByteArray, w: Int, h: Int,
                      orientationDeg: Int, rawLook: Boolean): Uri {
        val nv21 = toneMapToNv21(y, u, v, w, h, if (rawLook) 1.0 else MIDTONE_GAMMA)
        return save(context, nv21, w, h, orientationDeg)
    }

    /** A small, quickly made preview of a frame for the gallery button (nearest-neighbour, no look applied). */
    fun quickThumbnail(f: YuvFrame, orientationDeg: Int, size: Int = 160): Bitmap {
        val scale = max(1, min(f.width, f.height) / size)
        val tw = f.width / scale
        val th = f.height / scale
        val px = IntArray(tw * th)
        val cw = f.width / 2
        for (ty in 0 until th) {
            val sy = ty * scale
            for (tx in 0 until tw) {
                val sx = tx * scale
                val yy = (f.y[sy * f.width + sx].toInt() and 0xFF).toFloat()
                val ci = (sy / 2) * cw + sx / 2
                val u = (f.u[ci].toInt() and 0xFF) - 128f
                val v = (f.v[ci].toInt() and 0xFF) - 128f
                val r = (yy + 1.402f * v).toInt().coerceIn(0, 255)
                val g = (yy - 0.344136f * u - 0.714136f * v).toInt().coerceIn(0, 255)
                val b = (yy + 1.772f * u).toInt().coerceIn(0, 255)
                px[ty * tw + tx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bmp = Bitmap.createBitmap(px, tw, th, Bitmap.Config.ARGB_8888)
        if (orientationDeg == 0) return bmp
        val m = Matrix().apply { postRotate(orientationDeg.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, tw, th, m, true)
    }

    // ---------------------------------------------------------------- merge

    private fun render(frames: List<YuvFrame>, iso: Int): ByteArray {
        val ref = frames[0]
        val w = ref.width
        val h = ref.height
        val cw = w / 2
        val ch = h / 2
        val refY = ref.y
        val refU = ref.u
        val refV = ref.v

        val n = frames.size
        if (n > 1) {
            val ySum = toShorts(refY)
            val uSum = toShorts(refU)
            val vSum = toShorts(refV)

            // Higher ISO = more noise = allow larger differences before treating a pixel as "moved".
            val tY = (12 + iso / 80).coerceIn(12, 48)
            val tC = max(8, tY * 2 / 3)

            val refSmall = downscale(refY, w, h, 8)
            val sw = w / 8
            val sh = h / 8

            for (k in 1 until n) {
                val f = frames[k]
                if (f.width != w || f.height != h) continue
                val small = downscale(f.y, w, h, 8)
                val coarse = searchCoarse(refSmall, small, sw, sh, 4)
                val (dx, dy) = searchFine(refY, f.y, w, h, coarse.first * 8, coarse.second * 8, 6)
                Log.d(TAG, "frame $k shift dx=$dx dy=$dy")

                accumulate(refY, f.y, ySum, w, h, dx, dy, tY)
                accumulate(refU, f.u, uSum, cw, ch, dx / 2, dy / 2, tC)
                accumulate(refV, f.v, vSum, cw, ch, dx / 2, dy / 2, tC)
            }
            average(ySum, refY, n)
            average(uSum, refU, n)
            average(vSum, refV, n)
        }

        return toneMapToNv21(refY, refU, refV, w, h)
    }

    private fun toShorts(src: ByteArray): ShortArray {
        val out = ShortArray(src.size)
        val rows = 64
        parallelRows((src.size + rows - 1) / rows) { r0, r1 ->
            val end = min(src.size, r1 * rows)
            for (i in r0 * rows until end) out[i] = (src[i].toInt() and 0xFF).toShort()
        }
        return out
    }

    /** Copies an Image plane into a tightly packed array, handling row and pixel strides. */
    internal fun copyPlane(plane: Image.Plane, w: Int, h: Int, out: ByteArray) {
        val rowStride = plane.rowStride
        val ps = plane.pixelStride
        val src = plane.buffer
        parallelRows(h) { y0, y1 ->
            val b = src.duplicate()
            val row = ByteArray(rowStride)
            for (y in y0 until y1) {
                val start = y * rowStride
                val len = min(rowStride, b.limit() - start)
                b.position(start)
                b.get(row, 0, len)
                val o = y * w
                if (ps == 1) {
                    System.arraycopy(row, 0, out, o, w)
                } else {
                    for (x in 0 until w) out[o + x] = row[x * ps]
                }
            }
        }
    }

    private fun downscale(src: ByteArray, w: Int, h: Int, f: Int): IntArray {
        val sw = w / f
        val sh = h / f
        val out = IntArray(sw * sh)
        for (sy in 0 until sh) {
            for (sx in 0 until sw) {
                var sum = 0
                for (yy in 0 until f) {
                    val row = (sy * f + yy) * w + sx * f
                    for (xx in 0 until f) sum += src[row + xx].toInt() and 0xFF
                }
                out[sy * sw + sx] = sum / (f * f)
            }
        }
        return out
    }

    /** Whole-frame translation search on the downscaled image. */
    private fun searchCoarse(ref: IntArray, img: IntArray, w: Int, h: Int, range: Int): Pair<Int, Int> {
        var best = Long.MAX_VALUE
        var bx = 0
        var by = 0
        for (dy in -range..range) {
            for (dx in -range..range) {
                var sad = 0L
                for (y in range until h - range) {
                    val ro = y * w
                    val io = (y + dy) * w + dx
                    for (x in range until w - range) sad += abs(ref[ro + x] - img[io + x])
                }
                if (sad < best) { best = sad; bx = dx; by = dy }
            }
        }
        return Pair(bx, by)
    }

    /** Refines the shift at full resolution on a sampled 512x512 center region. */
    private fun searchFine(ref: ByteArray, img: ByteArray, w: Int, h: Int, cx: Int, cy: Int, range: Int): Pair<Int, Int> {
        val half = 256
        val x0 = w / 2 - half
        val y0 = h / 2 - half
        var best = Long.MAX_VALUE
        var bx = cx
        var by = cy
        for (dy in cy - range..cy + range) {
            for (dx in cx - range..cx + range) {
                if (x0 + dx < 0 || y0 + dy < 0 || x0 + 2 * half + dx >= w || y0 + 2 * half + dy >= h) continue
                var sad = 0L
                var y = y0
                while (y < y0 + 2 * half) {
                    val ro = y * w
                    val io = (y + dy) * w + dx
                    var x = x0
                    while (x < x0 + 2 * half) {
                        sad += abs((ref[ro + x].toInt() and 0xFF) - (img[io + x].toInt() and 0xFF))
                        x += 2
                    }
                    y += 2
                }
                if (sad < best) { best = sad; bx = dx; by = dy }
            }
        }
        return Pair(bx, by)
    }

    /** Adds the shifted frame into the running sum; mismatching pixels contribute the reference instead. */
    private fun accumulate(ref: ByteArray, img: ByteArray, sum: ShortArray, w: Int, h: Int, dx: Int, dy: Int, thr: Int) {
        parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) {
                val fy = y + dy
                val ro = y * w
                val rowOk = fy in 0 until h
                val io = fy * w
                for (x in 0 until w) {
                    val r = ref[ro + x].toInt() and 0xFF
                    var v = r
                    if (rowOk) {
                        val fx = x + dx
                        if (fx in 0 until w) {
                            val t = img[io + fx].toInt() and 0xFF
                            if (abs(t - r) <= thr) v = t
                        }
                    }
                    sum[ro + x] = (sum[ro + x] + v).toShort()
                }
            }
        }
    }

    private fun average(sum: ShortArray, out: ByteArray, n: Int) {
        val rows = 64
        parallelRows((sum.size + rows - 1) / rows) { r0, r1 ->
            val end = min(sum.size, r1 * rows)
            for (i in r0 * rows until end) out[i] = ((sum[i] + n / 2) / n).toByte()
        }
    }

    // ---------------------------------------------------------------- look

    private fun buildGainLut(): FloatArray = FloatArray(256) { b ->
        val shadow = 1f - smoothstep(0f, 110f, b.toFloat())
        val highlight = smoothstep(170f, 255f, b.toFloat())
        1f + SHADOW_LIFT * shadow - HIGHLIGHT_PULL * highlight
    }

    /** Index i represents input value i / 2 (0..511.5), so boosted values above 255 roll off smoothly. */
    private fun buildToneLut(midGamma: Double): IntArray {
        val k = SHOULDER_KNEE
        fun shoulder(y: Double) = if (y <= k) y else k + (1 - k) * (1 - exp(-(y - k) / (1 - k)))
        val white = shoulder(1.0) // rescale so that input white (255) stays pure white
        return IntArray(1024) { i ->
            val u = (i / 2.0) / 255.0
            var y = shoulder(u.pow(midGamma)) / white
            y = max(0.0, (y - BLACK_POINT) / (1 - BLACK_POINT))
            (y * 255.0 + 0.5).toInt().coerceIn(0, 255)
        }
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    private fun boxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0
                var c = 0
                for (yy in max(0, y - r)..min(h - 1, y + r)) {
                    for (xx in max(0, x - r)..min(w - 1, x + r)) {
                        s += src[yy * w + xx]
                        c++
                    }
                }
                out[y * w + x] = s / c
            }
        }
        return out
    }

    /** Everything the per-pixel look needs, built once per photo. */
    private class Look(yP: ByteArray, val w: Int, val h: Int, midGamma: Double) {
        val f = 16
        val bw = max(1, w / f)
        val bh = max(1, h / f)
        // Brightness map at 1/16 scale, blurred, used to decide local shadow lift.
        val base = PhotoProcessor.boxBlur(PhotoProcessor.boxBlur(PhotoProcessor.downscale(yP, w, h, f), bw, bh, 2), bw, bh, 2)
        val gainLut = PhotoProcessor.buildGainLut()
        val toneLut = PhotoProcessor.buildToneLut(midGamma)
    }

    /**
     * Applies the look to one pixel and returns packed 0xRRGGBB.
     * [i] is the pixel index in [yP]; u and v are centered chroma (-128..127).
     */
    private fun shade(look: Look, yP: ByteArray, x: Int, y: Int, u: Float, v: Float): Int {
        val w = look.w
        val h = look.h
        val i = y * w + x
        val yc = (yP[i].toInt() and 0xFF).toFloat()
        var ysh = yc
        if (x > 0 && x < w - 1 && y > 0 && y < h - 1) {
            val avg = ((yP[i - 1].toInt() and 0xFF) + (yP[i + 1].toInt() and 0xFF) +
                (yP[i - w].toInt() and 0xFF) + (yP[i + w].toInt() and 0xFF)) * 0.25f
            val d = yc - avg
            if (abs(d) > SHARPEN_THRESHOLD) ysh = yc + SHARPEN * d
        }
        var r = ysh + 1.402f * v
        var g = ysh - 0.344136f * u - 0.714136f * v
        var b = ysh + 1.772f * u

        // Local shadow lift from the bilinear-sampled brightness map.
        val f = look.f
        val bw = look.bw
        val bh = look.bh
        val fy = (y + 0.5f) / f - 0.5f
        val by0 = fy.toInt().coerceIn(0, bh - 1)
        val by1 = min(bh - 1, by0 + 1)
        val wy = (fy - by0).coerceIn(0f, 1f)
        val fx = (x + 0.5f) / f - 0.5f
        val bx0 = fx.toInt().coerceIn(0, bw - 1)
        val bx1 = min(bw - 1, bx0 + 1)
        val wx = (fx - bx0).coerceIn(0f, 1f)
        val base = look.base
        val top = base[by0 * bw + bx0] * (1 - wx) + base[by0 * bw + bx1] * wx
        val bot = base[by1 * bw + bx0] * (1 - wx) + base[by1 * bw + bx1] * wx
        val gain = look.gainLut[(top * (1 - wy) + bot * wy).toInt().coerceIn(0, 255)]

        r *= gain * WARM_R
        g *= gain
        b *= gain * WARM_B

        // Vibrance: muted colors get more saturation than already-saturated ones.
        val l = 0.299f * r + 0.587f * g + 0.114f * b
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val sat = if (mx > 1f) ((mx - mn) / mx).coerceIn(0f, 1f) else 0f
        val k = 1f + VIBRANCE * (1f - sat)
        r = l + (r - l) * k
        g = l + (g - l) * k
        b = l + (b - l) * k

        val lut = look.toneLut
        val ri = lut[(r * 2f).toInt().coerceIn(0, 1023)]
        val gi = lut[(g * 2f).toInt().coerceIn(0, 1023)]
        val bi = lut[(b * 2f).toInt().coerceIn(0, 1023)]
        return (ri shl 16) or (gi shl 8) or bi
    }

    /**
     * Applies the look and writes the result straight into an NV21 buffer, one 2x2 block at a time.
     * Skipping a full-size RGB bitmap halves memory use, which is what lets 50 MP photos fit.
     */
    private fun toneMapToNv21(yP: ByteArray, uP: ByteArray, vP: ByteArray, w: Int, h: Int, midGamma: Double = MIDTONE_GAMMA): ByteArray {
        val cw = w / 2
        val ch = h / 2
        val look = Look(yP, w, h, midGamma)
        val out = ByteArray(w * h + 2 * cw * ch)
        val uvBase = w * h
        parallelRows(ch) { by0, by1 ->
            for (by in by0 until by1) {
                for (bx in 0 until cw) {
                    val ci = by * cw + bx
                    val u = (uP[ci].toInt() and 0xFF) - 128f
                    val v = (vP[ci].toInt() and 0xFF) - 128f
                    var uSum = 0f
                    var vSum = 0f
                    for (dy in 0..1) {
                        val y = by * 2 + dy
                        for (dx in 0..1) {
                            val x = bx * 2 + dx
                            val rgb = shade(look, yP, x, y, u, v)
                            val r = ((rgb shr 16) and 0xFF).toFloat()
                            val g = ((rgb shr 8) and 0xFF).toFloat()
                            val b = (rgb and 0xFF).toFloat()
                            // Full-range (JFIF) BT.601, matching the camera's YUV and YuvImage's JPEG encoder.
                            val yy = 0.299f * r + 0.587f * g + 0.114f * b
                            out[y * w + x] = (yy + 0.5f).toInt().coerceIn(0, 255).toByte()
                            uSum += -0.168736f * r - 0.331264f * g + 0.5f * b
                            vSum += 0.5f * r - 0.418688f * g - 0.081312f * b
                        }
                    }
                    val o = uvBase + by * w + bx * 2 // NV21 = V then U, interleaved
                    out[o] = (vSum * 0.25f + 128.5f).toInt().coerceIn(0, 255).toByte()
                    out[o + 1] = (uSum * 0.25f + 128.5f).toInt().coerceIn(0, 255).toByte()
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- output

    private fun save(context: Context, nv21: ByteArray, w: Int, h: Int, orientationDeg: Int): Uri {
        val tmp = File.createTempFile("photo", ".jpg", context.cacheDir)
        FileOutputStream(tmp).use {
            YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), JPEG_QUALITY, it)
        }

        val now = Date()
        val exif = ExifInterface(tmp.absolutePath)
        val orientation = when (orientationDeg) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(now))
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "SmoothCam")
        exif.saveAttributes()

        val name = "SmoothCam_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(now) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SmoothCam")
            put(MediaStore.Images.Media.DATE_TAKEN, now.time)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("no output stream")
            out.use { o -> tmp.inputStream().use { it.copyTo(o) } }
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        } finally {
            tmp.delete()
        }
        return uri
    }

    // ---------------------------------------------------------------- threading

    internal fun parallelRows(h: Int, block: (Int, Int) -> Unit) {
        val n = threadCount
        val chunk = (h + n - 1) / n
        val error = AtomicReference<Throwable?>(null)
        val workers = (0 until n).map { t ->
            val y0 = t * chunk
            val y1 = min(h, y0 + chunk)
            thread(name = "photo-$t") {
                try {
                    if (y0 < y1) block(y0, y1)
                } catch (e: Throwable) {
                    error.compareAndSet(null, e)
                }
            }
        }
        workers.forEach { it.join() }
        error.get()?.let { throw it }
    }
}
