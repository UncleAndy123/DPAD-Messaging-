package com.dpad.messaging.util

import androidx.compose.foundation.Indication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Handles D-pad OK / Center button (KeyEvent.KEYCODE_DPAD_CENTER or Enter)
 * as a click action on a focusable composable.
 */
fun Modifier.dpadClickable(onClick: () -> Unit): Modifier = this.onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyUp &&
        (event.key == Key.DirectionCenter || event.key == Key.Enter)
    ) {
        onClick()
        true
    } else {
        false
    }
}

/**
 * A combined modifier that handles D-pad focus, high-contrast visuals, and clicking.
 * This completely replaces the manual `onFocusChanged` + `background` + `border` logic
 * scattered across the UI files, ensuring instantaneous feedback and less boilerplate.
 */
@Composable
fun Modifier.dpadFocusableItem(
    onClick: () -> Unit,
    shape: Shape = RoundedCornerShape(8.dp),
    borderWidth: Dp = 3.dp,
    padding: Dp = 0.dp
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    return this
        .padding(padding)
        .clip(shape)
        .border(
            width = if (isFocused) borderWidth else 0.dp,
            color = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
            shape = shape
        )
        .background(
            if (isFocused) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            shape = shape
        )
        // Standard Compose clickable handles the interactionSource focus states instantly
        // and also provides the ripple effect if desired.
        .clickable(
            interactionSource = interactionSource,
            indication = androidx.compose.material.ripple.rememberRipple(),
            onClick = onClick
        )
        // We still attach our key listener to catch Center/Enter explicitly on TV remotes
        // just in case standard clickable drops it on some leanback devices.
        .dpadClickable(onClick)
}
