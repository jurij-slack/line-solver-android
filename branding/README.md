# Line Solver launcher artwork and data attribution

The supplied `../slackline_canyon_icon.png` is retained unchanged. See
`launcher-artwork.json` for its SHA-256, sizing and safe-zone measurements.

Color resources derive directly from that raster. Source coordinates are uniformly
scaled into the central 72 dp of a 108 dp layer. Only the outer tile/background is
extended from samples inside the old rounded border, so the original black matte
and pre-rounded edge are not nested inside the launcher's mask. Mainline, three
backup loops, walker, anchor rings and the inner canyon retain the source pixels
and their relative geometry/colors. Important colored elements stay within a
29.73 dp radius, inside the 33 dp safe circle. Foreground is a full-bleed scene;
the separate background is source-matched dark blue for edge/parallax coverage.

- `res/drawable-nodpi/ic_launcher_foreground.png`: 648 × 648 adaptive scene.
- `res/drawable/ic_launcher_background.xml`: full-bleed background.
- `res/drawable/ic_launcher_monochrome.xml`: clean transparent vector tracing of
  the walker, two anchor rings, mainline and three connected backup loops. Canyon
  details are omitted for legibility. The initial imagegen extraction draft was
  rejected because it introduced noisy filled areas; it is not used in the app.
- `res/mipmap-anydpi-v26/`: adaptive icons for the existing minimum API 26, with
  background/foreground and the monochrome layer consumed on API 33+.
- `res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/`: 48/72/96/144/192 px legacy icons,
  with separate single-mask circular variants and transparent outer corners.

The color resource derivation is deterministic; the asset-preparation record is
`validation/branding_isa/resource-generation.txt`. Mask/48 px previews and actual
Android-rendered adaptive/themed icon checks are stored in that validation folder.
No generated image replaces the finalized color artwork. No ISA logo is used.
Keep the v26 qualifier even though minSdk is 26: the real phone selected the
legacy density PNG when the adaptive XML was unversioned. The version qualifier
ensures the adaptive definition wins over the legacy bitmap configurations. Its
ObsoleteSdkInt lint notice is retained rather than suppressing checks.

Adaptive-icon sizing/reference:
https://developer.android.com/develop/ui/compose/system/icon_design_adaptive

## Data attribution

`DATA_ATTRIBUTION.md` mirrors the packaged `assets/licenses/SLACKDATA_NOTICE.txt`.
The original upstream `LICENSE-DATA`, including its scope, exclusions and complete
CC BY-SA 4.0 text, is bundled as `assets/licenses/SLACKDATA_LICENSE_DATA.txt`.
Existing catalogue/source/provenance files are unchanged. The notice describes
existing catalogue adaptations and their source hashes; it does not claim the
historical offline snapshot equals today's upstream main branch.

Verified sources:
- https://github.com/International-Slackline-Association/SlackData
- https://github.com/International-Slackline-Association/SlackData/blob/main/LICENSE-DATA
- https://creativecommons.org/licenses/by-sa/4.0/

Covered catalogue data/adaptations are distributed under CC BY-SA 4.0. The data
notice does not assign that license to the whole app, its icon, trademarks or
independently sourced work. Preserve record-level attribution and earlier change
tracking whenever updating the catalogue. Update the additive notice's hashes
when the covered distributed data changes; do not edit numerical curves to adjust
attribution.
