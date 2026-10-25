# Temporary demonstration restriction

`ui/DemoBungeePolicy.kt` is the single temporary gate. Its `available=false`
disables the existing settings checkbox, rig/material configuration entry points
and Android ViewModel calculation requests for rigs with bungee banks. Stored
visibility flags, assemblies, models and library records are retained. No mechanical
solver, material definition, schema or persistence implementation was changed.

The full `BungeeEditor` and `BungeeLawFields` source remains unchanged. This directory
preserves the previous advanced bungee UI workflow test as a noncompiled snapshot;
current phone tests verify the demo restriction instead. Native bungee mechanics
and serialization tests remain active.

After separately authorizing bungee access again, change `available` to true and
revalidate/restore the archived UI workflow test alongside the existing mechanics
regressions. This restores the original entry points and bypasses the demo guard;
no data migration or reconstruction should be needed. Do not silently drop saved
bungee assemblies to make a demonstration calculation run.
