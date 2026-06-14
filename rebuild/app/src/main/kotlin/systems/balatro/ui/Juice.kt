package systems.balatro.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Balatro 'feel': a card is never static. It idly wobbles (each one phased differently so
 * the hand breathes), springs up and scales when selected, and casts a soft shadow on the felt
 * that grows as it lifts. Compose's spring()/infiniteTransition give the bouncy, alive motion
 * that makes Balatro Balatro — no static UI, everything has weight and snap.
 */
@Composable
fun JuicyCard(
    bitmap: ImageBitmap?,
    label: String,
    selected: Boolean,
    index: Int,
    width: Dp,
    onClick: () -> Unit,
    baseTilt: Float = 0f,   // static fan rotation
    baseLift: Float = 0f,   // static fan arc (px)
    badges: @Composable BoxScope.() -> Unit = {},
) {
    val wobble = rememberInfiniteTransition(label = "wobble$index")
    val tilt by wobble.animateFloat(
        initialValue = -1.6f, targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            tween(1200 + (index % 5) * 140, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ),
        label = "tilt",
    )
    val lift by animateFloatAsState(
        if (selected) -34f else 0f, spring(dampingRatio = 0.42f, stiffness = 420f), label = "lift"
    )
    val scale by animateFloatAsState(
        if (selected) 1.12f else 1f, spring(dampingRatio = 0.5f, stiffness = 420f), label = "scale"
    )
    val shadow by animateFloatAsState(if (selected) 16f else 6f, label = "shadow")
    val h = width * (190f / 142f)
    Box(
        Modifier
            .size(width, h)
            .graphicsLayer {
                rotationZ = baseTilt + tilt + if (selected) -baseTilt - 4f else 0f
                translationY = baseLift + lift
                scaleX = scale; scaleY = scale
            }
            .clickable { onClick() }
    ) {
        Box(
            Modifier.matchParentSize()
                .shadow(shadow.dp, RoundedCornerShape(6.dp))
                .clip(RoundedCornerShape(6.dp))
                .background(Balatro.FeltDark)
        ) {
            bitmap?.let { Image(it, label, Modifier.matchParentSize()) }
        }
        badges()
    }
}
