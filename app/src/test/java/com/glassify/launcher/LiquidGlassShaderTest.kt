package com.glassify.launcher

import android.graphics.LinearGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import com.glassify.launcher.glass.LiquidGlassShaderPrograms
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compiles the AGSL against real Skia.
 *
 * This is the most valuable test in the project. A shader program is compiled at
 * *runtime*, so a single type error sails through the Kotlin build untouched and
 * then throws on the first frame that draws a pane of glass — which is the home
 * screen's first frame, on a launcher the user cannot escape by pressing Home.
 *
 * `@GraphicsMode(NATIVE)` is what gives this teeth. Without it Robolectric stubs
 * `RuntimeShader` and every program, valid or not, constructs happily; with it
 * the constructor runs the real SkSL compiler and a type error throws here
 * instead of on the device. (Verified by deliberately breaking the program and
 * confirming these fail.)
 *
 * Rasterising is not possible in this environment — the platform refuses a
 * RuntimeShader on a software canvas — so these cover compilation and the
 * uniform contract, which is where the mistakes actually are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiquidGlassShaderTest {

    /**
     * Compiles the refracting program and sets every uniform the renderer sets.
     *
     * The uniform half matters as much as the compile: naming one differently in
     * Kotlin than in the shader throws only when that line runs, and a uniform
     * left unused by the program is silently optimised out and then rejected.
     * These are the exact calls `drawRefractedGlass` makes.
     */
    @Test
    fun `the refracting program compiles and accepts its uniforms`() {
        val shader = RuntimeShader(portableSource(LiquidGlassShaderPrograms.REFRACTING))
        setSharedUniforms(shader)
        shader.setFloatUniform("uRefraction", 9f)
        shader.setFloatUniform("uAberration", 0.16f)
        shader.setInputShader("content", stubBackdrop())
    }

    /** The same, for the overlay program and the calls `drawSurfaceGlass` makes. */
    @Test
    fun `the surface-only program compiles and accepts its uniforms`() {
        val shader = RuntimeShader(LiquidGlassShaderPrograms.SURFACE_ONLY)
        setSharedUniforms(shader)
        shader.setFloatUniform("uBaseAlpha", 0.72f)
    }

    @Test
    fun `the programs tolerate a degenerate panel`() {
        // A panel can be laid out at zero size for a frame, and a corner radius
        // larger than the panel is normal for the pill shapes, which pass 999dp.
        val shader = RuntimeShader(LiquidGlassShaderPrograms.SURFACE_ONLY)
        shader.setFloatUniform("uSize", 0f, 0f)
        shader.setFloatUniform("uPad", 0f, 0f)
        shader.setFloatUniform("uCorner", 9999f)
        shader.setFloatUniform("uThickness", 0f)
        shader.setFloatUniform("uSpecular", 0f)
        shader.setFloatUniform("uTintAmount", 0f)
        shader.setFloatUniform("uLight", 0f, 0f)
        shader.setColorUniform("uTintColor", 0)
        shader.setFloatUniform("uBaseAlpha", 0f)
    }

    private fun setSharedUniforms(shader: RuntimeShader) {
        shader.setFloatUniform("uSize", 128f, 64f)
        shader.setFloatUniform("uPad", 8f, 8f)
        shader.setFloatUniform("uCorner", 16f)
        shader.setFloatUniform("uThickness", 10f)
        shader.setFloatUniform("uSpecular", 0.5f)
        shader.setFloatUniform("uTintAmount", 0.18f)
        shader.setFloatUniform("uLight", -0.35f, -0.85f)
        shader.setColorUniform("uTintColor", 0xFF10131A.toInt())
    }

    /** Stands in for the blurred backdrop the effect chain normally supplies. */
    private fun stubBackdrop(): Shader = LinearGradient(
        0f, 0f, 128f, 64f,
        0xFF204080.toInt(), 0xFF80C0FF.toInt(),
        Shader.TileMode.CLAMP,
    )

    /**
     * Rewrites `content.eval(expr)` into `sample(content, expr)`.
     *
     * Skia renamed this call: `sample()` is the old spelling and `.eval()` the
     * current one, and the Skia this test environment links against is old
     * enough to only know the former, while every device the app targets
     * (Android 13+, where RuntimeShader exists at all) only knows the latter.
     *
     * The production shader keeps the correct spelling for the device; the
     * translation happens here so the *rest* of the program — the SDF, the
     * optics, the precision rules, the uniform set, which is where the mistakes
     * actually are — still gets compiled by a real SkSL compiler. Only the shape
     * of three call sites goes unchecked.
     */
    private fun portableSource(source: String): String {
        val builder = StringBuilder(source.length)
        var index = 0
        while (true) {
            val call = source.indexOf(EVAL_CALL, index)
            if (call < 0) {
                builder.append(source, index, source.length)
                return builder.toString()
            }

            builder.append(source, index, call)

            // Walk to the matching close paren so nested calls survive intact.
            val argStart = call + EVAL_CALL.length
            var depth = 1
            var cursor = argStart
            while (cursor < source.length && depth > 0) {
                when (source[cursor]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                cursor++
            }
            check(depth == 0) { "unbalanced parentheses after ${EVAL_CALL} at $call" }

            builder.append("sample(content, ")
                .append(source, argStart, cursor - 1)
                .append(')')
            index = cursor
        }
    }

    private companion object {
        const val EVAL_CALL = "content.eval("
    }
}
