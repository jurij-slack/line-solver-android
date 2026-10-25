# Advanced connector editor — isolated from Simple

The `.snapshot` files preserve the pre-cleanup multi-item, role/load-state,
verification, source/custom-revision, notes and kit editor for a separately
approved future Dynamics integration. They are outside Android source sets.
`ConnectorFields` also retains its advanced/custom form logic behind defaults used
only by code; Simple calls it with `simple=true`, suppressing management controls.
No active navigation exposes the advanced editor or TypeX.

Simple edits only zero/one resolved plain item for Main and Backup. Empty lists represent
idealized joints with no optional hardware mass/rating. Existing multi-item,
unavailable, shared/unassigned, port-assigned, load-annotated, verified-installation or inactive
legacy assemblies are read-only. They are neither flattened nor converted; their
known active masses still use the unchanged model. Complete records remain in
exported diagnostics. Saving a simple selection copies all other joint metadata.
No schema or constitutive-law migration is introduced.
