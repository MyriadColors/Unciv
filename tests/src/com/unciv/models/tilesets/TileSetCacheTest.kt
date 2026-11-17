package com.unciv.models.tilesets

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.models.metadata.GameSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class TileSetCacheTest {

    @Before
    fun setUp() {
        TileSetCache.clear()
        UncivGame.Current = UncivGame().apply { settings = GameSettings() }
    }

    @After
    fun tearDown() {
        TileSetCache.clear()
    }

    @Test
    fun `getCurrent returns configured tileset when present`() {
        val customTileSet = TileSet("CustomSet")
        TileSetCache["CustomSet"] = customTileSet
        UncivGame.Current.settings.tileSet = "CustomSet"

        assertSame(customTileSet, TileSetCache.getCurrent())
    }

    @Test
    fun `getCurrent falls back to defaultTileset when configured tileset is missing`() {
        val defaultTileSet = TileSet(Constants.defaultTileset)
        TileSetCache[Constants.defaultTileset] = defaultTileSet
        UncivGame.Current.settings.tileSet = "MissingTileSet"

        assertSame(defaultTileSet, TileSetCache.getCurrent())
    }

    @Test
    fun `getCurrent returns fallback TileSet without throwing when cache is empty`() {
        UncivGame.Current.settings.tileSet = "MissingTileSet"

        val result = TileSetCache.getCurrent()
        assertEquals("MissingTileSet", result.name)
        assertEquals(1f, result.config.tileScale)
    }
}
