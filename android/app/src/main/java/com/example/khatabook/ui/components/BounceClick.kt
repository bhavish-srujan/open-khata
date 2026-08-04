package com.example.khatabook.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.collectLatest

/**
 * High-performance bounce-click modifier.
 * Uses a stable `Animatable` remembered once — no allocation per recomposition.
 * The duplicate `.clickable` is removed: bounceClick registers its own click handler.
 */
fun Modifier.bounceClick(
    scaleDown: Float = 0.98f,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val scaleAnim = remember { Animatable(1f) }

    // Single coroutine that listens to press/release — no animateFloatAsState subscription
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> scaleAnim.animateTo(
                    scaleDown,
                    tween(durationMillis = 100)
                )
                is PressInteraction.Release,
                is PressInteraction.Cancel -> scaleAnim.animateTo(
                    1f,
                    tween(durationMillis = 100)
                )
            }
        }
    }

    this
        .graphicsLayer {
            scaleX = scaleAnim.value
            scaleY = scaleAnim.value
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
}
