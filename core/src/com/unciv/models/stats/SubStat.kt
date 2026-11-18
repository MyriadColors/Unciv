package com.unciv.models.stats

import com.unciv.logic.civilization.NotificationIcons

enum class SubStat(val text: String, val icon: String) : GameResource {
    GoldenAgePoints("Golden Age points", NotificationIcons.Happiness),
    StoredFood("Stored Food", NotificationIcons.Food), // city-scoped
    ;
    companion object {
        fun safeValueOf(name: String): SubStat? = entries.firstOrNull { it.text == name }
    }
}
