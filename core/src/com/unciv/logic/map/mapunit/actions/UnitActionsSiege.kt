package com.unciv.logic.map.mapunit.actions

import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.UniqueType

object UnitActionsSiege {

    internal fun getBombardActions(unit: MapUnit, tile: Tile): Sequence<UnitAction> = sequence {
        var bombardRate = 0
        for (unique in unit.getMatchingUniques(UniqueType.CanBombardCityDefenses)) {
            bombardRate += unique.params[0].toInt()
        }
        if (bombardRate <= 0) return@sequence

        for (neighbor in tile.neighbors) {
            val city = neighbor.getCity() ?: continue
            if (!unit.civ.isAtWarWith(city.civ)) continue
            if (city.getCityDefenseBonus() <= 0) continue

            val canExecute = unit.hasMovement() && unit.canAttack() && !unit.isEmbarked()
            yield(
                UnitAction(
                    UnitActionType.Bombard,
                    title = "Bombard [${city.name}]",
                    action = {
                        val maxDefense = city.getMaxCityDefenseBonus()
                        val previousBonus = city.getCityDefenseBonus()
                        city.defenseDamageTaken = (city.defenseDamageTaken + bombardRate).coerceAtMost(maxDefense)
                        val newBonus = city.getCityDefenseBonus()
                        val reducedBy = previousBonus - newBonus

                        unit.attacksThisTurn += 1
                        unit.currentMovement = 0f

                        city.civ.addNotification(
                            "An enemy [${unit.baseUnit.name}] bombarded [${city.name}]'s defenses (-$reducedBy%)!",
                            city.location,
                            NotificationCategory.War,
                            NotificationIcon.War,
                            unit.baseUnit.name
                        )
                    }.takeIf { canExecute }
                )
            )
        }
    }
}
