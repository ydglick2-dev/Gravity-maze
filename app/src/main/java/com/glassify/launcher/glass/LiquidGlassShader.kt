package com.glassify.launcher.glass

/**
 * The AGSL behind the glass.
 *
 * The compositor blurs whatever is behind the window; this program paints the
 * material on top of that blur. Which means every cue that says "glass" rather
 * than "translucent panel" has to come from the surface itself, and the ordering
 * of those cues by how much they matter is not obvious:
 *
 *  1. **Fresnel.** Glass turns mirror-like at grazing angles. On a flat panel
 *     that means the rim, and it is the single strongest cue — far more than the
 *     blur, which any frosted plastic also has. A panel with a correct Fresnel
 *     falloff reads as glass even at low opacity.
 *  2. **A moving specular.** The highlight travels as the phone tilts, which is
 *     what separates a lit surface from a painted gradient.
 *  3. **Grain.** A large blur leaves visible banding on any gradient behind it.
 *     A half-percent of noise destroys the banding and, as a side effect, reads
 *     as the faint texture real frosted glass has.
 *  4. **Iridescence.** A trace of spectral colour at the rim. Barely visible in
 *     isolation and unmistakable when removed.
 *
 * The tint is deliberately weak. Earlier revisions leaned on it for the whole
 * effect and the result looked like milky plastic: opacity hides the backdrop,
 * and seeing through it is the entire point of the material.
 */
object LiquidGlassShaderPrograms {

    /**
     * Signed distance field for the panel plus its gradient.
     *
     * The gradient is the surface normal, and every optical term below is a
     * function of it and of the distance.
     */
    private const val SDF = """
        uniform float2 uSize;      // panel size in px
        uniform float  uCorner;    // corner radius in px
        uniform float  uThickness; // how deep the bevel reads, in px
        uniform float2 uLight;     // light direction, driven by device tilt
        uniform float  uSpecular;  // 0..1 highlight strength
        uniform float  uBaseAlpha; // opacity of the flat interior
        uniform float  uGrain;     // 0..1 noise amount
        uniform half4  uTintColor;

        float sdRoundBox(float2 p, float2 b, float r) {
            float2 q = abs(p) - b + r;
            return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;
        }

        float sdPanel(float2 p) {
            return sdRoundBox(p - uSize * 0.5, uSize * 0.5, uCorner);
        }

        float2 sdNormal(float2 p) {
            const float e = 1.0;
            float2 g = float2(
                sdPanel(p + float2(e, 0.0)) - sdPanel(p - float2(e, 0.0)),
                sdPanel(p + float2(0.0, e)) - sdPanel(p - float2(0.0, e))
            );
            float len = length(g);
            return len > 0.0001 ? g / len : float2(0.0, -1.0);
        }

        // Cheap value hash. Only ever used at half a percent amplitude, so its
        // statistical shortcomings do not matter; what matters is that it is
        // stable per pixel and costs one instruction.
        float hash21(float2 p) {
            return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
        }
    """

    /**
     * The glass surface.
     *
     * Alpha is not constant across the panel: it rises towards the rim, because
     * a real bevel presents more material to look through at its edge than at
     * its centre. That single gradient does more for the illusion of thickness
     * than any amount of drawn outline.
     */
    const val SURFACE_ONLY = """
        $SDF

        half4 main(float2 fragCoord) {
            float sd = sdPanel(fragCoord);

            // Antialiased shape. The panel clips itself, so the caller does not
            // have to.
            float shape = 1.0 - smoothstep(-1.0, 1.0, sd);
            if (shape <= 0.0) {
                return half4(0.0);
            }

            float2 n = sdNormal(fragCoord);

            // 1 at the very edge, falling to 0 by uThickness inside.
            float depth = clamp(-sd / max(uThickness, 1.0), 0.0, 1.0);
            float fresnel = pow(1.0 - depth, 3.0);

            float2 l = normalize(uLight + float2(0.0001));
            float facing = dot(n, l);

            // A tight highlight where the rim faces the light, a broader and
            // dimmer one opposite it so the far edge never goes dead, and a
            // constant hairline so the panel keeps a defined edge against a
            // busy background.
            float hot = pow(max(facing, 0.0), 10.0) * fresnel;
            float back = pow(max(-facing, 0.0), 6.0) * fresnel * 0.32;
            float hairline = (1.0 - smoothstep(0.0, 1.6, -sd)) * 0.42;

            // A sheen along the top edge, independent of tilt. Glass lit from
            // above is the default expectation, and this keeps that reading even
            // when the phone is flat on a table.
            float sheen = (1.0 - smoothstep(0.0, uThickness * 2.5, fragCoord.y)) * 0.10;

            float highlight = (hot + back) * uSpecular + hairline + sheen + fresnel * 0.10;

            // Thickness on the unlit side.
            float shade = max(-facing, 0.0) * fresnel * 0.22;

            // Faint spectral split at the rim, keyed to the normal so it sweeps
            // round the panel with the light rather than sitting still.
            float3 iris = 0.5 + 0.5 * cos(6.2831 * (float3(0.0, 0.33, 0.67) + n.x * 0.18 + n.y * 0.09));
            float3 lit = highlight * mix(float3(1.0), iris, 0.22 * fresnel);

            float3 tint = float3(uTintColor.rgb);
            float3 rgb = tint * (1.0 - shade) + lit;

            // Breaks up the banding a large blur leaves in smooth gradients.
            rgb += float3((hash21(fragCoord) - 0.5) * uGrain);

            // Interior stays close to clear; the rim thickens. Clamped because
            // the highlight terms can overlap at a corner.
            float a = clamp(uBaseAlpha + fresnel * 0.30 + highlight * 0.75, 0.0, 1.0) * shape;

            return half4(half3(clamp(rgb, 0.0, 1.0) * a), half(a));
        }
    """
}
