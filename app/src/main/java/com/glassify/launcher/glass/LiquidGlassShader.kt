package com.glassify.launcher.glass

/**
 * The AGSL programs behind the glass.
 *
 * Two variants exist because the two contexts have very different access to what
 * is behind the panel:
 *
 *  - [REFRACTING] runs inside our own window, where we hand it the backdrop as a
 *    child shader. It can bend that backdrop, so it produces real edge
 *    refraction — the wallpaper visibly stretches around the rim, which is the
 *    single most recognisable part of the effect.
 *
 *  - [SURFACE_ONLY] runs on overlay windows floating over other apps. Their
 *    pixels are not ours to sample (that would need a screen recording), so the
 *    system blurs behind the window for us and this program only paints the
 *    glass *surface*: rim light, specular, tint, inner shadow.
 */
object LiquidGlassShaderPrograms {

    /**
     * Shared prelude: the signed-distance field for a rounded box plus its
     * gradient. The gradient is the surface normal, and every optical term below
     * — refraction, specular, rim light, inner shadow — is a function of it.
     */
    private const val SDF = """
        uniform float2 uSize;      // panel size in px (excluding padding)
        uniform float2 uPad;       // padding baked into the layer on each side
        uniform float  uCorner;    // corner radius in px
        uniform float  uThickness; // how deep the glass edge reads, in px
        uniform float2 uLight;     // light direction, driven by device tilt
        uniform float  uSpecular;  // 0..1 highlight strength
        uniform float  uTintAmount;
        uniform half4  uTintColor;

        float sdRoundBox(float2 p, float2 b, float r) {
            float2 q = abs(p) - b + r;
            return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;
        }

        // Distance from the panel centre, in the panel's own coordinate space.
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
    """

    /**
     * Everything that makes the surface read as glass rather than as a blurred
     * rectangle. Shared by both variants so the two look like the same material.
     *
     * `sd` is negative inside the panel; `n` points outwards.
     */
    private const val SURFACE = """
        // Specular: a tight highlight where the surface normal faces the light,
        // and a wider, dimmer one on the opposite edge so the rim never goes
        // completely dead. uLight is fed from the accelerometer, so both travel
        // around the panel as the phone is tilted.
        //
        // Everything here stays in float and is narrowed to half once, at the
        // end of main(). SkSL will not silently mix the two precisions, and a
        // stray implicit conversion fails to compile at runtime rather than at
        // build time.
        float rimLight(float2 n, float sd) {
            float rim = 1.0 - smoothstep(0.0, uThickness, -sd);
            float2 l = normalize(uLight + float2(0.0001));
            float facing = dot(n, l);

            float hot  = pow(max(facing, 0.0), 12.0);
            float back = pow(max(-facing, 0.0), 5.0) * 0.35;

            // The very outermost pixel ring always carries a hairline so the
            // panel keeps a defined edge against a busy wallpaper.
            float hairline = smoothstep(1.5, 0.0, -sd) * 0.28;

            return (hot + back) * rim * uSpecular + hairline;
        }

        // Glass is thicker where light does not reach: a soft darkening just
        // inside the rim on the shadow side, which is what gives the panel
        // volume instead of looking like a decal.
        float innerShade(float2 n, float sd) {
            float band = 1.0 - smoothstep(0.0, uThickness * 2.0, -sd);
            float2 l = normalize(uLight + float2(0.0001));
            return band * max(-dot(n, l), 0.0) * 0.22;
        }
    """

    /**
     * Full-fat variant. `content` is the already-blurred backdrop.
     *
     * The refraction works by pulling the sample point *inwards* along the
     * surface normal near the rim: the closer to the edge, the further in we
     * reach, so a band of the backdrop is compressed into the edge exactly the
     * way a real bevel does it. Splitting the pull distance per channel adds the
     * faint colour fringing that sells the material.
     */
    const val REFRACTING = """
        uniform shader content;
        uniform float uRefraction;  // px of inward pull at the very edge
        uniform float uAberration;  // per-channel spread, as a fraction

        $SDF
        $SURFACE

        half4 main(float2 fragCoord) {
            float2 p = fragCoord - uPad;
            float sd = sdPanel(p);

            // Antialiased shape. Everything outside the panel is dropped, which
            // also clips the blur bleeding out of the padded layer.
            float alpha = 1.0 - smoothstep(-1.0, 1.0, sd);
            if (alpha <= 0.0) {
                return half4(0.0);
            }

            float2 n = sdNormal(p);

            // 0 at the centre, 1 at the rim, biased so the bend is concentrated
            // in the outer few pixels.
            float edge = 1.0 - smoothstep(0.0, uThickness, -sd);
            float lens = edge * edge;
            float2 pull = -n * lens * uRefraction;

            // Each eval() lands in its own variable before being swizzled:
            // SkSL will not swizzle the result of a child-shader evaluation
            // inline, and rejects the whole program if you try.
            float3 sampled;
            if (uAberration > 0.0) {
                float spread = uAberration * lens;
                half4 red   = content.eval(fragCoord + pull * (1.0 + spread));
                half4 green = content.eval(fragCoord + pull);
                half4 blue  = content.eval(fragCoord + pull * (1.0 - spread));
                sampled = float3(float(red.r), float(green.g), float(blue.b));
            } else {
                half4 direct = content.eval(fragCoord + pull);
                sampled = float3(direct.rgb);
            }

            float3 tint = float3(uTintColor.rgb);
            float3 rgb = mix(sampled, tint, uTintAmount * float(uTintColor.a));
            rgb = rgb * (1.0 - innerShade(n, sd));
            rgb = rgb + float3(rimLight(n, sd));

            // Premultiplied, which is what a runtime shader effect is expected
            // to return.
            return half4(half3(rgb * alpha), half(alpha));
        }
    """

    /**
     * Overlay variant: no backdrop to sample, so it paints a translucent tinted
     * sheet with the same rim and specular model. Sitting on top of the system's
     * own blur-behind, the result is close enough to the full effect that the
     * difference is hard to spot in motion.
     */
    const val SURFACE_ONLY = """
        uniform float uBaseAlpha;

        $SDF
        $SURFACE

        half4 main(float2 fragCoord) {
            float2 p = fragCoord - uPad;
            float sd = sdPanel(p);

            float alpha = 1.0 - smoothstep(-1.0, 1.0, sd);
            if (alpha <= 0.0) {
                return half4(0.0);
            }

            float2 n = sdNormal(p);

            // Without a backdrop the tint *is* the surface, so it carries the
            // full weight here rather than being mixed into sampled pixels.
            float3 rgb = float3(uTintColor.rgb);
            rgb = rgb * (1.0 - innerShade(n, sd));
            rgb = rgb + float3(rimLight(n, sd));

            float a = alpha * uBaseAlpha;
            return half4(half3(rgb * a), half(a));
        }
    """
}
