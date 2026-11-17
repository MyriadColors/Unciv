package com.unciv.logic.civilization.managers

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Civic
import com.unciv.models.ruleset.unique.UniqueMap
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly

class CivicManager : IsPartOfGameInfoSerialization {
    @Transient
    lateinit var civInfo: Civilization

    @Transient
    internal val civicUniques = UniqueMap()

    /** Maps CivicCategory name -> active Civic name */
    var activeCivics = HashMap<String, String>()

    fun clone(): CivicManager {
        val toReturn = CivicManager()
        toReturn.activeCivics.putAll(activeCivics)
        return toReturn
    }

    fun setTransients(civInfo: Civilization) {
        this.civInfo = civInfo
        recalculateCivicUniques()
    }

    fun initDefaults() {
        val categories = civInfo.gameInfo.ruleset.civicCategories
        var changed = false
        for (category in categories.values) {
            if (category.name !in activeCivics) {
                val defaultName = category.defaultCivic.ifEmpty { category.civics.firstOrNull()?.name ?: "" }
                if (defaultName.isNotEmpty()) {
                    activeCivics[category.name] = defaultName
                    changed = true
                }
            }
        }
        if (changed) recalculateCivicUniques()
    }

    fun recalculateCivicUniques() {
        civicUniques.clear()
        val rulesetCivics = civInfo.gameInfo.ruleset.civics
        for (civicName in activeCivics.values) {
            val civic = rulesetCivics[civicName] ?: continue
            civicUniques.addUniques(civic.uniqueObjects)
        }
    }

    @Readonly
    fun getActiveCivic(categoryName: String): Civic? {
        val civicName = activeCivics[categoryName] ?: return null
        return civInfo.gameInfo.ruleset.civics[civicName]
    }

    @Readonly
    fun isCivicActive(civicName: String): Boolean = civicName in activeCivics.values

    @Readonly
    fun canAdoptCivic(civic: Civic): Boolean {
        if (civInfo.isInAnarchy()) return false
        val requiredTech = civic.requiredTech
        if (requiredTech != null && !civInfo.tech.isResearched(requiredTech)) return false
        return true
    }

    @Readonly
    fun getAnarchyTurnsForChanges(changedCount: Int): Int {
        if (changedCount <= 0) return 0
        if (civInfo.hasUnique(UniqueType.NoAnarchy)) return 0
        if (civInfo.goldenAges.isGoldenAge()) return 0
        return ((changedCount + 1) / 2).coerceAtLeast(1)
    }

    fun switchCivics(newCivics: Map<String, String>): Int {
        val ruleset = civInfo.gameInfo.ruleset
        var changedCount = 0
        for ((categoryName, civicName) in newCivics) {
            if (activeCivics[categoryName] == civicName) continue
            val civic = ruleset.civics[civicName] ?: continue
            if (civic.category.name != categoryName) continue
            if (!canAdoptCivic(civic)) continue
            activeCivics[categoryName] = civicName
            changedCount++
        }
        if (changedCount == 0) return 0

        recalculateCivicUniques()
        if (civInfo.isCiv4ReligionEnabled() && civInfo.hasUnique(UniqueType.NoStateReligion) && civInfo.civ4Religion.stateReligion != null) {
            civInfo.civ4Religion.setStateReligion(null, applyAnarchy = false)
        }
        val anarchy = getAnarchyTurnsForChanges(changedCount)
        civInfo.anarchyTurns += anarchy
        civInfo.cache.updateCivResources()
        for (city in civInfo.cities) city.cityStats.update()
        civInfo.updateStatsForNextTurn()
        return anarchy
    }

    @Readonly
    fun getCivicUpkeep(): Int {
        if (!civInfo.gameInfo.ruleset.modOptions.hasUnique(UniqueType.CivicsMode)) return 0
        val numCities = civInfo.cities.size
        if (numCities == 0) return 0
        val totalPopulation = civInfo.cities.sumOf { it.population.population }
        val basePerMultiplier = 0.4f * numCities + 0.1f * totalPopulation

        val rulesetCivics = civInfo.gameInfo.ruleset.civics
        var rawUpkeep = 0f
        for (civicName in activeCivics.values) {
            val civic = rulesetCivics[civicName] ?: continue
            rawUpkeep += civic.upkeep.costMultiplier * basePerMultiplier
        }

        var modifierPercent = 0f
        civInfo.forEachMatchingUnique(UniqueType.CivicUpkeepDiscount) {
            modifierPercent += it.params[0].toFloat()
        }
        val finalUpkeep = rawUpkeep * ((100f + modifierPercent) / 100f).coerceAtLeast(0f)
        return finalUpkeep.toInt().coerceAtLeast(0)
    }
}
