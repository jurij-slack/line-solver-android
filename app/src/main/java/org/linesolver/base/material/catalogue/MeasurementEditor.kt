package org.linesolver.base.material.catalogue

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable data class MaterialEdit(
    val productId: String? = null, val datasetId: String? = null, val previousModelId: String? = null,
    val name: String = "", val brand: String = "", val aliases: String = "", val version: String = "", val batch: String = "",
    val notes: String = "", val composition: String = "", val widthMm: String = "", val mass: String = "", val mbs: String = "",
    val source: String = "User-entered data", val url: String = "", val restrictions: String = "", val license: String = "",
    val origin: String = "own_measured", val branch: String = "loading", val date: String = "", val protocol: String = "",
    val conditions: String = "", val uncertainty: String = "", val gaugeM: String = "",
    val forceUnit: String = "kN", val strainUnit: String = "%", val table: String = "",
    val forceColumn: Int = 0, val strainColumn: Int = 1, val csvHasHeader: Boolean = false, val importedCsvText: String? = null,
    val versionId: String? = null, val batchId: String? = null, val syntheticBatch: Boolean = false,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val category: ProductCategory = ProductCategory.webbing,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val bungee: BungeeLaw? = null
)
data class MeasurementTable(val rawRows: List<List<String>>, val points: List<MeasurementPoint>, val errors: List<String>)

object MeasurementCsv {
    fun number(raw: String): Double {
        val text = raw.trim()
        require(!text.contains('.') || !text.contains(',')) { "Ambiguous decimal '$raw'; use decimal dot or comma without grouping" }
        return requireNotNull(text.replace(',', '.').toDoubleOrNull()) { "Invalid number '$raw'" }.also {
            require(it.isFinite()) { "Nonfinite number '$raw'" }
        }
    }
    fun rows(text: String): List<List<String>> {
        val first = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val delimiter = when { ';' in first -> ';'; '\t' in first -> '\t'; else -> ',' }
        val result = mutableListOf<List<String>>(); val row = mutableListOf<String>(); val cell = StringBuilder()
        var quoted = false; var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && quoted && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == delimiter -> { row += cell.toString(); cell.clear() }
                !quoted && c == '\n' -> { row += cell.toString().removeSuffix("\r"); cell.clear(); if (row.size > 1 || row.any { it.isNotBlank() }) result += row.toList(); row.clear() }
                else -> cell.append(c)
            }
            i++
        }
        require(!quoted) { "Unclosed CSV quote" }
        row += cell.toString().removeSuffix("\r"); if (row.size > 1 || row.any { it.isNotBlank() }) result += row.toList()
        return result
    }
    fun encodeRows(rows: List<List<String>>) = rows.joinToString("\n") { row ->
        row.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }
    }
    /** Automatic mapping is limited to explicit known quantity/unit headers. Everything else needs confirmation. */
    fun detect(text: String): Triple<String, String, Pair<Int, Int>>? {
        val header = rows(text).firstOrNull() ?: return null
        val force = header.indexOfFirst { it.trim() in listOf("T/N", "T/kN") }
        val strain = header.indexOfFirst { it.trim() in listOf("epsilon/%", "epsilon/1", "ε/%", "ε/1", "extension/mm", "extension/m") }
        if (force < 0 || strain < 0) return null
        return Triple(header[force].trim().substringAfter('/'), header[strain].trim().substringAfter('/'), force to strain)
    }
    fun parse(edit: MaterialEdit): MeasurementTable {
        val errors = mutableListOf<String>(); val points = mutableListOf<MeasurementPoint>()
        val all = try { rows(edit.table) } catch (e: Exception) { return MeasurementTable(emptyList(), emptyList(), listOf(e.message.orEmpty())) }
        if (edit.forceUnit !in listOf("N", "kN") || edit.strainUnit !in listOf("1", "%", "mm", "m"))
            return MeasurementTable(all, emptyList(), listOf("Select explicit supported force and elongation units"))
        val gauge = if (edit.strainUnit in listOf("mm", "m")) runCatching { number(edit.gaugeM).also { require(it > 0) } }.getOrNull() else null
        if (edit.strainUnit in listOf("mm", "m") && gauge == null) errors += "Length elongation requires a finite positive gauge/reference length L0/m"
        require(edit.forceColumn >= 0 && edit.strainColumn >= 0 && edit.forceColumn != edit.strainColumn) { "Choose two distinct column numbers" }
        val header = edit.csvHasHeader || detect(edit.table) != null
        val data = if (header) all.drop(1) else all
        val origin = when (edit.origin) { "own_measured" -> PointOrigin.measured; "manufacturer" -> PointOrigin.supplied; else -> PointOrigin.derived }
        data.forEachIndexed { i, row ->
            try {
                require(row.size > maxOf(edit.forceColumn, edit.strainColumn)) { "Missing selected column" }
                val f = number(row[edit.forceColumn]) * if (edit.forceUnit == "kN") 1000 else 1
                val e = number(row[edit.strainColumn]) * when (edit.strainUnit) {
                    "%" -> .01; "mm" -> .001 / requireNotNull(gauge); "m" -> 1 / requireNotNull(gauge); else -> 1.0
                }
                require(f.isFinite() && e.isFinite()) { "Conversion exceeds finite range" }
                points += MeasurementPoint(e, f, origin)
            } catch (e: Exception) { errors += "Row ${i + 1}: ${e.message}" }
        }
        return MeasurementTable(all, points, errors)
    }
    fun export(dataset: MeasurementDataset): String {
        // The original table, including units, spelling and rejected cells, is the lossless CSV authority.
        val edit = dataset.rawPayload?.get("editor")?.let { CatalogueCodec.json.decodeFromJsonElement<MaterialEdit>(it) }
        require(edit != null) { "No retained user measurement table" }
        val header = "T/${edit.forceUnit},${if (edit.strainUnit in listOf("mm", "m")) "extension" else "epsilon"}/${edit.strainUnit}"
        val raw = rows(edit.table)
        val hasHeader = edit.csvHasHeader || detect(edit.table) != null
        fun quote(s: String) = "\"${s.replace("\"", "\"\"")}\""
        return header + "\n" + (if (hasHeader) raw.drop(1) else raw).joinToString("\n") { row ->
            listOf(row.getOrElse(edit.forceColumn) { "" }, row.getOrElse(edit.strainColumn) { "" }).joinToString(",", transform = ::quote)
        } + "\n"
    }
}

data class EditorPreview(val dataset: MeasurementDataset, val model: MaterialModel?, val problems: List<String>)
object MaterialEditing {
    fun load(doc: ComponentCatalogue, dataset: MeasurementDataset, modelId: String? = null): MaterialEdit =
        dataset.rawPayload?.get("editor")?.let { CatalogueCodec.json.decodeFromJsonElement<MaterialEdit>(it) }
            ?.copy(productId = dataset.productId, datasetId = dataset.id, previousModelId = modelId,
                versionId = dataset.versionId, batchId = dataset.batchId)
            ?: error("Bundled datasets are read-only; create a user-owned alternative")
    private fun quantity(text: String, unit: String): Double? = if (text.isBlank()) null else
        MeasurementCsv.number(text).also { require(it > 0) { "$unit must be positive or left unknown" } }
    fun preview(edit: MaterialEdit, productId: String = edit.productId ?: "user:00000000-0000-0000-0000-000000000000:preview",
        datasetId: String = "user:00000000-0000-0000-0000-000000000000:dataset",
        modelId: String = "user:00000000-0000-0000-0000-000000000000:model", revision: String = "1"): EditorPreview {
        val table = MeasurementCsv.parse(edit)
        require(edit.category in listOf(ProductCategory.webbing, ProductCategory.bungee))
        if(edit.category == ProductCategory.bungee) requireNotNull(edit.bungee) { "Declare measurement basis and physical reference length" }.validate()
        if(edit.category == ProductCategory.bungee && edit.strainUnit in listOf("mm","m")) {
            val b=requireNotNull(edit.bungee)
            require(kotlin.math.abs(MeasurementCsv.number(edit.gaugeM)-b.testedPhysicalLengthM/b.testedLegs)<=1e-10*b.testedPhysicalLengthM) {
                "Elongation gauge length must equal the tested installed reference separation (physical cord length / measured legs)"
            }
        }
        val specs = Specifications(densityKgPerM = quantity(edit.mass, "Linear mass kg/m"), mbsN = quantity(edit.mbs, "Nominal MBS N"),
            information = buildJsonObject { put("composition", edit.composition); quantity(edit.widthMm, "Width mm")?.let { put("width_m", it / 1000) }
                edit.bungee?.let { put("bungee_law", CatalogueCodec.json.encodeToJsonElement(it)) } })
        require(edit.source.isNotBlank()) { "Add a source reference" }
        require(edit.origin in listOf("own_measured", "manufacturer", "derived_from_source", "estimated")) { "Unsupported data origin" }
        require(edit.branch in listOf("loading", "unloading")) { "Unsupported branch" }
        val provenance = Provenance(edit.source, edit.url.takeIf { it.isNotBlank() }, edit.date.takeIf { it.isNotBlank() }, edit.notes)
        val dataset = MeasurementDataset(datasetId, productId, provenance, edit.versionId, edit.batchId,
            originalUnits = if (edit.strainUnit in listOf("1", "%")) SourceUnits(edit.forceUnit, if (edit.forceUnit == "kN") 1000.0 else 1.0,
                edit.strainUnit, if (edit.strainUnit == "%") .01 else 1.0) else null,
            procedure = edit.protocol, conditions = edit.conditions, rawDataStatus = if (edit.origin in listOf("estimated", "derived_from_source")) RawDataStatus.derived else RawDataStatus.raw,
            uncertaintyNotes = edit.uncertainty.lines().filter { it.isNotBlank() },
            conflictFlags = table.errors, points = if (table.errors.isEmpty()) table.points else null,
            rawPayload = buildJsonObject {
                put("editor", CatalogueCodec.json.encodeToJsonElement(edit)); put("origin", edit.origin); put("branch", edit.branch)
                put("license_notice", edit.license); put("batch_provenance", "user-declared; not independently verified")
                put("parsed_si_rows", CatalogueCodec.json.parseToJsonElement(CatalogueCodec.canonical(CatalogueCodec.json.encodeToJsonElement(table.points))))
                put("measurement_series", edit.datasetId ?: datasetId)
            })
        val assessment = CatalogueValidation.assess(dataset)
        val reasons = assessment.reasons.map { reason -> when(reason) {
            "nonmonotonic_points" -> "Force and strain must both increase strictly. Correct duplicate or reversed rows; source order is retained."
            "invalid_physical_point" -> "Force and engineering strain must be finite and nonnegative. Correct the reported cells."
            "contradictory_zero" -> "Zero force and zero strain must occur together. A nonzero force at zero strain cannot define this tension-only law."
            "invalid_synthetic_origin" -> "An imposed origin must be (0,0) and cannot be labelled as a measured point."
            "conflicting_source", "unparsed_source" -> "Unparsed or conflicting source cells need correction; no numerical fit was created."
            else -> reason.replace('_',' ')
        } }
        val errors = (table.errors + reasons).toMutableList()
        if (edit.branch != "loading") errors += "Unloading is retained for review; static models require a loading branch"
        if (edit.origin == "estimated") errors += "Estimated points are retained for review; independent observations are required to promote a model"
        if (specs.mbsN != null && table.points.any { it.forceN > specs.mbsN }) errors += "Curve exceeds nominal material MBS; correct or review the evidence"
        val valid = errors.isEmpty() && assessment.disposition in listOf(DatasetDisposition.monotonic_candidate, DatasetDisposition.provisional_candidate)
        val model = if (!valid) null else {
            val positive = table.points.filter { it.strain > 0 }
            val provisional = assessment.disposition == DatasetDisposition.provisional_candidate
            CatalogueIdentity.seal(MaterialModel(modelId, revision, "", productId,
                if (provisional) LawType.provisional_linear else LawType.pchip, listOf(datasetId),
                ModelDomain(positive.last().strain, StrainRange(if (provisional) positive.single().strain else table.points.first().strain, positive.last().strain),
                    if (edit.origin == "own_measured") RangeEvidence.measured else RangeEvidence.supplied),
                edit.versionId, edit.batchId, if (provisional) SourceQuality.provisional else SourceQuality.supplied,
                fitDescription = "User loading observations; imposed origin if absent; no upper continuation. Previous snapshot: ${edit.previousModelId ?: "none"}", specifications = specs), listOf(dataset))
        }
        if (model == null && errors.isEmpty()) errors += "No positive observations: catalogue/review only"
        if (edit.mass.isBlank() && edit.bungee?.totalMassKg==null) errors += "Reference/total mass unknown: plot/review only; unavailable for gravity calculations"
        if (edit.mbs.isBlank()) errors += "Nominal MBS unknown; no rupture endpoint or working load inferred"
        if (model?.type == LawType.provisional_linear) errors += "One-point provisional linear approximation; bounded at the observation"
        if (model != null && table.points.first().strain > 0) errors += "(0,0) is an imposed synthetic origin, not a measurement; dashed below the first observation"
        return EditorPreview(dataset, model, errors.distinct())
    }
    fun save(store: MaterialOverlayStore, edit: MaterialEdit): String {
        fun id() = "user:${store.value.namespace}:${UUID.randomUUID()}"
        val c = store.value.catalogue; val combined = MaterialPackages.combine(store.shipped, store.value)
        val productId = edit.productId ?: id()
        val existing = combined.products.firstOrNull { it.id == productId }
        require(existing == null || existing.category == edit.category) { "A product's component category cannot change" }
        require(existing != null || edit.name.isNotBlank()) { "Enter a product name" }
        val source = Provenance(edit.source, note = "User-declared identity; ${edit.notes}")
        val versions = if (edit.versionId == null && edit.version.isNotBlank()) listOf(ProductionVersion(id(), productId, edit.version, source)) else emptyList()
        val versionId = edit.versionId ?: versions.firstOrNull()?.id
        val batches = if (edit.batchId == null && edit.batch.isNotBlank()) listOf(ProductionBatch(id(), productId, edit.batch, source, versionId,syntheticExample=edit.syntheticBatch)) else emptyList()
        val batchId = edit.batchId ?: batches.firstOrNull()?.id
        val prepared = edit.copy(productId = productId, versionId = versionId, batchId = batchId)
        val previousModels = c.models.filter { it.productId == productId }
        val revision = (maxOf(previousModels.size,previousModels.maxOfOrNull { it.revision.toIntOrNull() ?: 0 } ?: 0) + 1).toString()
        var preview = preview(prepared, productId, id(), id(), revision)
        // Duplicating a fit must reference the SAME measurement snapshot if source/editor data is unchanged.
        val old = c.datasets.firstOrNull { it.id == edit.datasetId }
        if (old != null) {
            val previous = load(combined, old, edit.previousModelId)
            if (previous.copy(previousModelId = null) == prepared.copy(previousModelId = null)) {
                preview = preview.copy(dataset = old, model = preview.model?.let { CatalogueIdentity.seal(it.copy(datasetIds = listOf(old.id)), listOf(old)) })
            }
        }
        val p = Product(productId, edit.category, edit.brand.takeIf { it.isNotBlank() }, edit.name,
            edit.aliases.lines().filter { it.isNotBlank() }, listOf(source), edit.restrictions.lines().filter { it.isNotBlank() },
            preview.model?.specifications ?: Specifications(densityKgPerM = quantity(edit.mass, "kg/m"), mbsN = quantity(edit.mbs, "N"),
                information = buildJsonObject { put("notes", edit.notes); put("composition", edit.composition); put("width_mm", edit.widthMm) }))
        val products = when {
            existing == null -> c.products + p
            c.products.any { it.id == productId } -> c.products.map { if (it.id == productId) p else it }
            else -> c.products // bundled product is immutable; per-dataset/model metadata stays in the overlay
        }
        val next = c.copy(products = products, versions = c.versions + versions, batches = c.batches + batches,
            datasets = if (old?.id == preview.dataset.id) c.datasets else c.datasets + preview.dataset,
            models = c.models + listOfNotNull(preview.model))
        store.commit(store.value.copy(catalogue = next))
        return if (preview.model != null) "Saved immutable model revision $revision. Existing rig selections retained." else "Saved review-only dataset. Correct the reported points/metadata to construct a model."
    }
}
