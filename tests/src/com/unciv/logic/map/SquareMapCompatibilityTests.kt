package com.unciv.logic.map

import org.junit.Assert
import org.junit.Test

class SquareMapCompatibilityTests {
    @Test
    fun rejectsLegacyHexMapParameters() {
        val legacyParams = MapParameters().apply {
            gridFormat = ""
            shape = "Hexagonal"
        }
        Assert.assertThrows(IllegalArgumentException::class.java) {
            legacyParams.assertSquareCompatible()
        }
    }

    @Test
    fun acceptsSquareMapParameters() {
        val params = MapParameters()
        params.assertSquareCompatible()
        Assert.assertEquals("square", params.gridFormat)
        Assert.assertEquals(MapShape.rectangular, params.shape)
    }
}
