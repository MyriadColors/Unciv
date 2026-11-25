package com.unciv.logic.civilization.managers

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.CivilopediaAction
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.PopupAlert
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.components.extensions.toPercent
import yairm210.purity.annotations.Readonly

class GoldenAgeManager : IsPartOfGameInfoSerialization {
    @Transient
    lateinit var civInfo: Civilization

    var storedHappiness = 0
    private var numberOfGoldenAges = 0
    var goldenAgesTriggeredByGreatPeople = 0
    var turnsLeftForCurrentGoldenAge = 0

    fun clone(): GoldenAgeManager {
        val toReturn = GoldenAgeManager()
        toReturn.numberOfGoldenAges = numberOfGoldenAges
        toReturn.goldenAgesTriggeredByGreatPeople = goldenAgesTriggeredByGreatPeople
        toReturn.storedHappiness = storedHappiness
        toReturn.turnsLeftForCurrentGoldenAge = turnsLeftForCurrentGoldenAge
        return toReturn
    }

    @Readonly fun getGreatPeopleRequiredForGoldenAge(): Int = goldenAgesTriggeredByGreatPeople + 1

    @Readonly
    fun getDistinctGreatPeopleForGoldenAge(primaryUnit: com.unciv.logic.map.mapunit.MapUnit): List<com.unciv.logic.map.mapunit.MapUnit>? {
        val required = getGreatPeopleRequiredForGoldenAge()
        if (!primaryUnit.baseUnit.isGreatPerson || primaryUnit.baseUnit.hasUnique(UniqueType.CanAttachAsWarlord)) return null
        val chosen = ArrayList<com.unciv.logic.map.mapunit.MapUnit>()
        val seenTypes = HashSet<String>()
        chosen.add(primaryUnit)
        seenTypes.add(primaryUnit.baseUnit.name)
        if (chosen.size >= required) return chosen

        // Prefer distinct Great People on the same tile first
        for (other in primaryUnit.getTile().getUnits()) {
            if (other == primaryUnit || other.civ != civInfo) continue
            if (!other.baseUnit.isGreatPerson || other.baseUnit.hasUnique(UniqueType.CanAttachAsWarlord)) continue
            if (seenTypes.add(other.baseUnit.name)) {
                chosen.add(other)
                if (chosen.size >= required) return chosen
            }
        }

        // Fall back to distinct Great People elsewhere in the empire
        for (other in civInfo.units.getCivUnits()) {
            if (other == primaryUnit || other.getTile() == primaryUnit.getTile()) continue
            if (!other.baseUnit.isGreatPerson || other.baseUnit.hasUnique(UniqueType.CanAttachAsWarlord)) continue
            if (seenTypes.add(other.baseUnit.name)) {
                chosen.add(other)
                if (chosen.size >= required) return chosen
            }
        }
        return null
    }

    @Readonly fun isGoldenAge(): Boolean = turnsLeftForCurrentGoldenAge > 0
    
    fun addHappiness(amount: Int) {
        storedHappiness += amount
    }

    @Readonly
    fun happinessRequiredForNextGoldenAge(): Int {
        var cost = (500 + numberOfGoldenAges * 250).toFloat()
        cost *= civInfo.cities.size.toPercent()  //https://forums.civfanatics.com/resources/complete-guide-to-happiness-vanilla.25584/
        cost *= civInfo.gameInfo.speed.modifier
        return cost.toInt()
    }

    @Readonly
    fun calculateGoldenAgeLength(unmodifiedNumberOfTurns: Int): Int {
        var turnsToGoldenAge = unmodifiedNumberOfTurns.toFloat()
        civInfo.forEachMatchingUnique(UniqueType.GoldenAgeLength) { unique ->
            turnsToGoldenAge *= unique.params[0].toPercent()
        }
        turnsToGoldenAge *= civInfo.gameInfo.speed.goldenAgeLengthModifier
        return turnsToGoldenAge.toInt()
    }

    fun enterGoldenAge(unmodifiedNumberOfTurns: Int = 10) {
        turnsLeftForCurrentGoldenAge += calculateGoldenAgeLength(unmodifiedNumberOfTurns)
        civInfo.addNotification("You have entered a Golden Age!",
            CivilopediaAction("Tutorial/Golden Age"),
            NotificationCategory.General, "StatIcons/Happiness")
        civInfo.popupAlerts.add(PopupAlert(AlertType.GoldenAge, ""))

        civInfo.forEachTriggeredUnique(UniqueType.TriggerUponEnteringGoldenAge, ignoreCities = false) { unique ->
            UniqueTriggerActivation.triggerUnique(unique, civInfo)
        }
        //Golden Age can happen mid turn with Great Artist effects
        for (city in civInfo.cities)
            city.cityStats.update()
    }

    fun endTurn(happiness: Int) {
        val civ4Mode = civInfo.isCiv4GreatPeopleEnabled()
        if (!civ4Mode && !isGoldenAge())
            storedHappiness = (storedHappiness + happiness).coerceAtLeast(0)

        if (isGoldenAge()){
            turnsLeftForCurrentGoldenAge--
            if (turnsLeftForCurrentGoldenAge <= 0)
                civInfo.forEachTriggeredUnique(UniqueType.TriggerUpponEndingGoldenAge, ignoreCities = false) { unique ->
                    UniqueTriggerActivation.triggerUnique(unique, civInfo)
                }
        }
                
        else if (!civ4Mode && storedHappiness >= happinessRequiredForNextGoldenAge()) {
            storedHappiness -= happinessRequiredForNextGoldenAge()
            enterGoldenAge()
            numberOfGoldenAges++
        }
    }

}
