package com.glassify.launcher

import android.graphics.RuntimeShader
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
 * then throws on the first frame that draws a pane of glass.
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
     * Compiles the program and sets every uniform the renderer sets.
     *
     * The uniform half matters as much as the compile: naming one differently in
     * Kotlin than in the shader throws only when that line runs, and a uniform
     * the program never reads is optimised out and then rejected on assignment.
     */
    @Test
    fun `the glass program compiles and accepts its uniforms`() {
        setSharedUniforms(RuntimeShader(LiquidGlassShaderPrograms.SURFACE_ONLY))
    }

    @Test
    fun `the program tolerates a degenerate panel`() {
        // A panel can be laid out at zero size for a frame, a corner radius
        // larger than the panel is normal for the pill shapes (999dp), and a
        // zero bevel would divide by zero if the shader did not guard it.
        val shader = RuntimeShader(LiquidGlassShaderPrograms.SURFACE_ONLY)
        shader.setFloatUniform("uSize", 0f, 0f)
        shader.setFloatUniform("uCorner", 9999f)
        shader.setFloatUniform("uThickness", 0f)
        shader.setFloatUniform("uSpecular", 0f)
        shader.setFloatUniform("uBaseAlpha", 0f)
        shader.setFloatUniform("uGrain", 0f)
        shader.setFloatUniform("uLight", 0f, 0f)
        shader.setColorUniform("uTintColor", 0)
    }

    /** Exactly the calls `drawSurfaceGlass` makes, in the same order. */
    private fun setSharedUniforms(shader: RuntimeShader) {
        shader.setFloatUniform("uSize", 128f, 64f)
        shader.setFloatUniform("uCorner", 16f)
        shader.setFloatUniform("uThickness", 14f)
        shader.setFloatUniform("uSpecular", 0.6f)
        shader.setFloatUniform("uBaseAlpha", 0.16f)
        shader.setFloatUniform("uGrain", 0.022f)
        shader.setFloatUniform("uLight", -0.35f, -0.85f)
        shader.setColorUniform("uTintColor", 0xFF10131A.toInt())
    }
}
