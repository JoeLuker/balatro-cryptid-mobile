package systems.balatro.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush

/**
 * Balatro's actual background felt — a 1:1 port of resources/shaders/background.fs to AGSL.
 * The game draws G.SPLASH_BACK with this shader; the run felt feeds it (game.lua:2432):
 *   colour_1/2/3 = G.C.BACKGROUND.{C,L,D}, contrast, spin_amount, time (REAL_SHADER), spin_time.
 * For the play state the background is G.C.BLIND.Small = #50846e (common_events.lua), and
 * ease_background_colour with only new_colour sets C = ×0.9, L = ×1.3, D = ×0.7; contrast = 1;
 * spin_amount = 0 (no swirl during normal play). `time` churns the paint pattern (~the felt's life).
 *
 * RuntimeShader is API 33+. Below that, fall back to the measured radial-gradient approximation.
 */
@Composable
fun BalatroFelt(modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        FeltShader(modifier)
    } else {
        Box(modifier.background(Brush.radialGradient(
            listOf(Color(0xFF42926E), Color(0xFF356650), Color(0xFF2E5A46)))))
    }
}

private const val FELT_AGSL = """
uniform float time;
uniform float spin_time;
uniform float2 resolution;
uniform half4 colour_1;
uniform half4 colour_2;
uniform half4 colour_3;
uniform float contrast;
uniform float spin_amount;

const float PIXEL_SIZE_FAC = 700.0;
const float SPIN_EASE = 0.5;

half4 main(float2 fragCoord) {
    float pixel_size = length(resolution) / PIXEL_SIZE_FAC;
    float2 uv = (floor(fragCoord * (1.0 / pixel_size)) * pixel_size - 0.5 * resolution) / length(resolution) - float2(0.12, 0.0);
    float uv_len = length(uv);

    float speed = (spin_time * SPIN_EASE * 0.2) + 302.2;
    float new_pixel_angle = atan(uv.y, uv.x) + speed - SPIN_EASE * 20.0 * (spin_amount * uv_len + (1.0 - spin_amount));
    float2 mid = (resolution / length(resolution)) / 2.0;
    uv = float2(uv_len * cos(new_pixel_angle) + mid.x, uv_len * sin(new_pixel_angle) + mid.y) - mid;

    uv *= 30.0;
    speed = time * 2.0;
    float2 uv2 = float2(uv.x + uv.y, uv.x + uv.y);

    for (int i = 0; i < 5; i++) {
        uv2 += sin(max(uv.x, uv.y)) + uv;
        uv += 0.5 * float2(cos(5.1123314 + 0.353 * uv2.y + speed * 0.131121), sin(uv2.x - 0.113 * speed));
        uv -= 1.0 * cos(uv.x + uv.y) - 1.0 * sin(uv.x * 0.711 - uv.y);
    }

    float contrast_mod = (0.25 * contrast + 0.5 * spin_amount + 1.2);
    float paint_res = min(2.0, max(0.0, length(uv) * 0.035 * contrast_mod));
    float c1p = max(0.0, 1.0 - contrast_mod * abs(1.0 - paint_res));
    float c2p = max(0.0, 1.0 - contrast_mod * abs(paint_res));
    float c3p = 1.0 - min(1.0, c1p + c2p);

    float a = 0.3 / contrast;
    half4 mixcol = colour_1 * c1p + colour_2 * c2p + half4(colour_3.rgb * c3p, colour_1.a * c3p);
    return colour_1 * a + mixcol * (1.0 - a);
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun FeltShader(modifier: Modifier) {
    val shader = remember {
        RuntimeShader(FELT_AGSL).apply {
            // G.C.BLIND.Small #50846e → C ×0.9, L ×1.3, D ×0.7 (ease_background_colour, new_colour only)
            setFloatUniform("colour_1", 0.28235f, 0.46588f, 0.38824f, 1f)
            setFloatUniform("colour_2", 0.40784f, 0.67294f, 0.56078f, 1f)
            setFloatUniform("colour_3", 0.21961f, 0.36235f, 0.30196f, 1f)
            setFloatUniform("contrast", 1f)
            setFloatUniform("spin_amount", 0f)
            setFloatUniform("spin_time", 0f)
        }
    }
    val brush = remember { ShaderBrush(shader) }
    var time by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        var start = 0L
        while (true) {
            withFrameNanos { now ->
                if (start == 0L) start = now
                time = (now - start) / 1e9f
            }
        }
    }
    Box(modifier.drawBehind {
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("time", time)        // read animated state → redraws each frame
        drawRect(brush)
    })
}
