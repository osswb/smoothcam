package com.smoothcam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.LensShadingMap
import android.media.Image
import android.net.Uri
import android.util.Log
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * RAW+ : a small HDR+-style pipeline.
 *
 *  1. The burst is shot slightly underexposed, so bright areas aren't clipped in any frame.
 *  2. Frames are aligned to the first one: a whole-frame shift, then a small extra shift per tile,
 *     so moving parts line up better than with one global shift.
 *  3. Each frame's pixel is averaged in only if it matches the reference within the sensor's own
 *     measured noise level (from the camera's noise profile); otherwise it's left out (motion).
 *  4. The merged RAW is "developed": black/white level, lens shading (vignetting) correction, white
 *     balance, demosaicing (Malvar-He-Cutler), the camera's color matrix, then the underexposure
 *     is undone with a highlight roll-off so nothing clips, and sRGB gamma.
 *  5. Light chroma denoise, then the same look as the standard photo pipeline, saved as a JPEG.
 */
object RawProcessor {
    private const val TAG = "RawProcessor"

    /** One RAW frame copied out of the camera buffer (16-bit values, one per sensor pixel). */
    class RawFrame(val width: Int, val height: Int, val data: ShortArray) {
        companion object {
            fun copyOf(img: Image): RawFrame {
                val w = img.width
                val h = img.height
                val plane = img.planes[0]
                val rowStride = plane.rowStride
                val out = ShortArray(w * h)
                val src = plane.buffer
                PhotoProcessor.parallelRows(h) { y0, y1 ->
                    val b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    for (y in y0 until y1) {
                        b.position(y * rowStride)
                        b.asShortBuffer().get(out, y * w, w)
                    }
                }
                return RawFrame(w, h, out)
            }
        }
    }

    /** Everything about the shot needed to develop the RAW data. */
    class Meta(
        val cfa: Int,                 // SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_*
        val black: FloatArray,        // black level per 2x2 position, index (y%2)*2 + (x%2)
        val white: Float,
        val wb: FloatArray,           // white-balance gains [R, G even rows, G odd rows, B]
        val ccm: FloatArray,          // 3x3 row-major: white-balanced camera RGB -> linear sRGB
        val shading: LensShadingMap?,
        val noiseS: Double,           // noise model: variance = S * x + O (x normalised 0..1)
        val noiseO: Double,
        val boost: Float,             // brightness to restore (2^underexposure stops)
        val zoom: Float,              // centre crop factor (RAW isn't cropped by the camera's zoom)
        val iso: Int,
    ) {
        companion object {
            fun from(chars: CameraCharacteristics, r: CaptureResult, boost: Float, zoom: Float): Meta {
                val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
                    ?: CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
                val dyn = r.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
                val pattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
                val black = FloatArray(4) { p ->
                    when {
                        dyn != null && dyn.size >= 4 -> dyn[p]
                        pattern != null -> pattern.getOffsetForIndex(p % 2, p / 2).toFloat()
                        else -> 64f
                    }
                }
                val white = (r.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
                    ?: chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()
                val g = r.get(CaptureResult.COLOR_CORRECTION_GAINS)
                val wb = if (g != null) floatArrayOf(g.red, g.greenEven, g.greenOdd, g.blue) else floatArrayOf(2f, 1f, 1f, 1.6f)
                val t = r.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
                val ccm = FloatArray(9) { i ->
                    t?.getElement(i % 3, i / 3)?.toFloat() ?: if (i % 4 == 0) 1f else 0f
                }
                val noise = r.get(CaptureResult.SENSOR_NOISE_PROFILE)
                val s = noise?.map { it.first }?.average() ?: 0.0
                val o = noise?.map { it.second }?.average() ?: 0.0
                return Meta(
                    cfa, black, white, wb, ccm,
                    r.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP),
                    s, o, boost, zoom, r.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
                )
            }
        }
    }

    /** Merge, develop, apply the look and save. frames[0] is the reference. Returns the photo's URI. */
    fun processAndSave(context: Context, frames: MutableList<RawFrame?>, meta: Meta, orientationDeg: Int): Uri {
        val t0 = System.nanoTime()
        val w = frames[0]!!.width
        val h = frames[0]!!.height
        val plane = mergeToPlane(frames, meta, w, h)
        val t1 = System.nanoTime()
        val yuv = develop(plane, w, h, meta)
        val t2 = System.nanoTime()
        Log.i(TAG, String.format("merge %.2f s, develop %.2f s", (t1 - t0) / 1e9, (t2 - t1) / 1e9))
        return PhotoProcessor.finishAndSave(context, yuv.y, yuv.u, yuv.v, yuv.w, yuv.h, orientationDeg, rawLook = true)
    }

    // ---------------------------------------------------------------- color filter layout

    /** 0 = red, 1 = green, 2 = blue for each 2x2 position, index (y%2)*2 + (x%2). */
    private fun colors(cfa: Int): IntArray = when (cfa) {
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> intArrayOf(1, 0, 2, 1)
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> intArrayOf(1, 2, 0, 1)
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> intArrayOf(2, 1, 1, 0)
        else -> intArrayOf(0, 1, 1, 2) // RGGB
    }

    /** Index into [R, G even rows, G odd rows, B] (white balance and lens shading order). */
    private fun channels(cfa: Int): IntArray {
        val c = colors(cfa)
        return IntArray(4) { p ->
            when (c[p]) {
                0 -> 0
                2 -> 3
                else -> if (p / 2 == 0) 1 else 2
            }
        }
    }

    // ---------------------------------------------------------------- align + merge

    /** Half-resolution brightness image (each 2x2 sensor block summed), used only for alignment. */
    private fun halfGrey(f: RawFrame): IntArray {
        val hw = f.width / 2
        val hh = f.height / 2
        val out = IntArray(hw * hh)
        val d = f.data
        val w = f.width
        PhotoProcessor.parallelRows(hh) { y0, y1 ->
            for (y in y0 until y1) {
                val r0 = 2 * y * w
                val r1 = r0 + w
                for (x in 0 until hw) {
                    val i = 2 * x
                    out[y * hw + x] = (d[r0 + i].toInt() and 0xFFFF) + (d[r0 + i + 1].toInt() and 0xFFFF) +
                        (d[r1 + i].toInt() and 0xFFFF) + (d[r1 + i + 1].toInt() and 0xFFFF)
                }
            }
        }
        return out
    }

    private fun shrink4(src: IntArray, w: Int, h: Int): IntArray {
        val sw = w / 4
        val sh = h / 4
        val out = IntArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            var s = 0
            for (yy in 0 until 4) {
                val o = (y * 4 + yy) * w + x * 4
                s += src[o] + src[o + 1] + src[o + 2] + src[o + 3]
            }
            out[y * sw + x] = s / 16
        }
        return out
    }

    private fun sad(ref: IntArray, img: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, dx: Int, dy: Int, step: Int): Long {
        var s = 0L
        var y = y0
        while (y < y1) {
            val ro = y * w
            val io = (y + dy) * w + dx
            var x = x0
            while (x < x1) {
                s += abs(ref[ro + x] - img[io + x])
                x += step
            }
            y += step
        }
        return s
    }

    /** Whole-frame shift (in half-resolution pixels): coarse at 1/4, refined at full half-res. */
    private fun globalShift(ref: IntArray, img: IntArray, refSmall: IntArray, hw: Int, hh: Int): Pair<Int, Int> {
        val img4 = shrink4(img, hw, hh)
        val sw = hw / 4
        val sh = hh / 4
        val r = 6
        var best = Long.MAX_VALUE
        var cx = 0
        var cy = 0
        for (dy in -r..r) for (dx in -r..r) {
            val s = sad(refSmall, img4, sw, r, r, sw - r, sh - r, dx, dy, 1)
            if (s < best) { best = s; cx = dx; cy = dy }
        }
        // Refine on the central area at half resolution.
        val half = min(256, min(hw, hh) / 3)
        val x0 = hw / 2 - half
        val y0 = hh / 2 - half
        var bx = cx * 4
        var by = cy * 4
        best = Long.MAX_VALUE
        val fx = bx
        val fy = by
        for (dy in fy - 4..fy + 4) for (dx in fx - 4..fx + 4) {
            if (x0 + dx < 0 || y0 + dy < 0 || x0 + 2 * half + dx >= hw || y0 + 2 * half + dy >= hh) continue
            val s = sad(ref, img, hw, x0, y0, x0 + 2 * half, y0 + 2 * half, dx, dy, 2)
            if (s < best) { best = s; bx = dx; by = dy }
        }
        return Pair(bx, by)
    }

    private const val TILE = 64 // tile size in half-resolution pixels (128 sensor pixels)

    /** Per-tile extra shift (±2 half-res px) around the global one, for parts that moved differently. */
    private fun tileShifts(ref: IntArray, img: IntArray, hw: Int, hh: Int, gx: Int, gy: Int): Pair<IntArray, IntArray> {
        val tw = (hw + TILE - 1) / TILE
        val th = (hh + TILE - 1) / TILE
        val sx = IntArray(tw * th) { gx }
        val sy = IntArray(tw * th) { gy }
        PhotoProcessor.parallelRows(th) { t0, t1 ->
            for (ty in t0 until t1) for (tx in 0 until tw) {
                val x0 = tx * TILE
                val y0 = ty * TILE
                val x1 = min(hw, x0 + TILE)
                val y1 = min(hh, y0 + TILE)
                var best = Long.MAX_VALUE
                for (dy in gy - 2..gy + 2) for (dx in gx - 2..gx + 2) {
                    if (x0 + dx < 0 || y0 + dy < 0 || x1 + dx > hw || y1 + dy > hh) continue
                    val s = sad(ref, img, hw, x0, y0, x1, y1, dx, dy, 2)
                    // Prefer the global shift unless a local one is clearly better (avoids noise-chasing).
                    val biased = if (dx == gx && dy == gy) s * 97 / 100 else s
                    if (biased < best) { best = biased; sx[ty * tw + tx] = dx; sy[ty * tw + tx] = dy }
                }
            }
        }
        return Pair(sx, sy)
    }

    /**
     * Robust average of all frames into one normalised, white-balanced, shading-corrected RAW plane
     * (float, ~0..1). Frames are released as they are merged to keep memory down.
     */
    private fun mergeToPlane(frames: MutableList<RawFrame?>, meta: Meta, w: Int, h: Int): FloatArray {
        val ref = frames[0]!!.data
        val hw = w / 2
        val hh = h / 2
        val sum = IntArray(w * h) { ref[it].toInt() and 0xFFFF }
        val count = ByteArray(w * h) { 1 }

        if (frames.size > 1) {
            // Allowed difference per raw value: 3 sigma of the difference of two noisy samples.
            val range = meta.white - meta.black[0]
            val maxV = meta.white.toInt() + 1
            val thr = IntArray(maxV + 1) { v ->
                val x = ((v - meta.black[0]) / range).coerceIn(0f, 1f).toDouble()
                val sigma = if (meta.noiseS > 0 || meta.noiseO > 0) sqrt(meta.noiseS * x + meta.noiseO) * range
                else sqrt(max(1.0, (v - meta.black[0]).toDouble())) + 4.0
                (3.0 * 1.414 * sigma + 2.0).roundToInt()
            }
            val refGrey = halfGrey(frames[0]!!)
            val refSmall = shrink4(refGrey, hw, hh)
            val tw = (hw + TILE - 1) / TILE
            for (k in 1 until frames.size) {
                val f = frames[k] ?: continue
                if (f.width != w || f.height != h) continue
                val grey = halfGrey(f)
                val (gx, gy) = globalShift(refGrey, grey, refSmall, hw, hh)
                val (tsx, tsy) = tileShifts(refGrey, grey, hw, hh, gx, gy)
                Log.d(TAG, "frame $k global shift ($gx, $gy) half-res px")
                val d = f.data
                PhotoProcessor.parallelRows(h) { y0, y1 ->
                    for (y in y0 until y1) {
                        val ty = (y / 2) / TILE
                        for (x in 0 until w) {
                            val ti = ty * tw + (x / 2) / TILE
                            val sx = x + 2 * tsx[ti]
                            val syy = y + 2 * tsy[ti]
                            if (sx < 0 || syy < 0 || sx >= w || syy >= h) continue
                            val i = y * w + x
                            val r = ref[i].toInt() and 0xFFFF
                            val v = d[syy * w + sx].toInt() and 0xFFFF
                            if (abs(v - r) <= thr[min(r, maxV)]) {
                                sum[i] += v
                                count[i]++
                            }
                        }
                    }
                }
                frames[k] = null // let it be garbage-collected
            }
        }
        frames[0] = null

        // Normalise: black/white level, lens shading, white balance.
        val ch = channels(meta.cfa)
        val plane = FloatArray(w * h)
        val shading = meta.shading
        val cols = shading?.columnCount ?: 0
        val rows = shading?.rowCount ?: 0
        PhotoProcessor.parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) {
                val gy = if (rows > 1) y.toFloat() / (h - 1) * (rows - 1) else 0f
                val r0 = gy.toInt().coerceIn(0, max(0, rows - 1))
                val r1 = min(max(0, rows - 1), r0 + 1)
                val fy = gy - r0
                for (x in 0 until w) {
                    val i = y * w + x
                    val p = (y and 1) * 2 + (x and 1)
                    val c = ch[p]
                    val b = meta.black[p]
                    var v = (sum[i].toFloat() / count[i] - b) / (meta.white - b)
                    if (shading != null && cols > 1) {
                        val gx = x.toFloat() / (w - 1) * (cols - 1)
                        val c0 = gx.toInt().coerceIn(0, cols - 1)
                        val c1 = min(cols - 1, c0 + 1)
                        val fx = gx - c0
                        val top = shading.getGainFactor(c, c0, r0) * (1 - fx) + shading.getGainFactor(c, c1, r0) * fx
                        val bot = shading.getGainFactor(c, c0, r1) * (1 - fx) + shading.getGainFactor(c, c1, r1) * fx
                        v *= top * (1 - fy) + bot * fy
                    }
                    plane[i] = v * meta.wb[c]
                }
            }
        }
        return plane
    }

    // ---------------------------------------------------------------- develop

    class Yuv(val y: ByteArray, val u: ByteArray, val v: ByteArray, val w: Int, val h: Int)

    private fun develop(p: FloatArray, w: Int, h: Int, meta: Meta): Yuv {
        // Centre crop for zoom (RAW isn't cropped by the camera), kept on even pixels.
        val z = max(1f, meta.zoom)
        val cw = ((w / z).toInt() / 2) * 2
        val chh = ((h / z).toInt() / 2) * 2
        val ox = ((w - cw) / 4) * 2
        val oy = ((h - chh) / 4) * 2

        val colors = colors(meta.cfa)
        val m = meta.ccm
        // Highlight roll-off: restore brightness (boost) while keeping the brightest captured value
        // at white instead of clipping it.
        val knee = 0.72f
        fun shoulder(x: Float): Float = if (x <= knee) x else knee + (1 - knee) * (1 - exp(-(x - knee) / (1 - knee)))
        val norm = 1f / shoulder(meta.boost)
        // sRGB transfer curve as a lookup table over 0..1.
        val gammaLut = IntArray(4097) { i ->
            val x = i / 4096.0
            val g = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055
            (g * 255.0 + 0.5).toInt().coerceIn(0, 255)
        }

        fun at(x: Int, y: Int): Float {
            // Mirror by two pixels at the edges so the colour filter pattern stays consistent.
            val xx = if (x < 0) x + 2 else if (x >= w) x - 2 else x
            val yy = if (y < 0) y + 2 else if (y >= h) y - 2 else y
            return p[yy * w + xx]
        }

        val yOut = ByteArray(cw * chh)
        val uOut = ByteArray((cw / 2) * (chh / 2))
        val vOut = ByteArray((cw / 2) * (chh / 2))
        val half = cw / 2
        PhotoProcessor.parallelRows(chh / 2) { by0, by1 ->
            val rgb = FloatArray(3)
            for (by in by0 until by1) {
                for (bx in 0 until half) {
                    var uSum = 0f
                    var vSum = 0f
                    for (dy in 0..1) for (dx in 0..1) {
                        val x = ox + bx * 2 + dx
                        val y = oy + by * 2 + dy
                        demosaic(::at, x, y, colors[(y and 1) * 2 + (x and 1)], (y and 1) * 2 + (x and 1), colors, rgb)
                        // Camera colour matrix to linear sRGB.
                        var r = m[0] * rgb[0] + m[1] * rgb[1] + m[2] * rgb[2]
                        var g = m[3] * rgb[0] + m[4] * rgb[1] + m[5] * rgb[2]
                        var b = m[6] * rgb[0] + m[7] * rgb[1] + m[8] * rgb[2]
                        r = shoulder(max(0f, r) * meta.boost) * norm
                        g = shoulder(max(0f, g) * meta.boost) * norm
                        b = shoulder(max(0f, b) * meta.boost) * norm
                        val ri = gammaLut[(r * 4096f).toInt().coerceIn(0, 4096)].toFloat()
                        val gi = gammaLut[(g * 4096f).toInt().coerceIn(0, 4096)].toFloat()
                        val bi = gammaLut[(b * 4096f).toInt().coerceIn(0, 4096)].toFloat()
                        val yy = 0.299f * ri + 0.587f * gi + 0.114f * bi
                        yOut[(by * 2 + dy) * cw + bx * 2 + dx] = (yy + 0.5f).toInt().coerceIn(0, 255).toByte()
                        uSum += -0.168736f * ri - 0.331264f * gi + 0.5f * bi
                        vSum += 0.5f * ri - 0.418688f * gi - 0.081312f * bi
                    }
                    uOut[by * half + bx] = (uSum * 0.25f + 128.5f).toInt().coerceIn(0, 255).toByte()
                    vOut[by * half + bx] = (vSum * 0.25f + 128.5f).toInt().coerceIn(0, 255).toByte()
                }
            }
        }
        // Colour noise is the most visible leftover noise; smooth chroma a little (more at high ISO).
        val r = if (meta.iso >= 800) 2 else 1
        val ch2 = chh / 2
        blurInPlace(uOut, half, ch2, r)
        blurInPlace(vOut, half, ch2, r)
        return Yuv(yOut, uOut, vOut, cw, chh)
    }

    /**
     * Malvar-He-Cutler demosaicing: bilinear interpolation corrected by the local gradient of the
     * known colour, which keeps edges much sharper and with fewer colour fringes than plain bilinear.
     */
    private inline fun demosaic(at: (Int, Int) -> Float, x: Int, y: Int, c: Int, pos: Int, colors: IntArray, out: FloatArray) {
        val cv = at(x, y)
        val n = at(x, y - 1); val s = at(x, y + 1); val e = at(x + 1, y); val wv = at(x - 1, y)
        val n2 = at(x, y - 2); val s2 = at(x, y + 2); val e2 = at(x + 2, y); val w2 = at(x - 2, y)
        val ne = at(x + 1, y - 1); val nw = at(x - 1, y - 1); val se = at(x + 1, y + 1); val sw = at(x - 1, y + 1)
        when (c) {
            0, 2 -> { // red or blue pixel
                val g = (4 * cv + 2 * (n + s + e + wv) - (n2 + s2 + e2 + w2)) / 8f
                val other = (6 * cv + 2 * (ne + nw + se + sw) - 1.5f * (n2 + s2 + e2 + w2)) / 8f
                if (c == 0) { out[0] = cv; out[1] = g; out[2] = other } else { out[0] = other; out[1] = g; out[2] = cv }
            }
            else -> { // green pixel: which colour sits left/right of it?
                val horizColor = colors[(pos and 2) or ((pos and 1) xor 1)]
                val diag = ne + nw + se + sw
                val hv = (5 * cv + 4 * (wv + e) - (w2 + e2) - diag + 0.5f * (n2 + s2)) / 8f
                val vv = (5 * cv + 4 * (n + s) - (n2 + s2) - diag + 0.5f * (w2 + e2)) / 8f
                out[1] = cv
                if (horizColor == 0) { out[0] = hv; out[2] = vv } else { out[0] = vv; out[2] = hv }
            }
        }
    }

    private fun blurInPlace(a: ByteArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(w * h)
        // Horizontal then vertical box blur.
        PhotoProcessor.parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) {
                val o = y * w
                for (x in 0 until w) {
                    var s = 0
                    var c = 0
                    for (k in max(0, x - r)..min(w - 1, x + r)) { s += a[o + k].toInt() and 0xFF; c++ }
                    tmp[o + x] = s / c
                }
            }
        }
        PhotoProcessor.parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) {
                for (x in 0 until w) {
                    var s = 0
                    var c = 0
                    for (k in max(0, y - r)..min(h - 1, y + r)) { s += tmp[k * w + x]; c++ }
                    a[y * w + x] = (s / c).toByte()
                }
            }
        }
    }
}
