package com.phillyberk.fourierfiltercamera.dsp

/**
 * Geometry of a centered (fftshifted) spectrum.
 *
 * DC sits at pixel (width / 2, height / 2). Frequencies are in cycles per
 * frame pixel, so the grid spans [-0.5, 0.5) on both axes regardless of
 * aspect ratio, and a circle in frequency space is an ellipse in spectrum
 * pixels when width != height.
 *
 * Dimensions are kept even so that fftshift and ifftshift are the same
 * quadrant swap and every bin except Nyquist has a conjugate partner.
 */
data class FrequencyGrid(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Grid must be non-empty: ${width}x$height" }
        require(width % 2 == 0 && height % 2 == 0) { "Grid dims must be even: ${width}x$height" }
    }

    val centerX: Int get() = width / 2
    val centerY: Int get() = height / 2

    /** Horizontal frequency (cycles/pixel) at spectrum column [x]. */
    fun u(x: Double): Double = (x - centerX) / width

    /** Vertical frequency (cycles/pixel) at spectrum row [y]. */
    fun v(y: Double): Double = (y - centerY) / height

    fun x(u: Double): Double = u * width + centerX

    fun y(v: Double): Double = v * height + centerY

    companion object {
        /** Grid for an input frame, padded up to sizes the DFT handles quickly. */
        fun forFrame(frameWidth: Int, frameHeight: Int): FrequencyGrid =
            FrequencyGrid(FftSize.nextFastEven(frameWidth), FftSize.nextFastEven(frameHeight))
    }
}

object FftSize {
    /** Smallest even n >= [size] whose only prime factors are 2, 3 and 5. */
    fun nextFastEven(size: Int): Int {
        require(size > 0) { "size must be positive: $size" }
        var n = if (size % 2 == 0) size else size + 1
        while (!isFiveSmooth(n)) n += 2
        return n
    }

    private fun isFiveSmooth(n: Int): Boolean {
        var m = n
        for (p in intArrayOf(2, 3, 5)) while (m % p == 0) m /= p
        return m == 1
    }
}
