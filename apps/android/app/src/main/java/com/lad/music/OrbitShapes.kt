// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun ladControlShape(classicRadius: Dp): RoundedCornerShape =
    if (LocalOrbitDesign.current) CircleShape else RoundedCornerShape(classicRadius)

@Composable
internal fun ladPanelShape(classicRadius: Dp): RoundedCornerShape =
    RoundedCornerShape(if (LocalOrbitDesign.current) 26.dp else classicRadius)

@Composable
internal fun ChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    classicLabelSize: Int? = null,
) {
    if (!LocalOrbitDesign.current) {
        FilterChip(selected = selected, onClick = onClick, label = {
            if (classicLabelSize == null) Text(label)
            else Label(label, classicLabelSize, maxLines = 1)
        })
        return
    }
    val palette = LocalPalette.current
    Surface(
        selected = selected,
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) palette.tonal else palette.raised,
        border = if (selected) null else BorderStroke(1.dp, palette.text.copy(alpha = .14f)),
    ) {
        Box(
            Modifier.heightIn(min = 36.dp).padding(horizontal = 14.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Label(label, 13, weight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
