package com.unciv.models.ruleset

import com.unciv.Constants
import com.unciv.logic.MultiFilter
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.translations.tr
import com.unciv.ui.objectdescriptions.uniquesToCivilopediaTextLines
import com.unciv.ui.screens.civilopediascreen.FormattedLine
import yairm210.purity.annotations.Readonly

enum class CivicUpkeep(val costMultiplier: Float, val displayName: String) {
    None(0f, "No Upkeep"),
    Low(0.5f, "Low Upkeep"),
    Medium(1.0f, "Medium Upkeep"),
    High(1.5f, "High Upkeep")
}

class CivicCategory : RulesetObject() {
    var civics: ArrayList<Civic> = arrayListOf()
    var defaultCivic: String = ""

    override fun getUniqueTarget() = UniqueTarget.Civic
    override fun makeLink() = "Civic/$name"
}

class Civic : RulesetObject() {
    @Transient
    lateinit var category: CivicCategory

    var upkeep: CivicUpkeep = CivicUpkeep.None
    var requiredTech: String? = null
    var priorities: HashMap<String, Int> = HashMap()

    override fun getUniqueTarget() = UniqueTarget.Civic
    override fun makeLink() = "Civic/$name"

    @Readonly
    fun matchesFilter(filter: String, state: GameContext? = null): Boolean =
        MultiFilter.multiFilter(filter, {
            matchesSingleFilter(it) ||
                state != null && hasTagUnique(it, state) ||
                state == null && hasTagUnique(it)
        })

    @Readonly
    fun matchesSingleFilter(filter: String): Boolean {
        return when (filter) {
            in Constants.all -> true
            name -> true
            category.name -> true
            else -> false
        }
    }

    /** Used in CivicPickerScreen to display Civic properties */
    fun getDescription(): String {
        val lines = ArrayList<String>()
        lines += name.tr()
        lines += upkeep.displayName.tr()
        if (requiredTech != null)
            lines += "Requires [${requiredTech!!}]".tr()
        for (unique in uniqueObjects) {
            if (!unique.isHiddenToUsers())
                lines += unique.getDisplayText().tr()
        }
        return lines.joinToString("\n")
    }

    override fun getCivilopediaTextLines(ruleset: Ruleset): List<FormattedLine> {
        val textList = ArrayList<FormattedLine>()
        textList += FormattedLine("Category: [${category.name}]")
        textList += FormattedLine("Upkeep: [${upkeep.displayName}]")
        if (requiredTech != null)
            textList += FormattedLine("Required tech: [${requiredTech!!}]", link = "Technology/${requiredTech!!}")
        uniquesToCivilopediaTextLines(textList)
        return textList
    }
}
