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

// HSL<->RGB helpers from foil.fs (used by holo/polychrome), as float math to avoid AGSL half/float
// strictness. The animated "field" swirl (shared by dissolve/holo/poly) is inlined per shader.
private const val HSL_RGB = """
float hue(float s, float t, float h) {
    float hs = mod(h, 1.0) * 6.0;
    if (hs < 1.0) return (t - s) * hs + s;
    if (hs < 3.0) return t;
    if (hs < 4.0) return (t - s) * (4.0 - hs) + s;
    return s;
}
float4 RGB(float4 c) {
    if (c.y < 0.0001) return float4(float3(c.z), c.a);
    float t = (c.z < 0.5) ? c.y*c.z + c.z : -c.y*c.z + (c.y + c.z);
    float s = 2.0*c.z - t;
    return float4(hue(s,t,c.x + 1.0/3.0), hue(s,t,c.x), hue(s,t,c.x - 1.0/3.0), c.w);
}
float4 HSL(float4 c) {
    float low = min(c.r, min(c.g, c.b));
    float high = max(c.r, max(c.g, c.b));
    float delta = high - low;
    float sum = high + low;
    float4 hsl = float4(0.0, 0.0, 0.5*sum, c.a);
    if (delta == 0.0) return hsl;
    hsl.y = (hsl.z < 0.5) ? delta/sum : delta/(2.0 - sum);
    if (high == c.r) hsl.x = (c.g - c.b)/delta;
    else if (high == c.g) hsl.x = (c.b - c.r)/delta + 2.0;
    else hsl.x = (c.r - c.g)/delta + 4.0;
    hsl.x = mod(hsl.x/6.0, 1.0);
    return hsl;
}
float field_swirl(float2 uv, float2 size, float t, float spread) {
    float2 fuv = floor(uv * float2(71.0, 95.0)) / float2(71.0, 95.0);
    float2 c = (fuv - 0.5) * spread;
    float2 f1 = c + 50.0*float2(sin(-t/143.6340), cos(-t/99.4324));
    float2 f2 = c + 50.0*float2(cos( t/53.1532),  cos( t/61.4532));
    float2 f3 = c + 50.0*float2(sin(-t/87.53218), sin(-t/49.0000));
    return (1.0 + (cos(length(f1)/19.483) + sin(length(f2)/33.155)*cos(f2.y/15.73) + cos(length(f3)/27.193)*sin(f3.x/21.92)))/2.0;
}
"""

private val HOLO_AGSL = """
uniform shader content;
uniform float2 holo;
uniform float time;
uniform float2 size;
$HSL_RGB
half4 main(float2 fragCoord) {
    half4 texh = content.eval(fragCoord);
    if (texh.a < 0.01) return half4(0.0);
    float a0 = texh.a;
    float3 col = float3(texh.rgb) / a0;
    float2 uv = fragCoord / size;
    float4 hsl = HSL(0.5*float4(col, 1.0) + 0.5*float4(0.0, 0.0, 1.0, 1.0));
    float field = field_swirl(uv, size, holo.y*7.221 + time, 250.0);
    float res = 0.5 + 0.5*cos(holo.x*2.612 + (field - 0.5)*3.14);
    float low = min(col.r, min(col.g, col.b));
    float high = max(col.r, max(col.g, col.b));
    float delta = 0.2 + 0.3*(high - low) + 0.1*high;
    float g = 0.79;
    float fac = 0.5*max(max(max(0.0, 7.0*abs(cos(uv.x*g*20.0))-6.0), max(0.0, 7.0*cos(uv.y*g*45.0 + uv.x*g*20.0)-6.0)), max(0.0, 7.0*cos(uv.y*g*45.0 - uv.x*g*20.0)-6.0));
    hsl.x = hsl.x + res + fac;
    hsl.y = hsl.y*1.3;
    hsl.z = hsl.z*0.6 + 0.4;
    float3 outc = (1.0 - delta)*col + delta*RGB(hsl).rgb*float3(0.9, 0.8, 1.2);
    float a = a0 < 0.7 ? a0/3.0 : a0;
    return half4(half3(outc * a), half(a));
}
"""

private val POLY_AGSL = """
uniform shader content;
uniform float2 poly;
uniform float time;
uniform float2 size;
$HSL_RGB
half4 main(float2 fragCoord) {
    half4 texh = content.eval(fragCoord);
    if (texh.a < 0.01) return half4(0.0);
    float a0 = texh.a;
    float3 col = float3(texh.rgb) / a0;
    float2 uv = fragCoord / size;
    float low = min(col.r, min(col.g, col.b));
    float high = max(col.r, max(col.g, col.b));
    float delta = high - low;
    float satf = 1.0 - max(0.0, 0.05*(1.1 - delta));
    float4 hsl = HSL(float4(col.r*satf, col.g*satf, col.b, 1.0));
    float field = field_swirl(uv, size, poly.y*2.221 + time, 50.0);
    float res = 0.5 + 0.5*cos(poly.x*2.612 + (field - 0.5)*3.14);
    hsl.x = hsl.x + res + poly.y*0.04;
    hsl.y = min(0.6, hsl.y + 0.5);
    float3 outc = RGB(hsl).rgb;
    float a = a0 < 0.7 ? a0/3.0 : a0;
    return half4(half3(outc * a), half(a));
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun shaderEffect(agsl: String, name: String, x: Float, y: Float, time: Float, wPx: Float, hPx: Float) =
    RenderEffect.createRuntimeShaderEffect(
        RuntimeShader(agsl).apply {
            setFloatUniform(name, x, y); setFloatUniform("time", time); setFloatUniform("size", wPx, hPx)
        },
        "content",
    ).asComposeRenderEffect()

/** Edition overlay RenderEffect by edition tag ("Foil"/"Holo"/"Poly"); [t] animates the shimmer
 *  (the clock); null for no edition. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun editionRenderEffect(edition: String, wPx: Float, hPx: Float, t: Float) = when (edition) {
    "Foil" -> foilRenderEffect(wPx, hPx, foilR = t, foilG = 1f)
    "Holo" -> shaderEffect(HOLO_AGSL, "holo", t, 0f, t, wPx, hPx)
    "Poly" -> shaderEffect(POLY_AGSL, "poly", t, 0f, t, wPx, hPx)
    else -> null
}
