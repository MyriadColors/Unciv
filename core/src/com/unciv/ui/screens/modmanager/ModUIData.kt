package com.unciv.ui.screens.modmanager

import com.unciv.logic.github.GithubAPI
import com.unciv.models.metadata.ModCategories
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.translations.tr
import com.unciv.ui.components.fonts.Fonts

/** Helper class holds combined mod info for ModManagementScreen, used for both installed and online lists.
 *
 *  Contains metadata only, some preformatted for the UI, but no Gdx actors!
 *  (This is important on resize - ModUIData are passed to the new screen)
 *  Note it is guaranteed either ruleset or repo are non-null, never both.
 */
class ModUIData private constructor(
    val name: String,
    val description: String,
    val ruleset: Ruleset? = null,
    val repo: GithubAPI.Repo? = null,
    var isVisual: Boolean = false,
    var hasUpdate: Boolean = false
) {
    // For deserialization from cache file 
    constructor():this("","")
    
    constructor(ruleset: Ruleset, isVisual: Boolean): this (
        ruleset.name,
        ruleset.getSummary().let {
            "Installed".tr() + (if (it.isEmpty()) "" else ": $it")
        },
        ruleset, null, isVisual = isVisual
    )

    constructor(repo: GithubAPI.Repo, isUpdated: Boolean): this (
        repo.name,
        (repo.description ?: "-{No description provided}-".tr()) +
                "\n" + "[${repo.stargazers_count}]${Fonts.star}".tr(),
        null, repo, hasUpdate = isUpdated
    )

    val isInstalled get() = ruleset != null
    fun lastUpdated() = ruleset?.modOptions?.lastUpdated ?: repo?.pushed_at ?: ""
    fun stargazers() = repo?.stargazers_count ?: 0
    fun author() = ruleset?.modOptions?.author ?: repo?.owner?.login ?: ""
    fun topics() = ruleset?.modOptions?.topics ?: repo?.topics ?: emptyList()
    fun buttonText() = when {
        ruleset != null -> ModManagementScreen.cleanModName(ruleset.name)
        repo != null -> ModManagementScreen.cleanModName(repo.name) + (if (hasUpdate) " - {Updated}" else "")
        else -> ""
    }

    internal fun compatibilityIssue(): String? {
        if (ruleset != null) {
            if (ruleset.folderLocation == null) return null // Built-in rulesets (e.g. BasicSquare)
            if (ruleset.modOptions.isBaseRuleset)
                return "Ruleset mods are currently disabled due to engine differences (square grid & Civ IV mechanics)."
            val folder = ruleset.folderLocation!!
            val jsonFolder = folder.child("jsons")
            if (jsonFolder.exists() && jsonFolder.isDirectory &&
                jsonFolder.list().any {
                    if (it.isDirectory) it.name() != "Skins"
                    else it.name() != "ModOptions.json"
                }
            ) {
                return "Ruleset/gameplay mods are currently disabled due to engine differences (square grid & Civ IV mechanics)."
            }
            if (folder.list("atlas").any { atlasFile ->
                    atlasFile.readString().lineSequence().any { it.startsWith("TileSets/") }
                }) {
                return "Tileset mods are currently disabled because external hex tilesets are incompatible with the square grid."
            }
            if (com.unciv.models.ruleset.validation.ModCompatibility.isAssetOnlyMod(ruleset))
                return null
            return "Only asset-only mods (graphics, audio, skins — excluding tilesets) are currently supported."
        }
        if (repo != null) {
            if ("unciv-mod-rulesets" in repo.topics)
                return "Ruleset mods are currently disabled due to engine differences (square grid & Civ IV mechanics)."
            val textToCheck = (repo.name + " " + (repo.description ?: "")).lowercase()
            if (listOf("tileset", "tile set", "hex", "tiles").any { it in textToCheck })
                return "Tileset mods are currently disabled because external hex tilesets are incompatible with the square grid."
            if ("unciv-mod-graphics" in repo.topics || "unciv-mod-audio" in repo.topics)
                return null
            return "Only asset-only mods (graphics, audio, skins — excluding tilesets) are currently supported."
        }
        return null
    }

    internal fun matchesFilter(filter: ModManagementOptions.Filter): Boolean = when {
        filter.compatibleOnly && compatibilityIssue() != null -> false
        !matchesCategory(filter) -> false
        filter.text.isEmpty() -> true
        name.contains(filter.text, true) -> true
        // description.contains(filterText, true) -> true // too many surprises as description is different in the two columns
        author().contains(filter.text, true) -> true
        else -> false
    }

    private fun matchesCategory(filter: ModManagementOptions.Filter): Boolean {
        if (filter.topic == ModCategories.default().topic)
            return true
        val modTopics = repo?.topics ?: ruleset?.modOptions?.topics!!
        return filter.topic in modTopics
    }

    fun stateSortWeight() = when {
        hasUpdate && isVisual -> 3
        hasUpdate -> 2
        isVisual -> 1
        else -> 0
    }

    // Equality contract required to use this as HashMap key
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ModUIData) return false
        return other.isInstalled == isInstalled && other.name == name
    }

    override fun hashCode() = name.hashCode() * (if (isInstalled) 31 else 19)
}
