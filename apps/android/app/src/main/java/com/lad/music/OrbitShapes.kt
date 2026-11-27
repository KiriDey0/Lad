// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun ladControlShape(classicRadius: Dp): RoundedCornerShape =
    if (LocalOrbitDesign.current) CircleShape else RoundedCornerShape(classicRadius)

@Composable
internal fun ladPanelShape(classicRadius: Dp): RoundedCornerShape =
    RoundedCornerShape(if (LocalOrbitDesign.current) 26.dp else classicRadius)

@Composable
internal fun ChoiceChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        shape = if (LocalOrbitDesign.current) CircleShape else FilterChipDefaults.shape,
    )
}
