package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.civilization.Civilization
import com.unciv.models.UncivSound
import com.unciv.models.ruleset.Civic
import com.unciv.models.ruleset.CivicCategory
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.colorFromRGB
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.screens.basescreen.BaseScreen

class CivicPickerScreen(
    val viewingCiv: Civilization,
    val canChangeState: Boolean
) : PickerScreen() {

    private val pendingSelections = HashMap<String, String>()
    private var selectedCivicForDescription: Civic? = null

    init {
        viewingCiv.civics.initDefaults()
        pendingSelections.putAll(viewingCiv.civics.activeCivics)

        setDefaultCloseAction()

        rightSideButton.setText("Adopt Civics".tr())
        rightSideButton.onClick(UncivSound.Policy) {
            viewingCiv.civics.switchCivics(pendingSelections)
            game.popScreen()
        }

        rebuildLayout()
        updateSummaryAndButton()
    }

    private fun rebuildLayout() {
        topTable.clear()
        topTable.defaults().pad(10f).top()

        val categories = viewingCiv.gameInfo.ruleset.civicCategories.values
        for (category in categories) {
            topTable.add(buildCategoryColumn(category)).top()
        }
    }

    private fun buildCategoryColumn(category: CivicCategory): Table {
        val column = Table()
        column.defaults().pad(5f).fillX().minWidth(200f)

        val header = category.name.toLabel(fontSize = 20, alignment = com.badlogic.gdx.utils.Align.center)
        column.add(header).padBottom(8f).row()

        val activeName = viewingCiv.civics.activeCivics[category.name]
        val pendingName = pendingSelections[category.name]

        for (civic in category.civics) {
            val isAdopted = civic.name == activeName
            val isPending = civic.name == pendingName
            val canAdopt = viewingCiv.civics.canAdoptCivic(civic)

            val buttonText = if (isAdopted) "${civic.name} (Active)" else civic.name
            val button = buttonText.toTextButton()

            when {
                isPending -> button.color = colorFromRGB(37, 150, 190)
                isAdopted -> button.color = Color.GREEN
                !canAdopt -> button.color = Color.GRAY
            }

            button.onClick {
                selectedCivicForDescription = civic
                if (canChangeState && canAdopt) {
                    pendingSelections[category.name] = civic.name
                    rebuildLayout()
                }
                updateSummaryAndButton()
            }

            column.add(button).row()
        }

        return column
    }

    private fun updateSummaryAndButton() {
        val changedCount = pendingSelections.count { (cat, civicName) ->
            viewingCiv.civics.activeCivics[cat] != civicName
        }
        val anarchyTurns = viewingCiv.civics.getAnarchyTurnsForChanges(changedCount)
        val upkeep = viewingCiv.civics.getCivicUpkeep()

        val statusLine = if (viewingCiv.isInAnarchy()) {
            "In Anarchy (${viewingCiv.anarchyTurns} turns remaining)".tr()
        } else {
            "Current Upkeep: [$upkeep] Gold/turn | Pending Changes: [$changedCount] (Anarchy: [$anarchyTurns] turns)".tr()
        }

        val descText = selectedCivicForDescription?.getDescription() ?: "Select a civic to view its effects.".tr()
        descriptionLabel.setText("$statusLine\n\n$descText")

        if (canChangeState && !viewingCiv.isInAnarchy() && changedCount > 0) {
            rightSideButton.enable()
        } else {
            rightSideButton.disable()
        }
    }
}
