# Troubleshooting

Short answers to the common build and run problems. For the general setup, see
the [README](../README.md) — in particular the
[Building](../README.md#building), [Files](../README.md#files) and
[Dependencies](../README.md#dependencies) sections.

## Wrong Java version

Minecraft 1.21.1 and this mod both target Java 21, in two different places.

**Building.** The Gradle build compiles for a Java 21 toolchain. If
`./gradlew build` (or `.\gradlew.bat build` in PowerShell) stops with a
`No matching toolchains found for requested specification` error that names
Java 21, then Gradle has no JDK 21 to use. Install a JDK 21 (Temurin, or the
OpenJDK package of your distribution), point `JAVA_HOME` at it and check that
`java -version` reports 21.

**Running the game.** Minecraft 1.21.1 requires Java 21. If the game crashes on
start with an `UnsupportedClassVersionError` or a message about
`class file version 65.0` (65 is Java 21), it was launched on an older runtime.
The official launcher uses the right runtime for the 1.21.1 profile by default;
in a third-party launcher, set the instance's Java to a Java 21 runtime.

## NeoForge version mismatch

The mod is built for Minecraft 1.21.1 and NeoForge 21.1.248 or newer; those are
the versions in `META-INF/neoforge.mods.toml`. On another Minecraft version, or
on an older 21.1.x NeoForge build, the loader refuses to load the mod: Craft
Planner is missing from the mod list, or NeoForge's loading-errors screen names
it with a dependency error.

Install NeoForge 21.1.248 or newer for Minecraft 1.21.1 and start that profile.
If a modpack ships an older NeoForge build, update NeoForge in the pack's
instance settings first.

## The mod is not in the mod list

Check, in this order:

1. The file is the built jar (`build/libs/craftplanner-1.21.1-1.0.0.jar`) and
   lies **directly** in the instance's `mods/` folder — not in a subfolder and
   not unzipped.
2. It is installed in the client instance, not on the server: the mod is
   client-only, so servers neither need it nor load it.
3. The instance really is NeoForge for Minecraft 1.21.1 (not Forge or Fabric),
   and the profile you start is that NeoForge profile.
4. `logs/latest.log` mentions `craftplanner`; an error next to it points at the
   cause (a NeoForge version error, for example — see above).
5. If the game crashes during startup because of another mod, the list may
   never appear. Fix that mod's error first.

## The plan is empty after a restart

The plan is saved in `config/craftplanner-plan.json` in the game directory of
the instance you play. Check:

- You are in the same instance and game directory as before. A different
  instance, launcher profile or `gameDir` has its own `config/` and therefore
  its own plan. Copy the `craftplanner-*.json` files over to move the plan.
- `config/` is writable. If it is not, `logs/latest.log` contains a
  `Craft Planner could not save the plan to ...` warning, and nothing is kept.
- You did not delete the file: removing it resets the plan.

The plan is read after you enter a world, once the item registry is available.
Goals that point at items from a mod that has since been removed are dropped
when the plan loads, because those items no longer exist.

Recipe choices, "use as material" cuts and the recipe-sources selection are
kept in the other `craftplanner-*.json` files next to it; see
[Files](../README.md#files).

## Still stuck?

Open a bug report with `logs/latest.log` and the Craft Planner, NeoForge and
Minecraft versions:
[new bug report](https://github.com/ust-maya/Craft-Planner/issues/new?template=bug_report.yml).
