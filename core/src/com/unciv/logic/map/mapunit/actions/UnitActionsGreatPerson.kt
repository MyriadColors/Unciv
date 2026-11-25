package com.unciv.logic.map.mapunit.actions

import com.unciv.logic.city.CityFlags
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcons
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionConfirmation
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.tech.Technology
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toPercent
import kotlin.math.min

@Suppress("UNUSED_PARAMETER") // references need to have the signature expected by UnitActions.actionTypeToSingleFunctions and actionTypeToMultiFunctions
object UnitActionsGreatPerson {

    /** Finds the top researchable tech for Civ IV Great Person lightbulbing using ruleset `lightbulbPreferences`,
     *  falling back to the lowest-cost currently researchable tech. */
    fun getCiv4LightbulbTech(unit: MapUnit): Technology? {
        val techManager = unit.civ.tech
        val ruleset = unit.civ.gameInfo.ruleset
        for (techName in unit.baseUnit.lightbulbPreferences) {
            val tech = ruleset.technologies[techName] ?: continue
            if (techManager.canBeResearched(techName) && !tech.hasUnique(UniqueType.CannotBeHurried))
                return tech
        }
        return ruleset.technologies.values
            .filter { techManager.canBeResearched(it.name) && !it.hasUnique(UniqueType.CannotBeHurried) }
            .minByOrNull { techManager.costOfTech(it.name) }
    }

    /** Computes Civ IV lightbulb beakers: `(base + perPop * totalEmpirePop) * scienceCostModifier`. */
    fun getCiv4LightbulbBeakers(unit: MapUnit): Int {
        val isScientist = unit.baseUnit.name == "Great Scientist"
        val base = if (isScientist) 1500 else 1000
        val perPop = if (isScientist) 3 else 2
        val totalEmpirePop = unit.civ.cities.sumOf { it.population.population }
        return ((base + perPop * totalEmpirePop) * unit.civ.gameInfo.speed.scienceCostModifier).toInt()
    }

    // The hurried amount doesn't depend on the unique, so the first matching one is enough
    internal fun getHurryResearchAction(unit: MapUnit, tile: Tile): UnitAction? {
        val unique = unit.getMatchingUniques(UniqueType.CanHurryResearch).firstOrNull() ?: return null
        if (unit.civ.isCiv4GreatPeopleEnabled()) {
            val targetTech = getCiv4LightbulbTech(unit)
            val beakers = getCiv4LightbulbBeakers(unit)
            val title = if (targetTech != null)
                "Discover [${targetTech.name}] (+[$beakers] Science)"
            else UnitActionType.HurryResearch.value
            return UnitAction(
                UnitActionType.HurryResearch,
                title = title,
                associatedUnique = unique,
                action = {
                    val tech = targetTech!!
                    unit.civ.tech.addScienceToTech(tech.name, beakers)
                    unit.consume()
                }.takeIf { unit.hasMovement() && targetTech != null }
            )
        }
        return UnitAction(
            UnitActionType.HurryResearch,
            associatedUnique = unique,
            action = {
                unit.civ.tech.addScience(unit.civ.tech.getScienceFromGreatScientist())
                unit.consume()
            }.takeIf {
                unit.hasMovement()
                    && unit.civ.tech.currentTechnologyName() != null
                    && !unit.civ.tech.currentTechnology()!!.hasUnique(UniqueType.CannotBeHurried)
            }
        )
    }

    internal fun getSettleGreatPersonAction(unit: MapUnit, tile: Tile): UnitAction? {
        val unique = unit.getMatchingUniques(UniqueType.SettleGreatPersonStats).firstOrNull()
            ?: unit.getMatchingUniques(UniqueType.SettleGreatPersonUnitXP).firstOrNull()
            ?: return null
        val city = if (tile.isCityCenter() && tile.getCity()?.civ == unit.civ) tile.getCity() else null
        return UnitAction(
            UnitActionType.SettleGreatPerson,
            associatedUnique = unique,
            action = {
                val targetCity = city!!
                targetCity.settledGreatPeople.add(unit.baseUnit.name, 1)
                targetCity.cityStats.update()
                unit.consume()
            }.takeIf { unit.hasMovement() && city != null }
        )
    }

    internal fun getCreateGreatWorkAction(unit: MapUnit, tile: Tile): UnitAction? {
        val unique = unit.getMatchingUniques(UniqueType.CanCreateGreatWork).firstOrNull() ?: return null
        val city = if (tile.isCityCenter() && tile.getCity()?.civ == unit.civ) tile.getCity() else null
        val cultureAmount = (unique.params[0].toInt() * unit.civ.gameInfo.speed.cultureCostModifier).toInt()
        return UnitAction(
            UnitActionType.CreateGreatWork,
            title = "Create Great Work (+[$cultureAmount] Culture)",
            associatedUnique = unique,
            action = {
                val targetCity = city!!
                if (targetCity.isInResistance())
                    targetCity.removeFlag(CityFlags.Resistance)
                targetCity.expansion.nextTurn(cultureAmount.toFloat())
                if (!targetCity.civ.gameInfo.ruleset.modOptions.useCiv4CultureBorders) {
                    while (targetCity.expansion.cultureStored >= targetCity.expansion.getCultureToNextTile()
                        && targetCity.expansion.chooseNewTileToOwn() != null
                    ) {
                        targetCity.expansion.nextTurn(0f)
                    }
                }
                targetCity.cityStats.update()
                unit.consume()
            }.takeIf { unit.hasMovement() && city != null }
        )
    }

    internal fun getAttachWarlordActions(unit: MapUnit, tile: Tile): Sequence<UnitAction> = sequence {
        val unique = unit.getMatchingUniques(UniqueType.CanAttachAsWarlord).firstOrNull() ?: return@sequence
        val friendlyMilitary = tile.militaryUnits.filter { it.civ == unit.civ }
        val eligibleTargets = friendlyMilitary.filter { "Warlord" !in it.promotions.promotions }
        if (eligibleTargets.isEmpty()) {
            yield(UnitAction(UnitActionType.AttachWarlord, associatedUnique = unique, action = null))
            return@sequence
        }
        for (target in eligibleTargets) {
            yield(
                UnitAction(
                    UnitActionType.AttachWarlord,
                    title = "Attach as Warlord to [${target.displayName()}]",
                    associatedUnique = unique,
                    action = {
                        val stack = tile.militaryUnits.filter { it.civ == unit.civ }
                        if (stack.isNotEmpty()) {
                            val totalXp = 20
                            val baseShare = totalXp / stack.size
                            var remainder = totalXp % stack.size
                            // Order with target first so any remainder XP goes to the Warlord first
                            val ordered = listOf(target) + stack.filter { it != target }
                            for (stackUnit in ordered) {
                                val bonus = if (remainder > 0) {
                                    remainder--
                                    1
                                } else 0
                                stackUnit.promotions.XP += baseShare + bonus
                            }
                        }
                        target.promotions.addPromotion("Warlord", isFree = true)
                        unit.consume()
                    }.takeIf { unit.hasMovement() }
                )
            )
        }
    }

    internal fun getStartGoldenAgeAction(unit: MapUnit, tile: Tile): UnitAction? {
        if (!unit.civ.isCiv4GreatPeopleEnabled()) return null
        if (!unit.baseUnit.isGreatPerson || unit.baseUnit.hasUnique(UniqueType.CanAttachAsWarlord)) return null
        val required = unit.civ.goldenAges.getGreatPeopleRequiredForGoldenAge()
        val chosenUnits = unit.civ.goldenAges.getDistinctGreatPeopleForGoldenAge(unit)
        val turns = unit.civ.goldenAges.calculateGoldenAgeLength(8)
        val title = if (required == 1)
            "Start Golden Age ([$turns] turns)"
        else
            "Start Golden Age ([$required] Great People, [$turns] turns)"
        val confirmation = if (chosenUnits != null && chosenUnits.size > 1) {
            val names = chosenUnits.joinToString(", ") { it.baseUnit.name }
            UnitActionConfirmation(
                question = "Consume [$names] to start a [$turns]-turn Golden Age?",
                confirmText = "Start Golden Age"
            )
        } else null
        return UnitAction(
            UnitActionType.StartGoldenAge,
            title = title,
            confirmation = confirmation,
            action = {
                val unitsToConsume = unit.civ.goldenAges.getDistinctGreatPeopleForGoldenAge(unit)
                if (unitsToConsume != null) {
                    unit.civ.goldenAges.goldenAgesTriggeredByGreatPeople++
                    unit.civ.goldenAges.enterGoldenAge(8)
                    for (gp in unitsToConsume) {
                        gp.consume()
                    }
                }
            }.takeIf { unit.hasMovement() && chosenUnits != null }
        )
    }

    internal fun getHurryPolicyAction(unit: MapUnit, tile: Tile): UnitAction? {
        val unique = unit.getMatchingUniques(UniqueType.CanHurryPolicy).firstOrNull() ?: return null
        return UnitAction(
            UnitActionType.HurryPolicy,
            associatedUnique = unique,
            action = {
                unit.civ.policies.addCulture(unit.civ.policies.getCultureFromGreatWriter())
                unit.consume()
            }.takeIf {unit.hasMovement()}
        )
    }

    internal fun getHurryWonderAction(unit: MapUnit, tile: Tile): UnitAction? {
        val unique = unit.getMatchingUniques(UniqueType.CanSpeedupWonderConstruction).firstOrNull() ?: return null
        val canHurryWonder =
            if (!tile.isCityCenter()) false
            else tile.getCity()!!.cityConstructions.isBuildingWonder()
                && tile.getCity()!!.cityConstructions.canBeHurried()

        return UnitAction(
            UnitActionType.HurryWonder,
            associatedUnique = unique,
            action = {
                tile.getCity()!!.cityConstructions.apply {
                    //http://civilization.wikia.com/wiki/Great_engineer_(Civ5)
                    addProductionPoints(((300 + 30 * tile.getCity()!!.population.population) * unit.civ.gameInfo.speed.productionCostModifier).toInt())
                    constructIfEnough()
                }

                unit.consume()
            }.takeIf { unit.hasMovement() && canHurryWonder }
        )
    }

    internal fun getHurryBuildingAction(unit: MapUnit, tile: Tile): UnitAction? {
        val unique = unit.getMatchingUniques(UniqueType.CanSpeedupConstruction).firstOrNull() ?: return null
        if (!tile.isCityCenter())
            return UnitAction(UnitActionType.HurryBuilding, associatedUnique = unique, action = null)

        val cityConstructions = tile.getCity()!!.cityConstructions
        val currentName = cityConstructions.currentConstructionName() // Once: the queue can change between two reads
        val canHurryConstruction = cityConstructions.getConstruction(currentName) is Building
            && cityConstructions.canBeHurried()

        //http://civilization.wikia.com/wiki/Great_engineer_(Civ5)
        val productionPointsToAdd = min(
            (300 + 30 * tile.getCity()!!.population.population) * unit.civ.gameInfo.speed.productionCostModifier,
            cityConstructions.getRemainingWork(currentName).toFloat() - 1
        ).toInt()
        if (productionPointsToAdd <= 0) return null

        return UnitAction(
            UnitActionType.HurryBuilding,
            title = "Hurry Construction (+[$productionPointsToAdd]⚙)",
            associatedUnique = unique,
            action = {
                cityConstructions.apply {
                    addProductionPoints(productionPointsToAdd)
                    constructIfEnough()
                }

                unit.consume()
            }.takeIf { unit.hasMovement() && canHurryConstruction }
        )
    }

    internal fun getConductTradeMissionAction(unit: MapUnit, tile: Tile): UnitAction? {
        val canConductTradeMission = tile.owningCity?.civ?.isCityState == true
            && tile.owningCity?.civ != unit.civ
            && tile.owningCity?.civ?.isAtWarWith(unit.civ) == false
        // Enabled-ness doesn't depend on the unique, so the one giving the most influence is the best
        val unique = unit.getMatchingUniques(UniqueType.CanTradeWithCityStateForGoldAndInfluence)
            .maxByOrNull { it.params[0].toFloat() } ?: return null
        val influenceEarned = unique.params[0].toFloat()

        return UnitAction(
            UnitActionType.ConductTradeMission,
            associatedUnique = unique,
            action = {
                // http://civilization.wikia.com/wiki/Great_Merchant_(Civ5)
                var goldEarned = (350 + 50 * unit.civ.getEraNumber()) * unit.civ.gameInfo.speed.goldCostModifier

                // Apply the gold trade mission modifier
                for (goldUnique in unit.getMatchingUniques(UniqueType.PercentGoldFromTradeMissions, checkCivInfoUniques = true))
                    goldEarned *= goldUnique.params[0].toPercent()

                val goldEarnedInt = goldEarned.toInt()
                unit.civ.addGold(goldEarnedInt)
                val tileOwningCiv = tile.owningCity!!.civ

                tileOwningCiv.getDiplomacyManager(unit.civ)!!.addInfluence(influenceEarned)
                unit.civ.addNotification("Your trade mission to [$tileOwningCiv] has earned you [${goldEarnedInt.tr()}] gold and [${influenceEarned.tr()}] influence!",
                    NotificationCategory.General, tileOwningCiv.civName, NotificationIcons.Gold, NotificationIcons.Culture)
                unit.consume()
            }.takeIf { unit.hasMovement() && canConductTradeMission }
        )
    }
}
