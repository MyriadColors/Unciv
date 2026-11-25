package com.unciv.logic.civilization.managers

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Civic
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.nation.PersonalityValue
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueMap
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
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
        if (!::civInfo.isInitialized) return 0
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

    @Readonly
    fun evaluateCivic(civic: Civic): Float {
        if (!::civInfo.isInitialized) return 0f
        val cities = civInfo.cities
        val numCities = cities.size.coerceAtLeast(1)
        val totalPop = cities.sumOf { it.population.population }

        var score = 0f
        for (unique in civic.uniqueObjects) {
            score += evaluateCivicUnique(unique, numCities)
        }

        val basePerMultiplier = 0.4f * numCities + 0.1f * totalPop
        var upkeepDiscountPercent = 0f
        civInfo.forEachMatchingUnique(UniqueType.CivicUpkeepDiscount) {
            upkeepDiscountPercent += it.params[0].toFloat()
        }
        val upkeepCost = civic.upkeep.costMultiplier * basePerMultiplier * ((100f + upkeepDiscountPercent) / 100f).coerceAtLeast(0f)
        val goldPressure = if (civInfo.stats.statsForNextTurn.gold < 0f && civInfo.gold < 100) 2.0f else 0.8f
        score -= upkeepCost * goldPressure

        return score
    }

    @Readonly
    private fun evaluateCivicUnique(unique: Unique, numCities: Int): Float {
        return when (unique.type) {
            UniqueType.Stats -> scoreStats(unique.stats) * numCities
            UniqueType.StatsFromObject -> evaluateStatsFromObject(unique, numCities)
            UniqueType.StatPercentBonusCities -> evaluateStatPercentBonusCities(unique)
            UniqueType.CanWhipProduction -> evaluateWhipUnique()
            UniqueType.CanHurryProductionWithGold -> 5f + (civInfo.gold / 200f).coerceIn(0f, 10f)
            UniqueType.ImprovementGrowthRate -> 4f + countUpgradingTiles() * 2f
            UniqueType.CityHealth -> evaluateCityHealthUnique(unique)
            UniqueType.PercentProductionUnits -> evaluateMilitaryProductionUnique(unique)
            UniqueType.PercentProductionBuildings -> evaluateBuildingProductionUnique(unique)
            else -> evaluateOtherCivicUnique(unique, numCities)
        }
    }

    @Readonly
    private fun scoreStats(s: com.unciv.models.stats.Stats): Float =
        s.food * 2.5f + s.production * 2.5f + s.science * 2.2f + s.gold * 1.5f + s.culture * 1.5f + s.happiness * 2.5f

    @Readonly
    private fun evaluateStatsFromObject(unique: Unique, numCities: Int): Float {
        val filter = unique.params[1]
        val statVal = scoreStats(unique.stats)
        var matchCount = 0
        for (city in civInfo.cities) {
            matchCount += city.population.getNewSpecialists()[filter]
            if (city.cityConstructions.isBuilt(filter)) matchCount++
            matchCount += city.getTiles().count { it.improvement == filter && !it.improvementIsPillaged }
        }
        return statVal * (matchCount + numCities * 0.5f)
    }

    @Readonly
    private fun evaluateStatPercentBonusCities(unique: Unique): Float {
        val amount = unique.params[0].toFloatOrNull() ?: 0f
        val statName = unique.params[1]
        val targetCities = if (unique.params[2] == "in capital") civInfo.cities.filter { it.isCapital() } else civInfo.cities
        var bonusScore = 0f
        for (city in targetCities) {
            val baseStat = getCityStatByName(city, statName).coerceAtLeast(2f)
            bonusScore += baseStat * (amount / 100f) * 1.5f
        }
        return bonusScore
    }

    @Readonly
    private fun getCityStatByName(city: com.unciv.logic.city.City, statName: String): Float = when (statName) {
        Stat.Production.name -> city.cityStats.currentCityStats.production
        Stat.Gold.name -> city.cityStats.currentCityStats.gold
        Stat.Science.name -> city.cityStats.currentCityStats.science
        Stat.Culture.name -> city.cityStats.currentCityStats.culture
        Stat.Food.name -> city.cityStats.currentCityStats.food
        else -> 4f
    }

    @Readonly
    private fun evaluateWhipUnique(): Float {
        val whipCities = civInfo.cities.count { it.getAngryCitizens() > 0 || it.population.population >= 4 }
        return 6f + whipCities * 2.5f + (if (civInfo.isAtWar()) 4f else 0f)
    }

    @Readonly
    private fun countUpgradingTiles(): Int = civInfo.cities.sumOf { city ->
        city.getTiles().count { tile ->
            tile.getUnpillagedTileImprovement()?.hasUnique(UniqueType.ImprovementUpgradesAfterTurns) == true
        }
    }

    @Readonly
    private fun evaluateCityHealthUnique(unique: Unique): Float {
        val healthAmount = unique.params[0].toFloatOrNull() ?: 0f
        val unhealthyCities = civInfo.cities.count { it.getHealthBreakdown().netHealth <= 2 }
        return healthAmount * (1f + unhealthyCities * 1.5f)
    }

    @Readonly
    private fun evaluateMilitaryProductionUnique(unique: Unique): Float {
        val bonus = unique.params[0].toFloatOrNull() ?: 0f
        val militaryFocus = civInfo.getPersonality().modifierFocus(PersonalityValue.Military, 0.3f)
        return (bonus * 0.35f) * (if (civInfo.isAtWar()) 2.2f else 1f) * militaryFocus
    }

    @Readonly
    private fun evaluateBuildingProductionUnique(unique: Unique): Float {
        val bonus = unique.params[0].toFloatOrNull() ?: 0f
        val stateReligionCities = countStateReligionCities()
        return (bonus * 0.25f) * stateReligionCities.coerceAtLeast(1)
    }

    @Readonly
    private fun countStateReligionCities(): Int {
        val stateReligion = if (civInfo.isCiv4ReligionEnabled()) civInfo.civ4Religion.stateReligion else null
        return if (stateReligion != null) civInfo.cities.count { stateReligion in it.presentReligions } else 0
    }

    @Readonly
    private fun evaluateOtherCivicUnique(unique: Unique, numCities: Int): Float {
        val militaryFocus = civInfo.getPersonality().modifierFocus(PersonalityValue.Military, 0.3f)
        val stateReligionCities = countStateReligionCities()
        return when (unique.type) {
            UniqueType.BlocksNonStateReligionSpread -> if (stateReligionCities > 0) 3f * militaryFocus else 0f
            UniqueType.NoStateReligion -> -stateReligionCities * 1.5f
            UniqueType.HappinessPerPresentReligion -> {
                val amount = unique.params[0].toFloatOrNull() ?: 0f
                amount * civInfo.cities.sumOf { it.presentReligions.size } * 2.5f
            }
            UniqueType.CivicUpkeepDiscount -> -(unique.params[0].toFloatOrNull() ?: 0f) * 0.2f * numCities
            else -> evaluateCivicTextFallback(unique.text, numCities, militaryFocus, stateReligionCities)
        }
    }

    @Readonly
    private fun evaluateCivicTextFallback(
        text: String,
        numCities: Int,
        militaryFocus: Float,
        stateReligionCities: Int
    ): Float {
        val isAtWar = civInfo.isAtWar()
        return when {
            text.contains("XP gained from combat") -> (if (isAtWar) 14f else 6f) * militaryFocus
            text.contains("construction speed of tile improvements") -> 4f + numCities * 1.5f
            text.contains(" growth rate of upgrading improvements") -> 6f + countUpgradingTiles() * 2f
            text.contains("Great Person generation") -> 4f * stateReligionCities.coerceAtLeast(1)
            text.contains("maintenance costs") && text.contains("Military") -> {
                val militaryCount = civInfo.units.getCivUnits().count { it.baseUnit.isMilitary }
                -militaryCount * (if (isAtWar) 1.5f else 0.6f)
            }
            else -> 3f
        }
    }

    /** Evaluates Slavery population whipping for AI cities when [UniqueType.CanWhipProduction] is active. */
    fun automateSlaveryWhip() {
        if (!::civInfo.isInitialized || !civInfo.hasUnique(UniqueType.CanWhipProduction)) return
        val isAtWar = civInfo.isAtWar()
        for (city in civInfo.cities) {
            val constructions = city.cityConstructions
            val currentConstruction = constructions.getCurrentConstruction() as? INonPerpetualConstruction ?: continue
            if (!constructions.canWhipConstruction(currentConstruction)) continue
            val popCost = constructions.getWhipPopulationCost(currentConstruction)
            if (popCost > 0 && shouldWhipCity(city, currentConstruction, popCost, isAtWar)) {
                constructions.whipConstruction(currentConstruction)
            }
        }
    }

    /** Evaluates Nationhood defensive unit drafting for AI cities when [UniqueType.CanDraftUnits] is active. */
    fun automateNationhoodDraft() {
        if (!::civInfo.isInitialized || !civInfo.hasUnique(UniqueType.CanDraftUnits)) return
        if (!civInfo.isAtWar()) return
        for (city in civInfo.cities) {
            val constructions = city.cityConstructions
            if (!constructions.canDraftUnit()) continue
            if (city.getAngryCitizens() > 1) continue
            val enemyNearby = city.getCenterTile().getTilesInDistance(3).any { tile ->
                tile.militaryUnits.any { it.civ.isAtWarWith(civInfo) }
            }
            if (enemyNearby) {
                constructions.draftUnit()
            }
        }
    }

    @Readonly
    private fun shouldWhipCity(
        city: com.unciv.logic.city.City,
        currentConstruction: INonPerpetualConstruction,
        popCost: Int,
        isAtWar: Boolean
    ): Boolean {
        val isBuildingMilitary = currentConstruction is BaseUnit && currentConstruction.isMilitary
        val enemyNearby = isAtWar && city.getCenterTile().getTilesInDistance(3).any { tile ->
            tile.militaryUnits.any { it.civ.isAtWarWith(civInfo) }
        }
        if (enemyNearby && isBuildingMilitary && popCost <= 2) return true
        if (city.whipAngerTurns > 0) return false
        val angryCitizens = city.getAngryCitizens()
        val netHappiness = city.cityStats.happinessList.values.sum()
        return angryCitizens >= popCost || (city.population.population >= 4 && netHappiness >= 1f && popCost == 1)
    }

    /** Evaluates unlocked civics across all categories and switches in a single batch when worthwhile. */
    fun automateCivics(): Int {
        if (!::civInfo.isInitialized) return 0
        if (!civInfo.gameInfo.ruleset.modOptions.hasUnique(UniqueType.CivicsMode)) return 0
        if (civInfo.isInAnarchy()) return 0

        initDefaults()
        val categories = civInfo.gameInfo.ruleset.civicCategories.values
        val desiredCivics = HashMap<String, String>()
        var totalScoreGain = 0f
        var changedCount = 0

        for (category in categories) {
            val currentCivic = getActiveCivic(category.name)
            val currentScore = if (currentCivic != null) evaluateCivic(currentCivic) else Float.NEGATIVE_INFINITY
            var bestCivic = currentCivic
            var bestScore = currentScore

            for (candidate in category.civics) {
                if (!canAdoptCivic(candidate)) continue
                val candidateScore = evaluateCivic(candidate)
                if (candidateScore > bestScore + 0.5f) {
                    bestScore = candidateScore
                    bestCivic = candidate
                }
            }

            if (bestCivic != null && bestCivic.name != currentCivic?.name) {
                desiredCivics[category.name] = bestCivic.name
                totalScoreGain += (bestScore - currentScore.coerceAtLeast(0f))
                changedCount++
            }
        }

        if (changedCount == 0) return 0
        val expectedAnarchy = getAnarchyTurnsForChanges(changedCount)
        val anarchyThreshold = expectedAnarchy * 4f
        if (totalScoreGain < anarchyThreshold) return 0

        return switchCivics(desiredCivics)
    }
}
