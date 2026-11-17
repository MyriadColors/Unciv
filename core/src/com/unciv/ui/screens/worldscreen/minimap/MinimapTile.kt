package com.unciv.ui.screens.worldscreen.minimap

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.utils.Align
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.GridMath
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.map.tile.TileHistory.TileHistoryState.CityCenterType
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.widgets.IconCircleGroup
import com.unciv.ui.images.ImageGetter
import com.unciv.utils.DebugUtils
import kotlin.math.PI
import kotlin.math.atan

class MinimapTile(val tile: Tile, tileSize: Float) {
    val image: Image = ImageGetter.getWhiteDot()
    private var cityCircleImage: IconCircleGroup<Image>? = null
    var owningCiv: Civilization? = null
    private var neighborToBorderImage = HashMap<Tile, Image>()
    val isUnrevealed get() = !image.isVisible

    init {
        val positionalVector = GridMath.grid2WorldCoords(tile.position)

        image.isVisible = false
        image.setSize(tileSize, tileSize)
        image.setPosition(
            positionalVector.x * tileSize,
            positionalVector.y * tileSize
        )
    }

    fun updateColor(isTileUnrevealed: Boolean, turn: Int? = null) {
        image.isVisible = DebugUtils.VISIBLE_MAP || !isTileUnrevealed
        if (!image.isVisible) return
        val isCityCenter =
                if (turn == null) tile.isCityCenter() else tile.history.getState(turn).cityCenterType != CityCenterType.None
        val owningCiv = if (turn == null) tile.getOwner() else getOwningCivFromHistory(tile, turn)
        image.color = when {
            isCityCenter && !tile.isWater -> owningCiv!!.nation.getInnerColor()
            owningCiv != null && !tile.isWater -> owningCiv.nation.getOuterColor()
            else -> tile.getBaseTerrain().getColor().lerp(Color.GRAY, 0.5f)
        }
    }

    class ActorChange(val removed: Set<Actor>, val added: Set<Actor>) {
        fun updateActorsIn(group: Group) {
            removed.forEach { group.removeActor(it) }
            added.forEach { group.addActor(it) }
        }
    }

    fun updateBorders(turn: Int? = null): ActorChange {
        val owningCiv = if (turn == null) tile.getOwner() else getOwningCivFromHistory(tile, turn)
        val imagesBefore = neighborToBorderImage.values.toSet()
        for (neighbor in tile.neighbors) {
            val relativeWorldPosition = tile.tileMap.getNeighborTilePositionAsWorldCoords(tile, neighbor)
            if (relativeWorldPosition.x != 0f && relativeWorldPosition.y != 0f) continue

            val neighborOwningCiv =
                    if (turn == null) neighbor.getOwner() else getOwningCivFromHistory(
                        neighbor,
                        turn
                    )
            val shouldHaveBorderDisplayed = owningCiv != null
                    && neighborOwningCiv != owningCiv
            if (!shouldHaveBorderDisplayed) {
                neighborToBorderImage.remove(neighbor)
                continue
            }
            if (neighbor in neighborToBorderImage) {
                neighborToBorderImage[neighbor]!!.color = owningCiv!!.nation.getInnerColor()
                continue
            }

            val borderImage = ImageGetter.getWhiteDot()

            // copied from tilegroup border logic

            val edgeLength = image.width

            borderImage.setSize(edgeLength, edgeLength / 5f)
            borderImage.setOrigin(Align.center)
            val centerX = image.x + image.width / 2
            borderImage.x = centerX - borderImage.width / 2
            val centerY = image.y + image.height / 2
            borderImage.y = centerY - borderImage.height / 2

            val angle = when {
                relativeWorldPosition.y > 0f -> 0f
                relativeWorldPosition.x > 0f -> -90f
                relativeWorldPosition.y < 0f -> 180f
                else -> 90f
            }

            borderImage.moveBy(
                relativeWorldPosition.x * edgeLength / 2f,
                relativeWorldPosition.y * edgeLength / 2f
            )
            borderImage.rotateBy(angle)
            borderImage.color = owningCiv!!.nation.getInnerColor()
            neighborToBorderImage[neighbor] = borderImage
        }
        val imagesAfter = neighborToBorderImage.values.toSet()
        return ActorChange(imagesBefore - imagesAfter, imagesAfter - imagesBefore)
    }

    fun updateCityCircle(turn: Int? = null): ActorChange {
        val prevCircle = cityCircleImage
        val owningCiv = if (turn == null) tile.getOwner() else getOwningCivFromHistory(tile, turn)
        val isCityCenter =
                if (turn == null) tile.isCityCenter() else tile.history.getState(turn).cityCenterType != CityCenterType.None

        if (owningCiv == null || !isCityCenter) {
            return ActorChange(
                if (prevCircle != null) setOf(prevCircle) else emptySet(),
                emptySet()
            )
        }

        val nation = owningCiv.nation
        val isCapital =
                if (turn == null)
                    tile.getCity()!!.isCapital()
                else
                    tile.history.getState(turn).cityCenterType ==
                            CityCenterType.Capital
        val nationIconSize = (if (isCapital && owningCiv.isMajorCiv) 1.667f else 1.25f) * image.width
        val cityCircle = ImageGetter.getCircle(nation.getInnerColor())
            .surroundWithCircle(nationIconSize, color = nation.getOuterColor())
        val hexCenterXPosition = image.x + image.width / 2
        cityCircle.x = hexCenterXPosition - nationIconSize / 2
        val hexCenterYPosition = image.y + image.height / 2
        cityCircle.y = hexCenterYPosition - nationIconSize / 2
        cityCircleImage = cityCircle

        return ActorChange(if (prevCircle != null) setOf(prevCircle) else emptySet(), setOf(cityCircle))
    }

    fun getOwningCivFromHistory(tile: Tile, turn: Int) : Civilization? {
        val owningCivName = tile.history.getState(turn).owningCivName
        return if (owningCivName == null) null else tile.tileMap.gameInfo.getCivilizationOrNull(owningCivName)
    }

}
