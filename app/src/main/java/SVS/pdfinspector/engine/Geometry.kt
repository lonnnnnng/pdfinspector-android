package SVS.pdfinspector.engine

// 2D affine transform matching a PDF matrix [a b c d e f]; row-vector convention.
class Affine(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val e: Float,
    val f: Float,
) {
    // Result R such that p*R == p*this*other (apply this, then other).
    fun then(o: Affine): Affine = Affine(
        a * o.a + b * o.c,
        a * o.b + b * o.d,
        c * o.a + d * o.c,
        c * o.b + d * o.d,
        e * o.a + f * o.c + o.e,
        e * o.b + f * o.d + o.f,
    )

    fun mapX(x: Float, y: Float): Float = a * x + c * y + e
    fun mapY(x: Float, y: Float): Float = b * x + d * y + f

    companion object {
        val IDENTITY = Affine(1f, 0f, 0f, 1f, 0f, 0f)
        fun translate(tx: Float, ty: Float) = Affine(1f, 0f, 0f, 1f, tx, ty)
    }
}

// Axis-aligned bounds in PDF user space (y grows upward).
class Bounds(
    var minX: Float,
    var minY: Float,
    var maxX: Float,
    var maxY: Float,
) {
    val width: Float get() = maxX - minX
    val height: Float get() = maxY - minY
    val isValid: Boolean get() = maxX >= minX && maxY >= minY

    fun include(x: Float, y: Float) {
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
    }

    fun includeBounds(o: Bounds) {
        include(o.minX, o.minY)
        include(o.maxX, o.maxY)
    }

    companion object {
        fun empty() = Bounds(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
    }
}
