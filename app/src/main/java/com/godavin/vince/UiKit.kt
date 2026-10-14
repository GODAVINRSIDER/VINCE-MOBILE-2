package com.godavin.vince

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v2.7 - one button style for every Settings card.
 *
 * Why: the old buttons had a 2 dp vertical padding and sat three-in-a-row with
 * no weights, so on a normal phone their labels wrapped onto two or three lines
 * and every button came out a different, squashed shape. These are all the same
 * height, equal width inside a row, rounded the same, with the label centred
 * (two lines at most, never clipped).
 */
private val KitShape = RoundedCornerShape(12.dp)
private val KitPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)

/** One button. [primary] = filled, otherwise outlined. */
@Composable
fun VButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    val m = modifier.height(48.dp)
    if (primary) {
        Button(onClick = onClick, modifier = m, enabled = enabled, shape = KitShape, contentPadding = KitPadding) {
            KitLabel(text)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = m, enabled = enabled, shape = KitShape, contentPadding = KitPadding) {
            KitLabel(text)
        }
    }
}

@Composable
private fun KitLabel(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center
    )
}

/** Description of one button for [ButtonGrid]. */
class BtnSpec(
    val text: String,
    val primary: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/**
 * Buttons in neat rows of [perRow] (default 2), equal widths, equal heights,
 * with an 8 dp gap. A last row with fewer buttons keeps the same button width.
 */
@Composable
fun ButtonGrid(vararg items: BtnSpec, perRow: Int = 2, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.toList().chunked(perRow).forEach { rowItems ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { b ->
                    Box(modifier = Modifier.weight(1f)) {
                        VButton(
                            text = b.text,
                            onClick = b.onClick,
                            modifier = Modifier.fillMaxWidth(),
                            primary = b.primary,
                            enabled = b.enabled
                        )
                    }
                }
                repeat(perRow - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}
