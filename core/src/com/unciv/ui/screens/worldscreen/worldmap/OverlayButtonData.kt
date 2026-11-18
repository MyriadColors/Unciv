package com.unciv.ui.screens.worldscreen.worldmap

import com.unciv.view.hasMovement
import com.unciv.view.isPreparingParadrop
import com.unciv.view.GameView
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.logic.automation.unit.UnitAutomation
import com.unciv.logic.map.GridCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.UncivSound
import com.unciv.models.translations.tr
import com.unciv.ui.audio.SoundPlayer
import com.unciv.ui.components.extensions.*
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.UnitIconGroup
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.espionage.EspionageScreen
import com.unciv.view.ForeignCityView
import com.unciv.view.MapUnitView
import com.unciv.view.SpyView
import com.unciv.view.TileView

/** Interface for creating floating "action" buttons on tiles */
interface OverlayButtonData {
    fun createButton(worldMapHolder: WorldMapHolder): Actor
}

const val BUTTON_SIZE = 60f
const val SMALLER_CIRCLE_SIZES = 25f

class MoveHereOverlayButtonData(
    val unitToTurnsToDestination: HashMap<MapUnitView, Int>,
    val tileView: TileView
) : OverlayButtonData {
    override fun createButton(worldMapHolder: WorldMapHolder): Actor {
        return getMoveHereButton(worldMapHolder)
    }

    private fun getMoveHereButton(worldMapHolder: WorldMapHolder): Group {
        val isParadrop = unitToTurnsToDestination.keys.all { it.isPreparingParadrop() }
        val image = if (isParadrop)
            ImageGetter.getUnitActionPortrait("Paradrop", BUTTON_SIZE / 2)
        else ImageGetter.getStatIcon("Movement")
            .apply { color = ImageGetter.CHARCOAL; width = BUTTON_SIZE / 2; height = BUTTON_SIZE / 2 }
        val moveHereButton = image
            .surroundWithCircle(BUTTON_SIZE - 2, false)
            .surroundWithCircle(BUTTON_SIZE, false, ImageGetter.CHARCOAL)

        if (!isParadrop) {
            val numberCircle = unitToTurnsToDestination.values.maxOrNull()!!.tr().toLabel(fontSize = 14)
                .apply { setAlignment(Align.center) }
                .surroundWithCircle(SMALLER_CIRCLE_SIZES - 2, color = BaseScreen.skinStrings.skinConfig.baseColor.darken(0.3f))
                .surroundWithCircle(SMALLER_CIRCLE_SIZES, false)
            moveHereButton.addActor(numberCircle)
        }

        val firstUnit = unitToTurnsToDestination.keys.first()
        val unitIcon = if (unitToTurnsToDestination.size == 1) UnitIconGroup(firstUnit, SMALLER_CIRCLE_SIZES)
        else unitToTurnsToDestination.size.tr().toLabel(fontColor = firstUnit.civ().getInnerColor()).apply { setAlignment(
            Align.center) }
            .surroundWithCircle(SMALLER_CIRCLE_SIZES).apply { circle.color = firstUnit.civ().getOuterColor() }
        unitIcon.y = BUTTON_SIZE - unitIcon.height
        moveHereButton.addActor(unitIcon)

        val unitsThatCanMove = unitToTurnsToDestination.keys.filter { it.hasMovement() }
        if (unitsThatCanMove.isEmpty()) moveHereButton.color.a = 0.5f
        else {
            moveHereButton.onActivation(UncivSound.Silent) {
                worldMapHolder.moveUnitToTargetTile(unitsThatCanMove, tileView)
            }
            moveHereButton.keyShortcuts.add(KeyCharAndCode.TAB)
        }
        return moveHereButton
    }
}

// Contains the data required to draw a "swap with" button
class SwapWithOverlayButtonData(val unitView: MapUnitView, val tileView: TileView) : OverlayButtonData {
    override fun createButton(worldMapHolder: WorldMapHolder): Actor {
        return getSwapWithButton(worldMapHolder)
    }

    fun getSwapWithButton(worldMapHolder: WorldMapHolder): Group {
        val swapWithButton = Group()
        swapWithButton.setSize(BUTTON_SIZE, BUTTON_SIZE)
        swapWithButton.addActor(ImageGetter.getCircle(size = BUTTON_SIZE))
        swapWithButton.addActor(
            ImageGetter.getImage("OtherIcons/Swap").apply {
                color = ImageGetter.CHARCOAL
                setSize(BUTTON_SIZE / 2)
                center(swapWithButton)
            }
        )

        val unitIcon = UnitIconGroup(unitView, SMALLER_CIRCLE_SIZES)
        unitIcon.y = BUTTON_SIZE - unitIcon.height
        swapWithButton.addActor(unitIcon)

        swapWithButton.onActivation(UncivSound.Silent) {
            worldMapHolder.swapMoveUnitToTargetTile(unitView, tileView)
        }
        swapWithButton.keyShortcuts.add(KeyCharAndCode.TAB)

        return swapWithButton
    }
}

// Contains the data required to draw a "connect road" button
class ConnectRoadOverlayButtonData(val unitView: MapUnitView, val tileView: TileView) : OverlayButtonData {
    override fun createButton(worldMapHolder: WorldMapHolder): Actor {
        return getConnectRoadButton(worldMapHolder)
    }

    private fun getConnectRoadButton(worldMapHolder: WorldMapHolder): Group {
        val connectRoadButton = Group()
        connectRoadButton.setSize(BUTTON_SIZE, BUTTON_SIZE)
        connectRoadButton.addActor(ImageGetter.getUnitActionPortrait("RoadConnection", BUTTON_SIZE * 0.8f).apply {
            center(connectRoadButton)
        }
        )

        val unitIcon = UnitIconGroup(unitView, SMALLER_CIRCLE_SIZES)
        unitIcon.y = BUTTON_SIZE - unitIcon.height
        connectRoadButton.addActor(unitIcon)

        connectRoadButton.onActivation(UncivSound.Silent) {
            connectRoadToTargetTile(worldMapHolder, unitView.getUnit(), tileView.position())
        }
        connectRoadButton.keyShortcuts.add(KeyboardBinding.ConnectRoad)

        return connectRoadButton
    }

    private fun connectRoadToTargetTile(worldMapHolder: WorldMapHolder, selectedUnit: MapUnit, targetTilePosition: GridCoord) {
        selectedUnit.automatedRoadConnectionDestination = targetTilePosition
        selectedUnit.action = Constants.connectRoadAction
        selectedUnit.automated = true
        UnitAutomation.automateUnitMoves(GameView(selectedUnit.civ.gameInfo, selectedUnit.civ).getMapUnitView(selectedUnit))

        SoundPlayer.play(UncivSound("wagon"))

        worldMapHolder.worldScreen.shouldUpdate = true
        worldMapHolder.removeUnitActionOverlay()

        // Make highlighting go away
        worldMapHolder.worldScreen.bottomUnitTable.selectedUnitIsConnectingRoad = false
    }
}

// Contains the data required to draw a "move spy" button
class MoveSpyOverlayButtonData(val spyView: SpyView, val cityView: ForeignCityView?) : OverlayButtonData {
    override fun createButton(worldMapHolder: WorldMapHolder): Actor {
        return getMoveSpyButton(worldMapHolder)
    }

    private fun getMoveSpyButton(worldMapHolder: WorldMapHolder): Group {
        val spyActionButton = Group()
        spyActionButton.setSize(BUTTON_SIZE, BUTTON_SIZE)
        spyActionButton.addActor(ImageGetter.getCircle(size = BUTTON_SIZE))
        if (cityView != null) {
            spyActionButton.addActor(
                ImageGetter.getStatIcon("Movement").apply {
                    name = "Button"
                    color = ImageGetter.CHARCOAL
                    setSize(BUTTON_SIZE / 2)
                    center(spyActionButton)
                }
            )
            spyActionButton.addActor(
                ImageGetter.getImage("OtherIcons/Spy_White")
                    .surroundWithCircle(SMALLER_CIRCLE_SIZES, color = ImageGetter.CHARCOAL).apply {
                        setPosition(2f, spyActionButton.height - 2f, Align.topLeft)
                    }
            )
        } else {
            spyActionButton.addActor(
                ImageGetter.getImage("OtherIcons/Close").apply {
                    name = "Button"
                    color = Color.RED
                    setSize(BUTTON_SIZE / 2)
                    center(spyActionButton)
                }
            )
        }

        val worldScreen = worldMapHolder.worldScreen
        spyActionButton.onActivation(UncivSound.Silent) {
            if (cityView != null) {
                spyView.tryMoveTo(cityView)
                worldScreen.game.pushScreen { EspionageScreen(worldScreen.selectedCiv, worldScreen) }
            } else {
                worldScreen.game.pushScreen { EspionageScreen(worldScreen.selectedCiv, worldScreen) }
                worldScreen.bottomUnitTable.selectSpy(null)
            }
            worldMapHolder.removeUnitActionOverlay()
            worldMapHolder.selectedTile = null
            worldScreen.shouldUpdate = true
            worldScreen.bottomUnitTable.selectSpy(null)
        }
        spyActionButton.keyShortcuts.add(KeyCharAndCode.TAB)
        spyActionButton.keyShortcuts.add(KeyCharAndCode.RETURN)

        return spyActionButton
    }
}
