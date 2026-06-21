package systems.balatro.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asComposeRenderEffect

/**
 * Card EDITION shaders, ported GLSL→AGSL (the foil/holo/polychrome card shimmer). This is the spike:
 * resources/shaders/foil.fs's `effect()` (the metallic shimmer), re-authored as an AGSL RuntimeShader
 * that samples the card content (`uniform shader content`) and outputs the foil overlay. The
 * dissolve_mask + hover-bulge vertex pass are omitted (animation/transition extras, not the edition).
 *
 * AGSL's content.eval() returns PREMULTIPLIED colour, so we un-premultiply, run the foil math on the
 * straight colour exactly as the GLSL did, then re-premultiply the output. `foil` = the animation
 * vector (foil.r churns the shimmer, foil.g the angle); `size` = the card draw size in px (the GLSL
 * used texture_details for the card aspect — here the content IS just the card, so size encodes it).
 * RuntimeShader is API 33+.
 */
private const val FOIL_AGSL = """
uniform shader content;
uniform float2 foil;
uniform float2 size;

half4 main(float2 fragCoord) {
    half4 tex = content.eval(fragCoord);
    if (tex.a < 0.01) return half4(0.0);
    half3 col = tex.rgb / tex.a;                       // un-premultiply for the foil math

    float2 uv = fragCoord / size;
    float2 auv = uv - float2(0.5, 0.5);
    auv.x = auv.x * (size.x / size.y);

    float low = min(col.r, min(col.g, col.b));
    float high = max(col.r, max(col.g, col.b));
    float delta = min(high, max(0.5, 1.0 - low));

    float fac = max(min(2.0*sin((length(90.0*auv) + foil.x*2.0) + 3.0*(1.0+0.8*cos(length(113.1121*auv) - foil.x*3.121))) - 1.0 - max(5.0-length(90.0*auv), 0.0), 1.0), 0.0);
    float2 rot = float2(cos(foil.x*0.1221), sin(foil.x*0.3512));
    float angle = dot(rot, auv) / (length(rot)*length(auv) + 1e-4);
    float fac2 = max(min(5.0*cos(foil.y*0.3 + angle*3.14*(2.2+0.9*sin(foil.x*1.65 + 0.2*foil.y))) - 4.0 - max(2.0-length(20.0*auv), 0.0), 1.0), 0.0);
    float fac3 = 0.3*max(min(2.0*sin(foil.x*5.0 + uv.x*3.0 + 3.0*(1.0+0.5*cos(foil.x*7.0))) - 1.0, 1.0), -1.0);
    float fac4 = 0.3*max(min(2.0*sin(foil.x*6.66 + uv.y*3.8 + 3.0*(1.0+0.5*cos(foil.x*3.414))) - 1.0, 1.0), -1.0);
    float maxfac = max(max(fac, max(fac2, max(fac3, max(fac4, 0.0)))) + 2.2*(fac+fac2+fac3+fac4), 0.0);

    half3 outc = col;
    outc.r = col.r - delta + delta*maxfac*0.3;
    outc.g = col.g - delta + delta*maxfac*0.3;
    outc.b = col.b + delta*maxfac*1.9;
    float a = min(tex.a, 0.3*tex.a + 0.9*min(0.5, maxfac*0.1));   // shimmer overlay alpha
    return half4(outc * a, a);                                    // re-premultiply
}
"""

/** Build the foil overlay RenderEffect for a card of [wPx]×[hPx]. [foilR]/[foilG] animate the
 *  shimmer (foil.r/foil.g in the GLSL); a static value already shows the metallic pattern. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun foilRenderEffect(wPx: Float, hPx: Float, foilR: Float = 3f, foilG: Float = 1f) =
    RenderEffect.createRuntimeShaderEffect(
        RuntimeShader(FOIL_AGSL).apply {
            setFloatUniform("foil", foilR, foilG)
            setFloatUniform("size", wPx, hPx)
        },
        "content",
    ).asComposeRenderEffect()
