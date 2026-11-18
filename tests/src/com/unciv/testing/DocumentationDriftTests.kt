package com.unciv.testing

import com.unciv.models.ModConstants
import com.unciv.models.ruleset.MilestoneType
import com.unciv.models.ruleset.ModOptions
import com.unciv.models.ruleset.RulesetFile
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Modifier

@RunWith(BaseTestRunner::class)
class DocumentationDriftTests {

    private fun resolveRepoRoot(): File {
        val current = File(".").canonicalFile
        return if (current.name == "assets" && current.parentFile?.name == "android") {
            current.parentFile.parentFile
        } else if (File(current, "docs").exists()) {
            current
        } else {
            current.parentFile ?: current
        }
    }

    @Test
    fun `every RulesetFile is listed in 1-Overview and has a JSON schema`() {
        val repoRoot = resolveRepoRoot()
        val overviewFile = File(repoRoot, "docs/Modders/Mod-file-structure/1-Overview.md")
        assertTrue("1-Overview.md must exist", overviewFile.exists())
        val overviewText = overviewFile.readText()
        val schemasDir = File(repoRoot, "docs/Modders/schemas")

        for (entry in RulesetFile.entries) {
            val baseName = entry.filename.removeSuffix(".json")
            assertTrue(
                "1-Overview.md is missing documentation link for ${entry.filename}",
                overviewText.contains(entry.filename) || overviewText.contains("[$baseName]")
            )
            val schemaFile = File(schemasDir, "$baseName.schema.json")
            assertTrue(
                "Missing JSON schema file docs/Modders/schemas/$baseName.schema.json for ${entry.filename}",
                schemaFile.exists()
            )
        }
    }

    @Test
    fun `ModOptions and ModConstants properties are synchronized with ModOptions schema`() {
        val repoRoot = resolveRepoRoot()
        val schemaText = File(repoRoot, "docs/Modders/schemas/ModOptions.schema.json").readText()

        val excludedModOptionsFields = setOf(
            "name", "uniques", "uniqueObjects", "uniqueMap",
            "modUrl", "defaultBranch", "author", "lastUpdated", "modSize", "topics"
        )
        for (field in ModOptions::class.java.declaredFields) {
            if (field.modifiers and Modifier.STATIC != 0) continue
            if (field.name.contains("$")) continue
            if (field.name in excludedModOptionsFields) continue
            assertTrue(
                "ModOptions.schema.json is missing ModOptions property '${field.name}'",
                schemaText.contains("\"${field.name}\"")
            )
        }

        for (field in ModConstants::class.java.declaredFields) {
            if (field.modifiers and Modifier.STATIC != 0) continue
            if (field.name.contains("$")) continue
            assertTrue(
                "ModOptions.schema.json is missing ModConstants property '${field.name}'",
                schemaText.contains("\"${field.name}\"")
            )
        }
    }

    @Test
    fun `all MilestoneType entries are documented in VictoryTypes schema`() {
        val repoRoot = resolveRepoRoot()
        val schemaText = File(repoRoot, "docs/Modders/schemas/VictoryTypes.schema.json").readText()

        for (milestone in MilestoneType.entries) {
            assertTrue(
                "VictoryTypes.schema.json is missing MilestoneType '${milestone.name}' (${milestone.text})",
                schemaText.contains("\"${milestone.text}\"")
            )
        }
    }

    @Test
    fun `mod file structure and developer docs do not link to removed legacy ruleset or tileset paths`() {
        val repoRoot = resolveRepoRoot()
        val docsToVerify = listOf(
            "docs/Modders/Mod-file-structure/1-Overview.md",
            "docs/Modders/Mod-file-structure/2-Civilization-related-JSON-files.md",
            "docs/Modders/Mod-file-structure/3-Map-related-JSON-files.md",
            "docs/Modders/Mod-file-structure/4-Unit-related-JSON-files.md",
            "docs/Modders/Mod-file-structure/5-Miscellaneous-JSON-files.md",
            "docs/Developers/Project-structure-and-major-classes.md",
            "docs/Developers/Map-rendering.md",
            "docs/Developers/AI-Architecture.md",
            ".agents/map.md"
        )
        val forbiddenLegacyPaths = listOf(
            "android/assets/jsons/Civ%20V",
            "android/assets/jsons/Civ V",
            "TileSets/FantasyHex.json"
        )

        for (relativePath in docsToVerify) {
            val file = File(repoRoot, relativePath)
            assertTrue("Expected documentation file $relativePath to exist", file.exists())
            val content = file.readText()
            for (forbidden in forbiddenLegacyPaths) {
                assertTrue(
                    "Documentation file $relativePath contains dead legacy path '$forbidden'",
                    !content.contains(forbidden)
                )
            }
        }
    }
}
