package com.glassify.launcher.glass

/**
 * The AGSL behind the glass — calibrated against the user's reference design
 * (`Liquid Glass Demo.dc.html`).
 *
 * The reference builds its card from four layers, and this program reproduces
 * them with the design's own numbers:
 *
 *  1. **Fill** — `rgba(255,255,255,.04)`: a nearly clear *white* sheet. The
 *     design's glass is transparent, not frosted or dark; readability comes
 *     from separate tint plates under the text, never from the pane itself.
 *  2. **Rim** — a 1px hairline at `rgba(255,255,255,.28)` plus a 1.5px inner
 *     bevel that is directional and fixed: bright on the top edge (.55), dim
 *     on the bottom (.16), medium on the sides (.22). This is what gives the
 *     pane its machined edge.
 *  3. **Specular** — an interior elliptical highlight (~0.4 of the panel size,
 *     white .34 fading through .07 at 45% to nothing at 72%) that *travels*.
 *     The reference moves it with the pointer and calls that "the gyroscope
 *     substitute"; here it is the actual gyroscope, via uLight.
 *  4. **Shadow** — `0 24px 60px rgba(0,0,0,.5)`, applied outside the shader by
 *     the elevation modifier.
 *
 * The reference also demonstrates true edge refraction via an SVG displacement
 * map, and explicitly labels the blur+rim+specular build as the legitimate
 * approximation for surfaces that cannot sample their backdrop. Overlay windows
 * cannot — the compositor's blur-behind stands in — so this is that card,
 * with the design's numbers.
 */
object LiquidGlassShaderPrograms {

    /**
     * Signed distance field for the panel plus its gradient. The gradient is
     * the outward surface normal; the bevel's directionality reads from it.
     */
    private const val SDF = """
        uniform float2 uSize;      // panel size in px
        uniform float  uCorner;    // corner radius in px
        uniform float  uThickness; // bevel band width in px (design: ~1.5)
        uniform float2 uLight;     // tilt vector; drives the specular position
        uniform float  uSpecular;  // peak specular alpha (design: 0.34)
        uniform float  uBaseAlpha; // fill alpha (design: 0.04)
        uniform float  uGrain;     // 0..1 noise amount
        uniform half4  uTintColor; // fill colour (design: white; island: dark)

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

        // Cheap value hash, used at ~1.5% amplitude to break blur banding.
        float hash21(float2 p) {
            return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
        }
    """

    const val SURFACE_ONLY = """
        $SDF

        half4 main(float2 fragCoord) {
            float sd = sdPanel(fragCoord);

            // Antialiased shape; the panel clips itself.
            float shape = 1.0 - smoothstep(-1.0, 1.0, sd);
            if (shape <= 0.0) {
                return half4(0.0);
            }

            float2 n = sdNormal(fragCoord);
            float bevelW = max(uThickness, 1.0);

            // Layer 2a: the 1px hairline border, white at 0.28.
            float hairline = (1.0 - smoothstep(0.0, 1.2, -sd)) * 0.28;

            // Layer 2b: the inner bevel. Fixed directional weights from the
            // design: top .55, bottom .16, sides .22 — glass lit from above,
            // regardless of how the phone is held. Confined to a tight band.
            float band = (1.0 - smoothstep(0.0, bevelW + 1.0, -sd - 1.0));
            float bevelTone = 0.55 * max(-n.y, 0.0)
                            + 0.16 * max(n.y, 0.0)
                            + 0.22 * abs(n.x);
            float bevel = band * bevelTone;

            // Layer 3: the travelling elliptical specular. The design paints a
            // radial gradient about 0.4 of the panel across, peaking at .34 and
            // gone by 72% of its radius; its centre rides the tilt vector.
            float2 specCenter = uSize * (float2(0.5) + clamp(uLight, -1.0, 1.0) * 0.5);
            float2 d = (fragCoord - specCenter) / (uSize * 0.4 + float2(0.0001));
            float specDist = length(d);
            float specFall = 1.0 - smoothstep(0.0, 1.0, specDist / 0.72);
            // Two-stop profile: hot core, thin haze at mid-radius.
            float spec = uSpecular * (specFall * specFall * 0.8 + specFall * 0.2);

            // Layer 1: the fill, premultiplied against the pane's own alpha.
            float3 fillColor = float3(uTintColor.rgb);
            float fillA = uBaseAlpha;

            float lightA = clamp(hairline + bevel + spec, 0.0, 1.0);
            float a = clamp(fillA + lightA, 0.0, 1.0) * shape;

            // White light over the fill; grain breaks the compositor blur's
            // banding underneath (the one departure from the reference, which
            // never faces 8-bit gradient banding at these sizes).
            float3 rgb = fillColor * fillA + float3(1.0) * lightA;
            rgb += float3((hash21(fragCoord) - 0.5) * uGrain);

            return half4(half3(clamp(rgb, 0.0, 1.0) * shape), half(a));
        }
    """
}
