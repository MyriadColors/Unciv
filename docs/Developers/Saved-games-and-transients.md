# Saved games and transients

Unciv is a game where many things are interconnected. Each map unit, for example, belongs to a *civ*, is located on a *tile*, can have several *promotions* and "inherits" from a *base unit*.

When saving a game state, we want it to be as small as possible - so we limit the information saved to the bare minimum. We save names instead of pointers, and anything that can be recalculated is simply not saved.

But during runtime, we *need* these links for performance - why perform a lookup every time if we can save a reference on the object?

Classes are therefore *freeze dried* on serialization, and *rehydrated* for runtime. Since these fields are marked in Kotlin as @Transient fields, we call the rehydration function `setTransients`.

Take the map unit for example. How can we calculate the uniques for that unit? They can come from several places:

- Base unit uniques
- Promotions
- Civ-wide uniques

So from the save file, we get the civ name, unit name, and promotion names; for runtime, we'll want a reference to the civ, base unit, and promotions.

We can find these by looking up the civ in the game, and the unit and promotions from the ruleset.

The civ itself - a game object - references the nation - a ruleset object, which is another link. The base unit references the unit type, another link.

The nation, base unit, and promotions, all contain *uniques* - which are saved as strings. For performance, these too get saved at runtime as Unique instances - which contain the unique type, the parameters, conditionals, etc.

Beyond the fact that each ruleset object retains a "hydrated" list of its uniques, the unit's uniques don't change very often - so we can add *yet another* layer of caching by saving all the unit's uniques, and rebuilding this every time there's a change.

All of this is VITAL for performance - Unciv is built to run on low-end hardware, and even hash lookups or deterministic bracket-scanned `Unique` parsing (`TranslationExtensions.parsePairs`) become expensive when repeated thousands of times per turn!

## Serialization Rules (`IsPartOfGameInfoSerialization`)

Every class that is part of the saved `GameInfo` tree implements `IsPartOfGameInfoSerialization` (`core/src/com/unciv/logic/GameInfo.kt`) and must obey LibGDX `Json` serialization invariants:

1. **No-arg constructor**: Every serializable class must have a zero-argument constructor (or default values for all primary constructor parameters).
2. **Concrete collection types**: Serialized collection fields must be declared as concrete classes (`ArrayList`, `HashMap`, `HashSet`, `Counter`), never abstract interfaces (`List`, `Map`, `Set`), so LibGDX `Json` knows which collection class to instantiate.
3. **Transient lazy delegates**: Any `by lazy` property on a serializable class must be annotated with `@delegate:Transient` so the Kotlin `Lazy` delegate object is not serialized.
4. **Rehydration in `setTransients`**: Transient parent references (`civInfo`, `city`, `tileMap`, `ruleset`) and derived caches (`CivicManager.recalculateCivicUniques()`, `CityExpansionManager.setTransients()`) must be restored in `setTransients()`.
