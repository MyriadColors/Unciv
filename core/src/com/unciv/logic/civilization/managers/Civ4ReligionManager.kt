package com.unciv.logic.civilization.managers

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly
import kotlin.random.Random

class Civ4ReligionManager : IsPartOfGameInfoSerialization {

    @Transient
    lateinit var civInfo: Civilization

    var stateReligion: String? = null
        private set

    fun clone(): Civ4ReligionManager {
        val clone = Civ4ReligionManager()
        clone.stateReligion = stateReligion
        return clone
    }

    fun setTransients(civInfo: Civilization) {
        this.civInfo = civInfo
    }

    @Readonly
    fun isReligionFounded(religionName: String): Boolean {
        for (civ in civInfo.gameInfo.civilizations) {
            for (city in civ.cities) {
                if (religionName in city.holyCityOfReligions) return true
            }
        }
        return false
    }

    @Readonly
    fun getFoundedReligions(): Map<String, City?> {
        val founded = LinkedHashMap<String, City?>()
        for (civ in civInfo.gameInfo.civilizations) {
            for (city in civ.cities) {
                for (religion in city.holyCityOfReligions) {
                    founded[religion] = city
                }
            }
        }
        return founded
    }

    @Readonly
    fun getAvailableStateReligions(): List<String> {
        val available = ArrayList<String>()
        val seen = HashSet<String>()
        for (city in civInfo.cities) {
            for (religion in city.presentReligions) {
                if (seen.add(religion)) available.add(religion)
            }
        }
        return available
    }

    fun foundReligion(religionName: String, preferredCity: City? = null): City? {
        if (religionName !in civInfo.gameInfo.ruleset.religions) return null
        if (isReligionFounded(religionName)) return null
        if (civInfo.cities.isEmpty()) return null

        val targetCity = preferredCity ?: selectHolyCityCandidate() ?: return null
        targetCity.holyCityOfReligions.add(religionName)
        targetCity.presentReligions.add(religionName)
        targetCity.cityStats.update()

        if (!civInfo.isSpectator) {
            civInfo.addNotification(
                "[$religionName] has been founded in [${targetCity.name}]!",
                targetCity.location,
                NotificationCategory.Religion,
                NotificationIcon.Faith
            )
        }
        return targetCity
    }

    @Readonly
    private fun selectHolyCityCandidate(): City? {
        if (civInfo.cities.isEmpty()) return null
        var bestNonCapital: City? = null
        for (city in civInfo.cities) {
            if (city.isCapital()) continue
            if (bestNonCapital == null || city.presentReligions.size < bestNonCapital.presentReligions.size) {
                bestNonCapital = city
            }
        }
        return bestNonCapital ?: civInfo.getCapital() ?: civInfo.cities.firstOrNull()
    }

    fun setStateReligion(religionName: String?, applyAnarchy: Boolean = true) {
        if (religionName != null && religionName !in getAvailableStateReligions()) return
        if (religionName == stateReligion) return

        stateReligion = religionName

        if (applyAnarchy && !civInfo.hasUnique(UniqueType.NoAnarchy) && !civInfo.goldenAges.isGoldenAge()) {
            civInfo.anarchyTurns = maxOf(civInfo.anarchyTurns, 1)
        }

        for (city in civInfo.cities) {
            city.cityStats.update()
        }
        for (diplo in civInfo.diplomacy.values) {
            diplo.setReligionBasedModifier()
            diplo.otherCivDiplomacy().setReligionBasedModifier()
        }
    }

    @Readonly
    fun canSpreadReligionToCity(religionName: String, city: City): Boolean {
        if (religionName !in civInfo.gameInfo.ruleset.religions) return false
        if (religionName in city.presentReligions) return false
        if (city.civ.hasUnique(UniqueType.BlocksNonStateReligionSpread) &&
            city.civ.civ4Religion.stateReligion != religionName
        ) {
            return false
        }
        return true
    }

    fun spreadReligionToCity(religionName: String, city: City): Boolean {
        if (!canSpreadReligionToCity(religionName, city)) return false
        city.presentReligions.add(religionName)
        city.cityStats.update()
        return true
    }

    fun endTurn(random: Random = Random.Default) {
        if (civInfo.isInAnarchy()) return

        processPassiveSpread(random)
        processAiStateReligion()
    }

    private fun processPassiveSpread(random: Random) {
        if (civInfo.cities.isEmpty()) return

        val candidateReligions = collectTradeNetworkReligions()
        if (candidateReligions.isEmpty()) return

        var spreadModifierPercent = 100f
        civInfo.forEachMatchingUnique(UniqueType.ReligionSpreadModifier) { unique ->
            spreadModifierPercent += unique.params[0].toFloat()
        }
        val multiplier = (spreadModifierPercent / 100f).coerceAtLeast(0f)

        for (city in civInfo.cities) {
            val baseChance = if (city.presentReligions.isEmpty()) 0.10f else 0.03f
            val chance = (baseChance * multiplier).coerceIn(0f, 1f)
            if (chance <= 0f) continue

            for (religion in candidateReligions) {
                if (!canSpreadReligionToCity(religion, city)) continue
                if (random.nextFloat() < chance) {
                    if (spreadReligionToCity(religion, city) && !civInfo.isSpectator) {
                        civInfo.addNotification(
                            "[$religion] has spread to [${city.name}]!",
                            city.location,
                            NotificationCategory.Religion,
                            NotificationIcon.Faith
                        )
                    }
                    break
                }
            }
        }
    }

    @Readonly
    private fun collectTradeNetworkReligions(): List<String> {
        val result = ArrayList<String>()
        val seen = HashSet<String>()

        for (city in civInfo.cities) {
            if (city.isCapital() || city.isConnectedToCapital()) {
                for (religion in city.presentReligions) {
                    if (seen.add(religion)) result.add(religion)
                }
            }
        }

        for (otherCiv in civInfo.getKnownCivs()) {
            val diplo = civInfo.getDiplomacyManager(otherCiv) ?: continue
            if (!diplo.hasOpenBorders && !otherCiv.isCityState) continue
            for (otherCity in otherCiv.cities) {
                for (religion in otherCity.presentReligions) {
                    if (seen.add(religion)) result.add(religion)
                }
            }
        }

        return result
    }

    private fun processAiStateReligion() {
        if (civInfo.isHuman() || stateReligion != null) return
        val counts = HashMap<String, Int>()
        for (city in civInfo.cities) {
            for (religion in city.presentReligions) {
                counts[religion] = (counts[religion] ?: 0) + 1
            }
        }
        var bestReligion: String? = null
        var bestCount = 0
        for ((religion, count) in counts) {
            if (count > bestCount) {
                bestCount = count
                bestReligion = religion
            }
        }
        if (bestReligion != null) {
            setStateReligion(bestReligion, applyAnarchy = true)
        }
    }
}
