// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

fun queueEdgeSpeed(pointer: Float, height: Float, edge: Float, maximum: Float): Float {
    if (height <= 0 || edge <= 0) return 0f
    val zone = edge.coerceAtMost(height / 3f)
    return when {
        pointer < zone -> -maximum * ((zone - pointer) / zone).coerceIn(0f, 1f)
        pointer > height - zone -> maximum * ((pointer - height + zone) / zone).coerceIn(0f, 1f)
        else -> 0f
    }
}
