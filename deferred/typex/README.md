# Deferred TypeX implementation — not part of the active Android UI

2026-10-18. The current UI does not support TypeX. Future integration belongs to a
separately authorized Dynamics project, not Phase 7 or this UI refinement.

`ui-refinement-2026-10-15/` contains verbatim pre-deactivation editor, connector-form
and device-test snapshots. `.snapshot` files are outside all Android source sets
and cannot be built or navigated to. Do not wire these screens into Dynamics yet.
The prior reports and validation evidence remain read-only historical evidence.

The portable `TypeXSeam`, `SeamState`, `JointDetails.typeX`, legacy cross-line
fields, source/revision snapshots, serializers, schema-6 codec and validation stay
in the model for lossless import/export. The old eligibility/declaration helpers
are retained for compatibility/development; the UI does not call them. Ordinary
connection edits copy `JointDetails` without replacing its inactive fields. Removed
annotated joints continue to use the retained detached-record mechanism. There is
no silent conversion from an inactive seam to a regular connection record.

`ConnectorLibrary.inactive` gates transverse sewn hardware out of active selection,
required-mass checks, concentrated mass, result diagnostics and cache mass signatures.
The records themselves still resolve and serialize with their immutable identities.
Seam annotations and generic legacy declarations do not create additional mechanical
constraints. All ordinary structural joints retain their established common nodes.
Anchor attachments are not editable and have no strength/failure diagnostics;
legacy regular anchor mass annotations are preserved for numerical compatibility.

The previous implementation was annotation-only. It could not calculate transverse
seam force, release main/backup coupling or redistribute backup slack. Its published
12 kN lower-bound example was assembly-specific, never a universal exact failure
threshold. No automatic seam failure, stitching damage or webbing-MBS reduction was
implemented. Deactivation adds none of these mechanics.

Future work requires an approved mechanical formulation for independent path/node
kinematics, tension/slack/contact states, transverse seam forces, constitutive and
assembly evidence, energy-consistent rupture/release, force balance and slack
redistribution. It also needs migration semantics distinguishing old annotations
from supported Dynamics inputs, and independent analytical/numerical/device tests.
Recovering the old UI alone does not provide a valid Dynamics implementation.
