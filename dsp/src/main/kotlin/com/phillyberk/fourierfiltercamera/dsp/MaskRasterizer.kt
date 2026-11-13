package com.phillyberk.fourierfiltercamera.dsp

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object MaskRasterizer {
    /**
     * Writes the 0/1 transmission of [spec] into [out] (row-major,
     * width * height) in centered layout.
     *
     * [frequencyScale] evaluates the shape at (u * s, v * s). A fixed
     * physical mask in the Fourier plane of a 4f system passes spatial
     * frequency nu at plane position lambda * f * nu, so the filter seen at
     * wavelength lambda_c, for a mask drawn at lambda_ref, is
     * H_ref(nu * lambda_c / lambda_ref). Pass s = lambda_c / lambda_ref.
     */
    fun rasterize(spec: FilterSpec, grid: FrequencyGrid, out: FloatArray, frequencyScale: Double = 1.0) {
        require(out.size == grid.width * grid.height) { "out has ${out.size} entries, grid needs ${grid.width * grid.height}" }
        require(frequencyScale > 0) { "frequencyScale must be positive" }
        val shape = spec.shape
        if (shape is FilterShape.Freeform) {
            rasterizeFreeform(shape, spec.passInside, grid, out, frequencyScale)
            return
        }
        val inside = if (spec.passInside) 1f else 0f
        val outside = 1f - inside
        val w = grid.width
        for (y in 0 until grid.height) {
            val v = grid.v(y.toDouble()) * frequencyScale
            val row = y * w
            for (x in 0 until w) {
                val u = grid.u(x.toDouble()) * frequencyScale
                out[row + x] = if (shape.contains(u, v)) inside else outside
            }
        }
    }

    /** Paints each dab only inside its bounding box: O(dabs * dab area), not O(dabs * grid). */
    private fun rasterizeFreeform(
        shape: FilterShape.Freeform,
        passInside: Boolean,
        grid: FrequencyGrid,
        out: FloatArray,
        s: Double,
    ) {
        val inside = if (passInside) 1f else 0f
        out.fill(1f - inside)
        val w = grid.width
        val h = grid.height
        for (dab in shape.dabs) {
            // Pixel (x, y) is inside when (u*s - du)^2 + (v*s - dv)^2 <= r^2.
            val x0 = max(0, floor(grid.x((dab.u - dab.radius) / s)).toInt())
            val x1 = min(w - 1, ceil(grid.x((dab.u + dab.radius) / s)).toInt())
            val y0 = max(0, floor(grid.y((dab.v - dab.radius) / s)).toInt())
            val y1 = min(h - 1, ceil(grid.y((dab.v + dab.radius) / s)).toInt())
            val r2 = dab.radius * dab.radius
            for (y in y0..y1) {
                val dv = grid.v(y.toDouble()) * s - dab.v
                val row = y * w
                for (x in x0..x1) {
                    val du = grid.u(x.toDouble()) * s - dab.u
                    if (du * du + dv * dv <= r2) out[row + x] = inside
                }
            }
        }
    }
}
