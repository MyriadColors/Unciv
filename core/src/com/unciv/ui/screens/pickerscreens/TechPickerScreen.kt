package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.TechManager
import com.unciv.models.UncivSound
import com.unciv.models.ruleset.tech.Technology
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.objectdescriptions.TechnologyDescriptions
import com.unciv.ui.components.NonTransformGroup
import com.unciv.ui.components.extensions.colorFromRGB
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onRightClick
import com.unciv.ui.components.input.onDoubleClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ToastPopup
import com.unciv.utils.Concurrency
import yairm210.purity.annotations.Readonly
import kotlin.math.abs


class TechPickerScreen(
    internal val civInfo: Civilization,
    centerOnTech: Technology? = null,
) : PickerScreen() {

    private val freeTechPick: Boolean = civInfo.tech.freeTechs != 0
    private val ruleset = civInfo.gameInfo.ruleset
    private var techNameToButton = HashMap<String, TechButton>()
    private var selectedTech: Technology? = null
    private var civTech: TechManager = civInfo.tech
    private var tempTechsToResearch: ArrayList<String>
    private var lines = NonTransformGroup().apply { touchable = Touchable.disabled }
    private var orderIndicators = NonTransformGroup().apply { touchable = Touchable.disabled }
    private var eraLabels = ArrayList<Label>()
    private var hoveredTechName: String? = null
    private val edgeToActors = HashMap<Pair<String, String>, MutableList<com.badlogic.gdx.scenes.scene2d.Actor>>()
    private val techGridCoords = HashMap<String, Pair<Int, Int>>()
    private var cellTables: Array<Array<Table>> = emptyArray()

    /** We need this to be a separate table, and NOT the topTable, because *inhales*
     * When call setConnectingLines we need to pack() the table so that the lines will align correctly, BUT
     *  this causes the table to be SMALLER THAN THE SCREEN for small tech trees from mods,
     *  meaning the tech tree is in a crumpled heap at the lower-left corner of the screen
     * Having this be a separate table allows us to leave the TopTable as is (that is: auto-width to fit the scrollPane)
     *  leaving us the juicy small tech tree right in the center.
     */
    private val techTable = object : Table(){
        override fun draw(batch: Batch?, parentAlpha: Float) {
            cullingArea = topTable.cullingArea
            super.draw(batch, parentAlpha)
        }
    }

    // All these are to counter performance problems when updating buttons for all techs.
    private var researchableTechs = ruleset.technologies.keys
            .filter { civTech.canBeResearched(it) }.toHashSet()

    private val currentTechColor = skinStrings.getUIColor("TechPickerScreen/CurrentTechColor", colorFromRGB(72, 147, 175))
    private val researchedTechColor = skinStrings.getUIColor("TechPickerScreen/ResearchedTechColor", colorFromRGB(255, 215, 0))
    private val researchableTechColor = skinStrings.getUIColor("TechPickerScreen/ResearchableTechColor", colorFromRGB(28, 170, 0))
    private val queuedTechColor = skinStrings.getUIColor("TechPickerScreen/QueuedTechColor", colorFromRGB(7*2, 46*2, 43*2))
    private val researchedFutureTechColor = skinStrings.getUIColor("TechPickerScreen/ResearchedFutureTechColor", colorFromRGB(127, 50, 0))

    private val turnsToTech = ruleset.technologies.values.associateBy({ it.name }, { civTech.turnsToTech(it.name) })

    init {
        setDefaultCloseAction()
        scrollPane.setOverscroll(false, false)

        descriptionLabel.onActivation {
            if (selectedTech != null)
                openCivilopedia(selectedTech!!.makeLink())
        }
        descriptionLabel.keyShortcuts.add(KeyboardBinding.Civilopedia)

        tempTechsToResearch = ArrayList(civTech.techsToResearch)

        createTechTable()
        techTable.addActor(lines)
        techTable.addActor(orderIndicators)
        setButtonsInfo()
        topTable.add(techTable)
        techTable.background = skinStrings.getUiBackground("TechPickerScreen/Background", tintColor = skinStrings.skinConfig.clearColor)
        pickerPane.bottomTable.background = skinStrings.getUiBackground("TechPickerScreen/BottomTable", tintColor = skinStrings.skinConfig.clearColor)

        rightSideButton.setText(if (freeTechPick) "Pick a free tech".tr() else "Pick a tech".tr())
        rightSideButton.onClick(UncivSound.Paper) { tryExit() }

        // per default show current/recent technology,
        // and possibly select it to show description,
        // which is very helpful when just discovered and clicking the notification
        val tech = centerOnTech ?: civInfo.tech.currentTechnology()
        if (tech != null) {
            // select only if there it doesn't mess up tempTechsToResearch
            if (civInfo.tech.isResearched(tech.name) || civInfo.tech.techsToResearch.size <= 1)
                selectTechnology(tech, queue = false, center = true)
            else centerOnTechnology(tech)
        } else {
            // center on any possible technology which is ready for the research right now
            val firstAvailable = researchableTechs.firstOrNull()
            val firstAvailableTech = ruleset.technologies[firstAvailable]
            if (firstAvailableTech != null)
                centerOnTechnology(firstAvailableTech)
        }
    }

    override fun getCivilopediaRuleset() = ruleset


    private fun tryExit() {
        if (freeTechPick) {
            val freeTech = selectedTech!!.name
            // More evil people fast-clicking to cheat - #4977
            if (!researchableTechs.contains(freeTech)) return
            civTech.getFreeTechnology(selectedTech!!.name)
        }
        else civTech.techsToResearch = tempTechsToResearch

        civTech.updateResearchProgress()

        game.settings.addCompletedTutorialTask("Pick technology")

        game.popScreen()
    }

    private fun createTechTable() {

        for (label in eraLabels) label.remove()
        eraLabels.clear()

        // Computed once for all techs instead of per-TechButton - see #15641
        val techIconsIndex = TechnologyDescriptions.buildTechIconsIndex(ruleset, civInfo)

        val allTechs = ruleset.technologies.values
        if (allTechs.isEmpty()) return
        val columns = allTechs.maxOf { it.column!!.columnNumber } + 1
        val rows = allTechs.maxOf { it.row } + 1
        val techMatrix = Array<Array<Technology?>>(columns) { arrayOfNulls(rows) } // Divided into columns, then rows

        for (technology in allTechs) {
            techMatrix[technology.column!!.columnNumber][technology.row - 1] = technology
        }

        val erasNamesToColumns = LinkedHashMap<String, ArrayList<Int>>()
        for (tech in allTechs) {
            val era = tech.era()
            if (!erasNamesToColumns.containsKey(era)) erasNamesToColumns[era] = ArrayList()
            val columnNumber = tech.column!!.columnNumber
            if (!erasNamesToColumns[era]!!.contains(columnNumber)) erasNamesToColumns[era]!!.add(columnNumber)
        }
        for ((era, eraColumns) in erasNamesToColumns) {
            val columnSpan = eraColumns.size
            val color = when {
                civTech.era.name == era -> queuedTechColor
                ruleset.eras[era]!!.eraNumber < civTech.era.eraNumber -> colorFromRGB(255, 175, 0)
                else -> ImageGetter.CHARCOAL.cpy()
            }

            val table1 = Table().pad(1f)
            val table2 = Table()

            table1.background = skinStrings.getUiBackground("General/Border", tintColor = Color.WHITE)
            table2.background = skinStrings.getUiBackground("General/Border", tintColor = color)

            val label = era.toLabel().apply {
                setAlignment(Align.center)
                if (ruleset.eras[era]!!.eraNumber < civTech.era.eraNumber)
                    this.color = colorFromRGB(120, 46, 16) }

            eraLabels.add(label)

            table2.add(label).growX()
            table1.add(table2).growX()

            techTable.add(table1).fill().colspan(columnSpan)
        }

        cellTables = Array(techMatrix.size) { Array(rows) { Table() } }

        for (rowIndex in 0 until rows) {

            techTable.row()

            for (columnIndex in techMatrix.indices) {
                val tech = techMatrix[columnIndex][rowIndex]

                val table = Table().pad(2f).padTop(14f).padBottom(14f).padRight(60f).padLeft(20f)
                cellTables[columnIndex][rowIndex] = table
                if (rowIndex == 0)
                    table.padTop(14f)

                if (erasNamesToColumns[civTech.era.name]?.contains(columnIndex) == true)
                    table.background = skinStrings.getUiBackground("TechPickerScreen/Background", tintColor = queuedTechColor.darken(0.5f))

                if (tech == null) {
                    techTable.add(table).fill()
                } else {
                    val techButton = TechButton(tech.name, civTech, false, techIconsIndex)
                    table.add(techButton)
                    techNameToButton[tech.name] = techButton
                    techGridCoords[tech.name] = columnIndex to rowIndex
                    techButton.onClick { selectTechnology(tech, queue = false, center = false) }
                    techButton.onRightClick { selectTechnology(tech, queue = true, center = false) }
                    techButton.onDoubleClick(UncivSound.Paper) { tryExit() }
                    techButton.addListener(object : com.badlogic.gdx.scenes.scene2d.InputListener() {
                        override fun enter(event: com.badlogic.gdx.scenes.scene2d.InputEvent?, x: Float, y: Float, pointer: Int, fromActor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                            if (pointer == -1) {
                                hoveredTechName = tech.name
                                updateLineFocusHighlighting()
                            }
                        }
                        override fun exit(event: com.badlogic.gdx.scenes.scene2d.InputEvent?, x: Float, y: Float, pointer: Int, toActor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                            if (pointer == -1 && hoveredTechName == tech.name) {
                                hoveredTechName = null
                                updateLineFocusHighlighting()
                            }
                        }
                    })
                    techTable.add(table).fillX()
                }
            }
        }
    }

    private fun setButtonsInfo() {
        for ((techName, techButton) in techNameToButton) {
            val isResearched = civTech.isResearched(techName)
            techButton.setButtonColor(when {
                isResearched && techName != Constants.futureTech -> researchedTechColor
                isResearched -> researchedFutureTechColor
                // if we're here to pick a free tech, show the current tech like the rest of the researchables so it'll be obvious what we can pick
                tempTechsToResearch.firstOrNull() == techName && !freeTechPick -> currentTechColor
                researchableTechs.contains(techName) -> researchableTechColor
                tempTechsToResearch.contains(techName) -> queuedTechColor
                else -> ImageGetter.CHARCOAL.cpy()
            })

            if (isResearched && techName != Constants.futureTech) {
                techButton.text.color = colorFromRGB(154, 98, 16)
            }

            if (!isResearched || techName == Constants.futureTech) {
                techButton.turns.setText(turnsToTech[techName] + "${Fonts.turn}".tr())
            }

            techButton.text.setText(techName.tr(true))
        }

        addConnectingLines()

        addOrderIndicators()
    }

    private fun addConnectingLines() {
        techTable.pack() // required for the table to have the button positions set, so topTable.stageToLocalCoordinates will be correct
        scrollPane.updateVisualScroll()

        lines.clear()
        edgeToActors.clear()
        addEraDividerLines()

        val cols = cellTables.size
        val numRows = if (cols > 0) cellTables[0].size else 0
        val router = TechLineRouter(cols, numRows) { col, row ->
            cellTables[col][row].children.any { it is TechButton }
        }

        data class EdgeSpec(val prereq: String, val tech: Technology, val isOr: Boolean)
        val edgeSpecs = ArrayList<EdgeSpec>()
        val routedInputs = ArrayList<TechLineRouter.RoutedEdge>()

        for (tech in ruleset.technologies.values) {
            if (techNameToButton[tech.name] == null) {
                ToastPopup("Tech ${tech.name} appears to be missing - perhaps two techs have the same row & column", this)
                continue
            }
            val to = techGridCoords[tech.name] ?: continue
            for (prerequisite in tech.prerequisites) {
                if (techNameToButton[prerequisite] == null) {
                    ToastPopup("Tech $prerequisite. prerequisite of ${tech.name}, appears to be missing - perhaps two techs have the same row & column", this)
                    continue
                }
                val from = techGridCoords[prerequisite] ?: continue
                edgeSpecs.add(EdgeSpec(prerequisite, tech, isOr = false))
                routedInputs.add(router.route("${prerequisite}->${tech.name}", prerequisite, from.first, from.second, to.first, to.second))
            }
            for (prerequisite in getOrPrerequisites(tech)) {
                val from = techGridCoords[prerequisite] ?: continue
                edgeSpecs.add(EdgeSpec(prerequisite, tech, isOr = true))
                routedInputs.add(router.route("${prerequisite}->${tech.name}", prerequisite, from.first, from.second, to.first, to.second))
            }
        }

        val lanedRoutes = router.assignLanes(routedInputs)
        for (i in edgeSpecs.indices) {
            drawRoutedEdge(edgeSpecs[i].tech, edgeSpecs[i].prereq, edgeSpecs[i].isOr, lanedRoutes[i])
        }

        lines.setSize(techTable.width, techTable.height)
        updateLineFocusHighlighting()
    }

    private fun addEraDividerLines() {
        for (eraLabel in eraLabels) {
            val coords = Vector2(0f, 0f)
            eraLabel.localToStageCoordinates(coords)
            techTable.stageToLocalCoordinates(coords)
            val line = ImageGetter.getLine(coords.x - 1f, coords.y, coords.x - 1f, coords.y - 1000f, 1f)
            line.color = Color.GRAY.cpy().apply { a = 0.6f }
            line.toBack()
            lines.addActor(line)
        }
    }

    private fun getOrPrerequisites(tech: Technology): List<String> =
        tech.uniqueObjects
            .filter { it.type == com.unciv.models.ruleset.unique.UniqueType.Unavailable }
            .flatMap { it.modifiers }
            .filter { it.type == com.unciv.models.ruleset.unique.UniqueType.ConditionalNoTech }
            .map { it.params[0] }
            .distinct()
            .filter { it !in tech.prerequisites && techNameToButton[it] != null }

    private fun gutterX(gx: Int): Float {
        if (cellTables.isEmpty()) return 0f
        return when {
            gx <= 0 -> {
                val cell = cellTables[0][0]
                val v = Vector2(0f, 0f)
                cell.localToStageCoordinates(v)
                techTable.stageToLocalCoordinates(v)
                v.x + 8f
            }
            gx >= cellTables.size -> {
                val cell = cellTables.last()[0]
                val v = Vector2(cell.width, 0f)
                cell.localToStageCoordinates(v)
                techTable.stageToLocalCoordinates(v)
                v.x - 8f
            }
            else -> {
                val leftCell = cellTables[gx - 1][0]
                val rightCell = cellTables[gx][0]
                val vL = Vector2(leftCell.width - 60f, 0f)
                val vR = Vector2(20f, 0f)
                leftCell.localToStageCoordinates(vL)
                rightCell.localToStageCoordinates(vR)
                techTable.stageToLocalCoordinates(vL)
                techTable.stageToLocalCoordinates(vR)
                (vL.x + vR.x) / 2f
            }
        }
    }

    private fun latticeY(gy: Int): Float {
        val numRows = cellTables[0].size
        return if (gy % 2 == 1) {
            val row = ((gy - 1) / 2).coerceIn(0, numRows - 1)
            val cell = cellTables[0][row]
            val v = Vector2(0f, cell.height / 2f)
            cell.localToStageCoordinates(v)
            techTable.stageToLocalCoordinates(v)
            v.y
        } else {
            val upperRow = (gy / 2 - 1).coerceIn(0, numRows - 1)
            val lowerRow = (gy / 2).coerceIn(0, numRows - 1)
            val vU = Vector2(0f, 0f)
            cellTables[0][upperRow].localToStageCoordinates(vU)
            techTable.stageToLocalCoordinates(vU)
            if (gy == 0) return vU.y + cellTables[0][0].height - 6f
            if (gy >= numRows * 2) return vU.y + 6f
            val vL = Vector2(0f, cellTables[0][lowerRow].height)
            cellTables[0][lowerRow].localToStageCoordinates(vL)
            techTable.stageToLocalCoordinates(vL)
            (vU.y + vL.y) / 2f
        }
    }

    private fun buildEdgeWaypoints(
        startPt: Vector2,
        endPt: Vector2,
        routed: TechLineRouter.RoutedEdge
    ): ArrayList<Vector2> {
        val laneSpacing = 4f
        val waypoints = ArrayList<Vector2>()
        waypoints.add(startPt)
        for (segIdx in routed.segments.indices) {
            val seg = routed.segments[segIdx]
            val offset = seg.lane * laneSpacing
            val x1 = gutterX(seg.start.gx) + if (seg.isVertical) offset else 0f
            val y1 = latticeY(seg.start.gy) + if (seg.isHorizontal) offset else 0f
            val x2 = gutterX(seg.end.gx) + if (seg.isVertical) offset else 0f
            val y2 = latticeY(seg.end.gy) + if (seg.isHorizontal) offset else 0f
            if (segIdx == 0) waypoints.add(Vector2(x1, startPt.y))
            waypoints.add(Vector2(x1, y1))
            waypoints.add(Vector2(x2, y2))
            if (segIdx == routed.segments.lastIndex) waypoints.add(Vector2(x2, endPt.y))
        }
        waypoints.add(endPt)
        return waypoints
    }

    private fun drawRoutedEdge(
        tech: Technology,
        prerequisite: String,
        isOrPrerequisite: Boolean,
        routed: TechLineRouter.RoutedEdge
    ) {
        val techButton = techNameToButton[tech.name] ?: return
        val prereqButton = techNameToButton[prerequisite] ?: return

        val startPt = Vector2(prereqButton.width, prereqButton.height / 2f)
        prereqButton.localToStageCoordinates(startPt)
        techTable.stageToLocalCoordinates(startPt)

        val endPt = Vector2(0f, techButton.height / 2f)
        techButton.localToStageCoordinates(endPt)
        techTable.stageToLocalCoordinates(endPt)

        val defaultColor = if (isOrPrerequisite) Color(1f, 0.75f, 0.2f, 0.85f) else Color.WHITE.cpy()
        val lineColor = when {
            civTech.isResearched(tech.name) && !tech.isContinuallyResearchable() -> defaultColor
            civTech.isResearched(prerequisite) -> researchableTechColor
            tempTechsToResearch.contains(tech.name) -> currentTechColor
            else -> defaultColor
        }
        val lineSize = if (tempTechsToResearch.contains(tech.name) && !civTech.isResearched(prerequisite)) 4f else 2f
        val waypoints = buildEdgeWaypoints(startPt, endPt, routed)
        val actors = edgeToActors.getOrPut(prerequisite to tech.name) { ArrayList() }
        for (i in 0 until waypoints.size - 1) {
            val a = waypoints[i]
            val b = waypoints[i + 1]
            if (a.dst2(b) < 0.25f) continue
            val segLine = ImageGetter.getLine(a.x, a.y, b.x, b.y, lineSize)
            segLine.color = lineColor.cpy()
            lines.addActor(segLine)
            actors.add(segLine)
        }
    }

    private fun updateLineFocusHighlighting() {
        val focus = hoveredTechName ?: selectedTech?.name
        for ((edge, actors) in edgeToActors) {
            val isFocused = focus == null || edge.first == focus || edge.second == focus
            for (actor in actors) {
                actor.color.a = if (isFocused) 1f else 0.25f
                if (focus != null && isFocused) actor.toFront()
            }
        }
    }

    private fun addTechPrerequisiteLines(tech: Technology, techButton: Table) {
        for (prerequisite in tech.prerequisites) {
            addPrerequisiteLine(tech, techButton, prerequisite, isOrPrerequisite = false)
        }

        val orPrerequisites = tech.uniqueObjects
            .filter { it.type == com.unciv.models.ruleset.unique.UniqueType.Unavailable }
            .flatMap { it.modifiers }
            .filter { it.type == com.unciv.models.ruleset.unique.UniqueType.ConditionalNoTech }
            .map { it.params[0] }
            .distinct()
            .filter { it !in tech.prerequisites }

        for (prerequisite in orPrerequisites) {
            addPrerequisiteLine(tech, techButton, prerequisite, isOrPrerequisite = true)
        }
    }

    private fun addPrerequisiteLine(
        tech: Technology,
        techButton: Table,
        prerequisite: String,
        isOrPrerequisite: Boolean
    ) {
        val prerequisiteButton = techNameToButton[prerequisite]
        if (prerequisiteButton == null) {
            ToastPopup("Tech $prerequisite. prerequisite of ${tech.name}, appears to be missing - perhaps two techs have the same row & column", this)
            return
        }
        val techButtonCoords = Vector2(0f, techButton.height / 2)
        techButton.localToStageCoordinates(techButtonCoords)
        techTable.stageToLocalCoordinates(techButtonCoords)

        val prerequisiteCoords = Vector2(prerequisiteButton.width, prerequisiteButton.height / 2)
        prerequisiteButton.localToStageCoordinates(prerequisiteCoords)
        techTable.stageToLocalCoordinates(prerequisiteCoords)

        val defaultColor = if (isOrPrerequisite) Color(1f, 0.75f, 0.2f, 0.85f) else Color.WHITE.cpy()
        val lineColor = when {
            civTech.isResearched(tech.name) && !tech.isContinuallyResearchable() -> defaultColor
            civTech.isResearched(prerequisite) -> researchableTechColor
            tempTechsToResearch.contains(tech.name) -> currentTechColor
            else -> defaultColor
        }

        val lineSize = when {
            tempTechsToResearch.contains(tech.name) && !civTech.isResearched(prerequisite) -> 4f
            else -> 2f
        }

        if (techButtonCoords.y != prerequisiteCoords.y) {
            addSteppedConnectingLine(techButtonCoords, prerequisiteCoords, lineColor, lineSize)
        } else if (hasHorizontalObstacle(prerequisiteCoords.x, techButtonCoords.x, prerequisiteCoords.y, techButton, prerequisiteButton)) {
            val detourY = prerequisiteCoords.y + techButton.height / 2 + 8f
            val midX = (prerequisiteCoords.x + techButtonCoords.x) / 2f
            addSteppedConnectingLine(Vector2(midX, detourY), prerequisiteCoords, lineColor, lineSize)
            addSteppedConnectingLine(techButtonCoords, Vector2(midX, detourY), lineColor, lineSize)
        } else {
            val line = ImageGetter.getWhiteDot().apply {
                width = techButtonCoords.x - prerequisiteCoords.x
                height = lineSize
                x = prerequisiteCoords.x
                y = prerequisiteCoords.y - lineSize / 2
            }
            line.color = lineColor
            lines.addActor(line)
        }
    }

    private fun hasHorizontalObstacle(
        startX: Float,
        endX: Float,
        y: Float,
        techButton: Table,
        prerequisiteButton: Table
    ): Boolean {
        val minX = minOf(startX, endX)
        val maxX = maxOf(startX, endX)
        for (btn in techNameToButton.values) {
            if (btn === techButton || btn === prerequisiteButton) continue
            val topLeft = Vector2(0f, btn.height)
            val bottomRight = Vector2(btn.width, 0f)
            btn.localToStageCoordinates(topLeft)
            btn.localToStageCoordinates(bottomRight)
            techTable.stageToLocalCoordinates(topLeft)
            techTable.stageToLocalCoordinates(bottomRight)
            if (bottomRight.x > minX && topLeft.x < maxX && y in bottomRight.y..topLeft.y) {
                return true
            }
        }
        return false
    }

    private fun addSteppedConnectingLine(
        techButtonCoords: Vector2,
        prerequisiteCoords: Vector2,
        lineColor: Color,
        lineSize: Float
    ) {
        val r = 6f
        val deltaX = techButtonCoords.x - prerequisiteCoords.x
        val deltaY = techButtonCoords.y - prerequisiteCoords.y
        val halfLength = deltaX / 2f

        val line = ImageGetter.getWhiteDot().apply {
            width = halfLength - r - lineSize/2
            height = lineSize
            x = prerequisiteCoords.x
            y = prerequisiteCoords.y - lineSize / 2
        }
        val line1 = ImageGetter.getWhiteDot().apply {
            width = halfLength - r - lineSize/2
            height = lineSize
            x = techButtonCoords.x - width
            y = techButtonCoords.y - lineSize / 2
        }
        val line2 = ImageGetter.getWhiteDot().apply {
            width = lineSize
            height = abs(deltaY) - 2*r - lineSize
            x = techButtonCoords.x - halfLength - lineSize / 2
            y = techButtonCoords.y + (if (deltaY > 0f) -height-r-lineSize/2 else r+lineSize/2)
        }

        val line3: Image
        val line4: Image
        if (deltaY < 0) {
            /* -\ */ line3 = ImageGetter.getLine(line2.x+lineSize/2+0.3f, line2.y + line2.height-lineSize/2,line.x + line.width-lineSize/2, line.y+lineSize/2+0.3f, lineSize)
            /* \- */ line4 = ImageGetter.getLine(line2.x+lineSize/2-0.3f, line2.y+lineSize/2, line1.x+lineSize/2, line1.y+lineSize/2-0.3f, lineSize)
        } else {
            /* -/ */ line3 = ImageGetter.getLine(line2.x+lineSize/2+0.3f, line2.y+lineSize/2, line.x + line.width-lineSize/2, line.y+lineSize/2-0.3f, lineSize)
            /* /- */ line4 = ImageGetter.getLine(line2.x+lineSize/2-0.3f, line2.y + line2.height-lineSize/2, line1.x+lineSize/2, line1.y+lineSize/2+0.3f, lineSize)
        }

        line.color = lineColor
        line1.color = lineColor
        line2.color = lineColor
        line3.color = lineColor
        line4.color = lineColor

        lines.addActor(line)
        lines.addActor(line1)
        lines.addActor(line2)
        lines.addActor(line3)
        lines.addActor(line4)
    }

    private fun addOrderIndicators() {
        orderIndicators.clear()
        for ((techName, techButton) in techNameToButton) {
            val techButtonCoords = Vector2(0f, techButton.height / 2)
            techButton.localToStageCoordinates(techButtonCoords)
            techTable.stageToLocalCoordinates(techButtonCoords)
            if (tempTechsToResearch.contains(techName) && tempTechsToResearch.size > 1) {
                val index = tempTechsToResearch.indexOf(techName) + 1
                val orderIndicator = index.tr().toLabel(fontSize = 18)
                    .apply { setAlignment(Align.center) }
                    .surroundWithCircle(28f, color = skinStrings.skinConfig.baseColor)
                    .surroundWithCircle(30f,false)
                    .apply { setPosition(techButtonCoords.x - width, techButtonCoords.y - height / 2) }
                orderIndicators.addActor(orderIndicator)
            }
        }
        orderIndicators.toFront()
        orderIndicators.setSize(techTable.width, techTable.height)
    }

    private fun selectTechnology(tech: Technology?, queue: Boolean = false, center: Boolean = false, switchFromWorldScreen: Boolean = true) {

        val previousSelectedTech = selectedTech
        selectedTech = tech
        descriptionLabel.setText(tech?.getDescription(civInfo))

        if (!switchFromWorldScreen)
            return

        if (tech == null)
            return

        // center on technology
        if (center) centerOnTechnology(tech)

        if (freeTechPick) {
            selectTechnologyForFreeTech(tech)
            setButtonsInfo()
            return
        }

        if (civInfo.gameInfo.gameParameters.godMode && !civInfo.tech.isResearched(tech.name)
                && selectedTech == previousSelectedTech) {
            civInfo.tech.addTechnology(tech.name)
        }

        if (civTech.isResearched(tech.name) && !tech.isContinuallyResearchable()) {
            rightSideButton.setText("Pick a tech".tr())
            rightSideButton.disable()
            setButtonsInfo()
            return
        }

        if (!GUI.isAllowedChangeState()) {
            rightSideButton.disable()
            return
        }

        val pathToTech = civTech.getRequiredTechsToDestination(tech)
        for (requiredTech in pathToTech) {
            val unavailableUniques = requiredTech.uniqueObjects.filter {
                it.type == UniqueType.OnlyAvailable && !it.conditionalsApply(civInfo.state) ||
                    it.type == UniqueType.Unavailable && it.conditionalsApply(civInfo.state)
            }
            for (unique in unavailableUniques) {
                rightSideButton.setText(unique.getDisplayText().tr())
                rightSideButton.disable()
                return
            }
        }

        if (queue){
            for (pathTech in pathToTech) {
                if (pathTech.name !in tempTechsToResearch) {
                    tempTechsToResearch.add(pathTech.name)
                }
            }
        }else{
            tempTechsToResearch.clear()
            tempTechsToResearch.addAll(pathToTech.map { it.name })
        }

        if (tempTechsToResearch.any()) {
            val label = "Research [${tempTechsToResearch[0]}]".tr()
            val techProgression = getTechProgressLabel(tempTechsToResearch)
            pick("${label}\n${techProgression}")
        } else {
            rightSideButton.setText("Unavailable".tr())
            rightSideButton.disable()
        }
        setButtonsInfo()
    }

    @Readonly
    private fun getTechProgressLabel(techs: List<String>): String {
        val progress = techs.sumOf { tech -> civTech.researchOfTech(tech) } + civTech.getOverflowScience()
        val techCost = techs.sumOf { tech -> civInfo.tech.costOfTech(tech) }
        return "(${progress}/${techCost})"
    }

    private fun centerOnTechnology(tech: Technology) {
        Concurrency.runOnGLThread {
            techNameToButton[tech.name]?.parent?.let {
                scrollPane.scrollTo(it.x, it.y, it.width, it.height, true, true)
                scrollPane.updateVisualScroll()
            }
        }
    }

    private fun selectTechnologyForFreeTech(tech: Technology) {
        if (researchableTechs.contains(tech.name)) {
            val label = "Pick [${tech.name}] as free tech".tr()
            val techProgression = getTechProgressLabel(listOf(tech.name))
            pick("${label}\n${techProgression}")
        } else {
            rightSideButton.setText("Pick a free tech".tr())
            rightSideButton.disable()
        }
    }
}
