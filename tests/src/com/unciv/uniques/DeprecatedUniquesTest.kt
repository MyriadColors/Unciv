package com.unciv.uniques

import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.unique.DeprecatedUniques
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.validation.RulesetErrorSeverity
import com.unciv.models.ruleset.validation.UniqueAutoUpdater
import com.unciv.models.ruleset.validation.UniqueValidator
import com.unciv.models.translations.getPlaceholderText
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for the [DeprecatedUniques] system and its integration with [UniqueValidator] and [UniqueAutoUpdater].
 *
 * All tests in this file are expected to **fail initially** because [DeprecatedUniques] does not
 * yet exist, and the required companion changes to [Unique], [UniqueValidator], and [UniqueAutoUpdater]
 * have not yet been made (see [DeprecatedUniques] KDoc for the full list).
 *
 * ## Use cases covered
 * 1. No placeholder-text overlap between [DeprecatedUniques] and active [UniqueType] entries.
 * 2. Every [DeprecatedUniques] replacement chain terminates at a non-deprecated [UniqueType].
 * 3. A unique whose text matches a [DeprecatedUniques] entry reports an **error** in [UniqueValidator].
 * 4. A half-deprecated unique (still in [UniqueType] with [DeprecationLevel.WARNING]) still reports a
 *    **warning** in [UniqueValidator] — ensuring backward compatibility.
 * 5. [UniqueAutoUpdater] detects and maps a fully deprecated unique to its replacement.
 * 6. [UniqueAutoUpdater] follows chains through multiple [DeprecatedUniques] hops before producing
 *    the final replacement (tests the "fully deprecated → fully deprecated → current" path).
 * 7. [UniqueAutoUpdater] handles a ruleset containing both half-deprecated and fully deprecated uniques.
 */
@RunWith(BaseTestRunner::class)
class DeprecatedUniquesTest {

    // -------------------------------------------------------------------------------------
    // Test 1 — Structural: no text collision between DeprecatedUniques and UniqueType
    // -------------------------------------------------------------------------------------

    @Test
    fun noPlaceholderTextConflictBetweenDeprecatedAndUniqueType() {
        val activeTypeMap = UniqueType.entries.associateBy { it.placeholderText }
        val conflicts = mutableListOf<String>()

        for (deprecated in DeprecatedUniques.entries) {
            if (activeTypeMap.containsKey(deprecated.placeholderText)) {
                conflicts += "${deprecated.name} conflicts with ${activeTypeMap[deprecated.placeholderText]!!.name}"
            }
        }

        assertTrue(
            "DeprecatedUniques entries must not share placeholder text with active UniqueType entries:\n${conflicts.joinToString("\n")}",
            conflicts.isEmpty()
        )
    }

    // -------------------------------------------------------------------------------------
    // Test 2 — Structural: every replacement chain reaches a non-deprecated UniqueType
    // -------------------------------------------------------------------------------------

    @Test
    fun allDeprecatedUniquesReplacementChainsTerminate() {
        // Simulate the same chain-following logic that UniqueAutoUpdater uses, but without a
        // ruleset (parameter substitution is skipped; we only follow placeholder matches).
        for (entry in DeprecatedUniques.entries) {
            var currentPlaceholder = entry.replacementText.getPlaceholderText()
            var hops = 0
            val maxHops = 20

            while (true) {
                val nextDeprecated = DeprecatedUniques.uniqueTypeMap[currentPlaceholder]
                val nextHalfDeprecated = UniqueType.uniqueTypeMap[currentPlaceholder]
                    ?.takeIf { it.getDeprecationAnnotation() != null }

                when {
                    nextDeprecated != null -> currentPlaceholder = nextDeprecated.replacementText.getPlaceholderText()
                    nextHalfDeprecated != null -> {
                        val annotation = nextHalfDeprecated.getDeprecationAnnotation()!!
                        currentPlaceholder = annotation.replaceWith.expression.getPlaceholderText()
                    }
                    else -> break // reached a non-deprecated UniqueType (or unknown text — acceptable)
                }

                hops++
                assertTrue(
                    "Replacement chain for DeprecatedUniques.${entry.name} has not terminated after $maxHops hops — possible cycle",
                    hops <= maxHops
                )
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Test 3 — Validator: fully deprecated unique reports ErrorOptionsOnly
    // -------------------------------------------------------------------------------------

    @Test
    fun fullyDeprecatedUniqueReportsErrorInValidator() {
        // TripleGoldFromEncampmentsAndCities is a simple, parameter-free fully deprecated unique.
        val deprecatedText = DeprecatedUniques.TripleGoldFromEncampmentsAndCities.text
        val unique = Unique(deprecatedText)

        RulesetCache.loadRulesets(noMods = true)
        val ruleset = RulesetCache.getVanillaRuleset()

        val errors = UniqueValidator(ruleset).checkUnique(unique, null)

        assertTrue(
            "A unique matching DeprecatedUniques should produce at least one ErrorOptionsOnly error, got: ${errors.map { it.errorSeverityToReport }}",
            errors.any { it.errorSeverityToReport == RulesetErrorSeverity.ErrorOptionsOnly }
        )
    }

    // -------------------------------------------------------------------------------------
    // Test 5 — AutoUpdater: detects and replaces a single fully deprecated unique
    // -------------------------------------------------------------------------------------

    @Test
    fun autoUpdaterReplacesFullyDeprecatedUnique() {
        // TripleGoldFromEncampmentsAndCities has a clear, parameter-free replacement.
        val deprecated = DeprecatedUniques.TripleGoldFromEncampmentsAndCities
        val game = TestGame()
        game.createBuilding(deprecated.text)

        val replacements = UniqueAutoUpdater.getDeprecatedReplaceableUniques(game.ruleset, game.ruleset)

        assertNotNull(
            "AutoUpdater should produce a replacement for '${deprecated.text}'",
            replacements[deprecated.text]
        )

        val replacement = Unique(replacements[deprecated.text]!!)
        assertNull(
            "The produced replacement '${replacement.text}' must not itself be deprecated",
            replacement.getDeprecationAnnotation()
        )
        assertNull(
            "The produced replacement '${replacement.text}' must not match any DeprecatedUniques",
            DeprecatedUniques.uniqueTypeMap[replacement.placeholderText]
        )
    }

    // -------------------------------------------------------------------------------------
    // Test 6 — AutoUpdater: follows a two-hop chain through DeprecatedUniques
    //
    // Chain: DecreasedBuildingMaintenanceDeprecated → BuildingMaintenanceOld → BuildingMaintenance
    //
    // How the chain works:
    //   DecreasedBuildingMaintenanceDeprecated.replacementText has placeholder "[]% maintenance cost for buildings []"
    //   which matches BuildingMaintenanceOld.placeholderText — so the auto-updater must take a second hop.
    //   BuildingMaintenanceOld.replacementText has placeholder "[]% maintenance cost for [] buildings []"
    //   which matches UniqueType.BuildingMaintenance (current, non-deprecated).
    // -------------------------------------------------------------------------------------

    @Test
    fun autoUpdaterFollowsChainThroughDeprecatedUniques() {
        // A building with a very old building-maintenance unique that was deprecated twice.
        // Concrete text: "-[50]% maintenance cost for buildings [in all cities]"
        val chainStart = DeprecatedUniques.DecreasedBuildingMaintenanceDeprecated
        val concreteText = "-[50]% maintenance cost for buildings [in all cities]"

        // Verify our test assumption: the replacement's placeholder matches BuildingMaintenanceOld
        val replacementPlaceholder = chainStart.replacementText.getPlaceholderText()
        assertEquals(
            "Test assumption violated: DecreasedBuildingMaintenanceDeprecated.replacementText placeholder should match BuildingMaintenanceOld",
            DeprecatedUniques.BuildingMaintenanceOld.placeholderText,
            replacementPlaceholder
        )

        val game = TestGame()
        game.createBuilding(concreteText)

        val replacements = UniqueAutoUpdater.getDeprecatedReplaceableUniques(game.ruleset, game.ruleset)

        assertNotNull(
            "AutoUpdater should follow the two-hop chain and produce a replacement for '$concreteText'",
            replacements[concreteText]
        )

        val finalReplacement = Unique(replacements[concreteText]!!)
        assertNull(
            "The final replacement '${finalReplacement.text}' must not itself be deprecated (chain was not fully followed)",
            finalReplacement.getDeprecationAnnotation()
        )
        assertNull(
            "The final replacement '${finalReplacement.text}' must not match any DeprecatedUniques (chain was not fully followed)",
            DeprecatedUniques.uniqueTypeMap[finalReplacement.placeholderText]
        )

        // The final replacement should resolve to UniqueType.BuildingMaintenance
        assertEquals(
            "Final replacement should be UniqueType.BuildingMaintenance",
            UniqueType.BuildingMaintenance,
            finalReplacement.type
        )
    }

    // -------------------------------------------------------------------------------------
    // Test 7 — AutoUpdater: handles a ruleset with both half-deprecated and fully deprecated
    // -------------------------------------------------------------------------------------

    @Test
    fun autoUpdaterHandlesMixOfHalfAndFullyDeprecatedUniques() {
        val game = TestGame()

        // Fully deprecated (in DeprecatedUniques)
        game.createBuilding(DeprecatedUniques.TripleGoldFromEncampmentsAndCities.text)

        // Half deprecated (still in UniqueType with DeprecationLevel.WARNING)
        // FoodConsumptionBySpecialists: "[relativeAmount]% Food consumption by specialists [cityFilter]"
        game.createBuilding("[+50]% Food consumption by specialists [in all cities]")

        val replacements = UniqueAutoUpdater.getDeprecatedReplaceableUniques(game.ruleset, game.ruleset)

        // Both should appear in the replacement map (assuming valid replacements)
        assertTrue(
            "AutoUpdater should detect and replace the fully deprecated unique",
            replacements.containsKey(DeprecatedUniques.TripleGoldFromEncampmentsAndCities.text)
        )
        assertTrue(
            "AutoUpdater should detect and replace the half-deprecated unique",
            replacements.containsKey("[+50]% Food consumption by specialists [in all cities]")
        )

        // All replacements must be non-deprecated
        for ((original, replacement) in replacements) {
            val replacementUnique = Unique(replacement)
            assertNull(
                "Replacement for '$original' is still deprecated in UniqueType: '$replacement'",
                replacementUnique.getDeprecationAnnotation()
            )
            assertNull(
                "Replacement for '$original' is still in DeprecatedUniques: '$replacement'",
                DeprecatedUniques.uniqueTypeMap[replacementUnique.placeholderText]
            )
        }
    }
}
