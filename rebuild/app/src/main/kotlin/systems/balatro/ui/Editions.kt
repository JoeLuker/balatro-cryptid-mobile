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

// negative.fs → AGSL: the NEGATIVE base transform. Unlike foil/holo/poly (additive overlays) this
// REPLACES the card colour — HSL, invert lightness, negate+shift hue, back to RGB + a teal-grey
// tint — the dark inverted look. Static (no time). Applied to the base art, not as an overlay.
private val NEG_BASE_AGSL = """
uniform shader content;
uniform float2 size;
$HSL_RGB
half4 main(float2 fragCoord) {
    half4 px = content.eval(fragCoord);
    float a0 = px.a;
    if (a0 < 0.001) return half4(0.0);
    float3 col = float3(px.rgb) / a0;
    float4 sat = HSL(float4(col, 1.0));
    sat.z = 1.0 - sat.z;                       // invert lightness
    sat.x = -sat.x + 0.2;                      // negate + shift hue
    float3 outc = RGB(sat).rgb + 0.8*float3(79.0/255.0, 99.0/255.0, 103.0/255.0);
    float a = a0 < 0.7 ? a0/3.0 : a0;
    return half4(half3(outc * a), half(a));
}
"""

// negative_shine.fs → AGSL: the animated blue/purple shine OVERLAY drawn over the negative base
// (negshine.x = time). Sweeping sin streaks; mostly transparent, alpha-modulated like the other
// edition overlays.
private val NEG_SHINE_AGSL = """
uniform shader content;
uniform float2 negshine;
uniform float time;
uniform float2 size;
half4 main(float2 fragCoord) {
    half4 px = content.eval(fragCoord);
    float a0 = px.a;
    float3 c = a0 > 0.0 ? float3(px.rgb) / a0 : float3(px.rgb);
    float2 uv = fragCoord / size;
    float t = negshine.x;
    float low = min(c.r, min(c.g, c.b));
    float high = max(c.r, max(c.g, c.b));
    float delta = high - low - 0.1;
    float fac  = 0.8 + 0.9*sin(11.0*uv.x + 4.32*uv.y + t*12.0 + cos(t*5.3 + uv.y*4.2 - uv.x*4.0));
    float fac2 = 0.5 + 0.5*sin( 8.0*uv.x + 2.32*uv.y + t*5.0  - cos(t*2.3 + uv.x*8.2));
    float fac3 = 0.5 + 0.5*sin(10.0*uv.x + 5.32*uv.y + t*6.111 + sin(t*5.3 + uv.y*3.2));
    float fac4 = 0.5 + 0.5*sin( 3.0*uv.x + 2.32*uv.y + t*8.111 + sin(t*1.3 + uv.y*11.2));
    float fac5 = sin(0.9*16.0*uv.x + 5.32*uv.y + t*12.0 + cos(t*5.3 + uv.y*4.2 - uv.x*4.0));
    float maxfac = 0.7*max(max(fac, max(fac2, max(fac3, 0.0))) + (fac + fac2 + fac3*fac4), 0.0);
    float3 outc = c*0.5 + float3(0.4, 0.4, 0.8);
    outc.r = outc.r - delta + delta*maxfac*(0.7 + fac5*0.27) - 0.1;
    outc.g = outc.g - delta + delta*maxfac*(0.7 - fac5*0.27) - 0.1;
    outc.b = outc.b - delta + delta*maxfac*0.7 - 0.1;
    float a = a0*(0.5*clamp(0.3*max(low*0.2, delta) + clamp(maxfac*0.1, 0.0, 0.4), 0.0, 1.0) + 0.15*maxfac*(0.1 + delta));
    return half4(half3(outc * a), half(a));
}
"""

/** NEGATIVE base transform applied to the card art itself (replaces, doesn't overlay). Static. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun negativeBaseRenderEffect(wPx: Float, hPx: Float) =
    RenderEffect.createRuntimeShaderEffect(
        RuntimeShader(NEG_BASE_AGSL).apply { setFloatUniform("size", wPx, hPx) },
        "content",
    ).asComposeRenderEffect()

/** Edition overlay RenderEffect by edition tag ("Foil"/"Holo"/"Poly"/"Negative"); [t] animates the
 *  shimmer (the clock); null for no edition. (Negative also needs negativeBaseRenderEffect on the
 *  base art — this returns only its animated shine overlay.) */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun editionRenderEffect(edition: String, wPx: Float, hPx: Float, t: Float) = when (edition) {
    "Foil" -> foilRenderEffect(wPx, hPx, foilR = t, foilG = 1f)
    "Holo" -> shaderEffect(HOLO_AGSL, "holo", t, 0f, t, wPx, hPx)
    "Poly" -> shaderEffect(POLY_AGSL, "poly", t, 0f, t, wPx, hPx)
    "Negative" -> shaderEffect(NEG_SHINE_AGSL, "negshine", t, 0f, t, wPx, hPx)
    else -> null
}

// dissolve.fs → AGSL: the card burn-away / materialize. Unlike editions this REPLACES the card render
// (it eats pixels): a moving noise field `res` is compared to `adjusted_dissolve`; pixels below it go
// transparent, the thin band at the threshold gets the fiery burn colour, and the whole card tints
// toward burn as `dissolve` rises (0 = whole card, 1 = gone). Reverse the value to materialize.
private const val DISSOLVE_AGSL = """
uniform shader content;
uniform float dissolve;
uniform float time;
uniform float2 size;
uniform float4 burn1;
uniform float4 burn2;

half4 main(float2 fragCoord) {
    half4 texh = content.eval(fragCoord);
    float a0 = texh.a;
    float3 col = a0 > 0.0 ? float3(texh.rgb) / a0 : float3(texh.rgb);
    float2 uv = fragCoord / size;

    if (dissolve > 0.01) {                 // tint the card toward the burn colour as it dissolves
        if (burn2.a > 0.01) col = col*(1.0-0.6*dissolve) + 0.6*burn2.rgb*dissolve;
        else if (burn1.a > 0.01) col = col*(1.0-0.6*dissolve) + 0.6*burn1.rgb*dissolve;
    }
    if (dissolve < 0.001) return half4(half3(col*a0), half(a0));

    float adj = (dissolve*dissolve*(3.0-2.0*dissolve))*1.02 - 0.01;
    float t = time*10.0 + 2003.0;
    float2 td = float2(71.0, 95.0);                          // card source px (atlas cell)
    float2 fuv = floor(uv * td) / max(td.x, td.y);
    float2 c = (fuv - 0.5) * 2.3 * max(td.x, td.y);
    float2 f1 = c + 50.0*float2(sin(-t/143.6340), cos(-t/99.4324));
    float2 f2 = c + 50.0*float2(cos( t/53.1532),  cos( t/61.4532));
    float2 f3 = c + 50.0*float2(sin(-t/87.53218), sin(-t/49.0000));
    float field = (1.0 + (cos(length(f1)/19.483) + sin(length(f2)/33.155)*cos(f2.y/15.73) + cos(length(f3)/27.193)*sin(f3.x/21.92)))/2.0;
    float2 b = float2(0.2, 0.8);
    float res = (0.5 + 0.5*cos(adj/82.612 + (field - 0.5)*3.14))
        - (fuv.x > b.y ? (fuv.x - b.y)*(5.0 + 5.0*dissolve) : 0.0)*dissolve
        - (fuv.y > b.y ? (fuv.y - b.y)*(5.0 + 5.0*dissolve) : 0.0)*dissolve
        - (fuv.x < b.x ? (b.x - fuv.x)*(5.0 + 5.0*dissolve) : 0.0)*dissolve
        - (fuv.y < b.x ? (b.x - fuv.y)*(5.0 + 5.0*dissolve) : 0.0)*dissolve;

    float3 rgb = col;
    if (a0 > 0.01 && burn1.a > 0.01 && res < adj + 0.8*(0.5-abs(adj-0.5)) && res > adj) {
        if (res < adj + 0.5*(0.5-abs(adj-0.5))) rgb = burn1.rgb;
        else if (burn2.a > 0.01) rgb = burn2.rgb;
    }
    float aout = res > adj ? a0 : 0.0;
    return half4(half3(rgb*aout), half(aout));
}
"""

/** Card dissolve/materialize RenderEffect. [dissolve] 0 = whole card → 1 = gone; reverse (1→0) to
 *  materialize. [time] animates the burn field. Edge colour: [glass] = white (Card:shatter's
 *  dissolve_colours {{1,1,1,0.8}}); [materialize] = green (start_materialize's G.C.GREEN #4BC292
 *  fallback); otherwise the fiery orange→yellow destroy edge. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun dissolveRenderEffect(dissolve: Float, time: Float, wPx: Float, hPx: Float, glass: Boolean = false, materialize: Boolean = false) =
    RenderEffect.createRuntimeShaderEffect(
        RuntimeShader(DISSOLVE_AGSL).apply {
            setFloatUniform("dissolve", dissolve); setFloatUniform("time", time); setFloatUniform("size", wPx, hPx)
            when {
                glass -> { setFloatUniform("burn1", 1f, 1f, 1f, 0.8f); setFloatUniform("burn2", 0f, 0f, 0f, 0f) }
                materialize -> { setFloatUniform("burn1", 0.294f, 0.761f, 0.573f, 1f); setFloatUniform("burn2", 0f, 0f, 0f, 0f) }
                else -> { setFloatUniform("burn1", 1f, 0.35f, 0.1f, 1f); setFloatUniform("burn2", 1f, 0.75f, 0.15f, 1f) }
            }
        },
        "content",
    ).asComposeRenderEffect()
