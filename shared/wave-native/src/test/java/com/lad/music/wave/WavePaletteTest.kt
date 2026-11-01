// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music.wave

import org.junit.Assert.*
import org.junit.Test

class WavePaletteTest {
    @Test fun achromaticArtworkHasBrightVisibleFallback() {
        for(gray in listOf(0,40,100,170,235,255)) {
            val pixel=(255 shl 24) or (gray shl 16) or (gray shl 8) or gray
            val p=WavePalette.extract(IntArray(96*96) { pixel },96,96)
            assertTrue(p.achromatic);assertEquals(1.25f,p.lineGain)
            assertTrue(p.colors.all { WavePalette.luminance(it.map(Float::toDouble).toDoubleArray())>.65 })
        }
    }
    @Test fun coloredCentreSurvivesGrayBorderAndStaysVisible() {
        val pixels=IntArray(96*96) { 0xff5a5a5a.toInt() }
        for(y in 32 until 64)for(x in 32 until 64)pixels[y*96+x]=0xffbe231e.toInt()
        val p=WavePalette.extract(pixels,96,96)
        assertFalse(p.achromatic)
        assertTrue(p.colors[0][0]>p.colors[0][1]+35)
        assertTrue(p.colors.all { WavePalette.luminance(it.map(Float::toDouble).toDoubleArray())>=.475 })
    }
    @Test fun noiseAndTransparentColorsCannotSetThePalette() {
        val pixels=IntArray(96*96) { 0xff505050.toInt() };pixels[0]=0xffff0000.toInt()
        assertTrue(WavePalette.extract(pixels,96,96).achromatic)
        assertTrue(WavePalette.extract(IntArray(96*96) { 0x00ff0000 },96,96).achromatic)
    }
}
