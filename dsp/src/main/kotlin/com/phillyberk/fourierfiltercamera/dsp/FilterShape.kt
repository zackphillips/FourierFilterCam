package com.phillyberk.fourierfiltercamera.dsp

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * A region of frequency space, in cycles/pixel with DC at the origin.
 * Whether the region passes or blocks light is decided by [FilterSpec].
 */
sealed interface FilterShape {
    /**
     * True when the region is point-symmetric about DC. The filtered image
     * of a real input is then real, which lets the engine use a cheaper
     * real-output inverse DFT.
     */
    val isPointSymmetric: Boolean

    fun contains(u: Double, v: Double): Boolean

    data object Empty : FilterShape {
        override val isPointSymmetric get() = true
        override fun contains(u: Double, v: Double) = false
    }

    /** Rectangle centered on DC. */
    data class Box(val halfU: Double, val halfV: Double) : FilterShape {
        override val isPointSymmetric get() = true
        override fun contains(u: Double, v: Double) = abs(u) <= halfU && abs(v) <= halfV
    }

    /** Right half (u >= 0) of a DC-centered rectangle. A single-sideband filter. */
    data class HalfBox(val halfU: Double, val halfV: Double) : FilterShape {
        override val isPointSymmetric get() = false
        override fun contains(u: Double, v: Double) = u >= 0 && u <= halfU && abs(v) <= halfV
    }

    /** Disk centered on DC: a circular pupil when passing, a DC stop when blocking. */
    data class Circle(val radius: Double) : FilterShape {
        override val isPointSymmetric get() = true
        override fun contains(u: Double, v: Double) = hypot(u, v) <= radius
    }

    /** Left half (u <= 0) of a DC-centered disk. */
    data class HalfCircle(val radius: Double) : FilterShape {
        override val isPointSymmetric get() = false
        override fun contains(u: Double, v: Double) = u <= 0 && hypot(u, v) <= radius
    }

    /** Ring centered on DC with outer radius [outer] and radial [width]. */
    data class Annulus(val outer: Double, val width: Double) : FilterShape {
        val inner: Double get() = max(outer - width, 0.0)
        override val isPointSymmetric get() = true
        override fun contains(u: Double, v: Double): Boolean {
            val r = hypot(u, v)
            return r <= outer && r >= inner
        }
    }

    /**
     * Union of disks painted with a finger. Identity, not list equality,
     * marks a change: each edit produces a new instance.
     */
    class Freeform(val dabs: List<Dab>) : FilterShape {
        override val isPointSymmetric get() = dabs.isEmpty()
        override fun contains(u: Double, v: Double) =
            dabs.any { hypot(u - it.u, v - it.v) <= it.radius }

        fun plus(dab: Dab): Freeform = Freeform(dabs + dab)
    }
}

data class Dab(val u: Double, val v: Double, val radius: Double)

/**
 * A complete filter: a region plus whether the region passes or blocks.
 * The engine rebuilds its filter whenever it is handed a different
 * instance, so callers create a new spec for every edit.
 */
class FilterSpec(val shape: FilterShape, val passInside: Boolean) {
    val isPointSymmetric: Boolean get() = shape.isPointSymmetric

    fun transmission(u: Double, v: Double): Float =
        if (shape.contains(u, v) == passInside) 1f else 0f

    override fun toString() = "FilterSpec($shape, passInside=$passInside)"

    companion object {
        val PASS_ALL = FilterSpec(FilterShape.Empty, passInside = false)
    }
}

/** The shape families offered in the UI. */
enum class FilterType(val label: String, val passInsideByDefault: Boolean) {
    FREEFORM("Freeform", passInsideByDefault = false),
    BOX("Box", passInsideByDefault = true),
    HALF_BOX("Half box", passInsideByDefault = true),
    CIRCLE("Circle", passInsideByDefault = true),
    HALF_CIRCLE("Half circle", passInsideByDefault = true),
    ANNULUS("Annulus", passInsideByDefault = true);

    /** Shape whose boundary passes through the touched frequency (u, v). */
    fun shapeThrough(u: Double, v: Double, annulusWidth: Double): FilterShape {
        val r = hypot(u, v)
        return when (this) {
            FREEFORM -> FilterShape.Freeform(emptyList())
            BOX -> FilterShape.Box(abs(u), abs(v))
            HALF_BOX -> FilterShape.HalfBox(abs(u), abs(v))
            CIRCLE -> FilterShape.Circle(r)
            HALF_CIRCLE -> FilterShape.HalfCircle(r)
            ANNULUS -> FilterShape.Annulus(r, annulusWidth)
        }
    }
}
