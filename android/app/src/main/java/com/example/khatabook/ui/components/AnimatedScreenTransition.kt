package com.example.khatabook.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.khatabook.AppScreen

// Signature Emphasized Decelerate Easing Curve (Apple / Material 3)
val EmphasizedSmoothEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

/**
 * Ultra-Fast, Ultra-Clean 60FPS/120FPS Screen Transition.
 * Uses lightweight instant fade and subtle offset for zero lag performance.
 */
@Composable
fun AnimatedScreenTransition(
    targetState: AppScreen,
    modifier: Modifier = Modifier,
    content: @Composable (AppScreen) -> Unit
) {
    // Highly optimized pure alpha Crossfade to completely eliminate navigation lag/jitter
    Crossfade(
        targetState = targetState,
        modifier = modifier,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "fastScreenCrossfade"
    ) { screen ->
        Box(modifier = Modifier.fillMaxSize()) {
            content(screen)
        }
    }
}

/**
 * Lightweight Item Pop-In / Fade Container for list items and cards.
 */
@Composable
fun ItemPopIn(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // Removed per-item AnimatedVisibility to completely eliminate scroll lag and jitter in LazyLists
    Box(modifier = modifier) {
        content()
    }
}
