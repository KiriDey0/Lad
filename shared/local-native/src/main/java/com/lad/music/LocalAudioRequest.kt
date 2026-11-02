// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

/** The Station appends its own query parameters; they are not part of the file ID. */
internal fun localAudioRequestId(target: String, token: String): String? {
    if (token.isBlank()) return null
    val path = target.substringBefore('?')
    val prefix = "/$token/"
    if (!path.startsWith(prefix)) return null
    return path.removePrefix(prefix).takeIf { Regex("local-track-[a-f0-9]{32}").matches(it) }
}
