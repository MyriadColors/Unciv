package com.unciv.logic.filter

import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Ruleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Tests [Policy][com.unciv.models.ruleset.Policy] filters*/
@RunWith(BaseTestRunner::class)
class PolicyFilterTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization

    @Before
    fun initTheWorld() {
        setupModdedGame()
    }
    
    @Test
    fun testPolicyMatchesFilter() {
        // Don't use a fake Policy without a branch, the policyFilter would stumble over a lateinit.
        val taggedPolicyBranch = game.createPolicyBranch("Some marker")
        val traditionBranch = game.createPolicyBranch()
        val traditionPolicies = List(5) {
            game.createPolicy().apply {
                branch = traditionBranch
                traditionBranch.policies.add(this)
            }
        }
        val libertyBranch = game.createPolicyBranch()
        val citizenship = game.createPolicy().apply {
            branch = libertyBranch
            libertyBranch.policies.add(this)
        }
        val polices = listOf(traditionBranch) + traditionPolicies + listOf(
            traditionBranch.policies.first { it.name.endsWith(com.unciv.models.ruleset.Policy.branchCompleteSuffix) },
            libertyBranch, citizenship, taggedPolicyBranch
        )
        for (policy in polices) {
            if (!civ.policies.isAdopted(policy.name)) {
                civ.policies.freePolicies++
                civ.policies.adopt(policy)
            }
        }

        val completeName = traditionBranch.name + com.unciv.models.ruleset.Policy.branchCompleteSuffix
        val filters = listOf(
            completeName to (listOf(completeName) to 1), // Completion policies are their own separate policies
            traditionBranch.name to (listOf(traditionBranch.name) to 1), // Should just be the starter by itself
            "[${traditionBranch.name}] branch" to ( // Should include the starter and completion policy, along with all policies in its branch
                (listOf(traditionBranch.name, completeName) + traditionPolicies.map { it.name }) to 7),
            "${libertyBranch.name}${com.unciv.models.ruleset.Policy.branchCompleteSuffix}" to (emptyList<String>() to 0), // Should not include completion branches that have not been completed
            "[${libertyBranch.name}] branch" to (listOf(libertyBranch.name, citizenship.name) to 2), // Should only include polices actually gotten
            "Military Tradition" to (emptyList<String>() to 0),
            "Some marker" to (listOf(taggedPolicyBranch.name) to 1) // Should include uniques of policies
        )
        val failures = ArrayList<String>()
        for (test in filters) {
            val filtered = civ.policies.getAdoptedPoliciesMatching(test.first)
                .toList()
            try {
                Assert.assertTrue(filtered.map { it.name }.containsAll(test.second.first))
                Assert.assertTrue(filtered.size == test.second.second)
            }
            catch (_: AssertionError) {
                failures.add("Filter: ${test.first}\nExpected result: ${test.second.first}, expected size: ${test.second.second}\n" +
                    "Result: $filtered, size: ${filtered.size}\n")
            }
        }
        if (failures.any()) {
            println(failures.joinToString("\n"))
            throw AssertionError()
        }
        Assert.assertEquals(civ.getCompletedPolicyBranchesCount(), 2) // Tradion and taggedPolicyBranch
    }

    private fun setupModdedGame(): Ruleset {
        game = TestGame()
        game.makeHexagonalMap(3)
        civ = game.addCiv()
        return game.ruleset
    }
}
