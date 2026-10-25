package org.linesolver.base.material.catalogue

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

/** Local wire envelope. Catalogue records remain catalogue-v1; bundled evidence is referenced, never exported. */
@Serializable data class MaterialPackage(
    val packageVersion: Int = 1,
    val namespace: String = UUID.randomUUID().toString(),
    val catalogue: ComponentCatalogue = ComponentCatalogue(1),
    val retiredModelIds: List<String> = emptyList(),
    val attribution: String = "User-owned records only. Source attribution and license notices retained in provenance/raw_payload."
)
enum class ImportPolicy { skip_identical, import_as_distinct, create_new_revisions }

object MaterialPackages {
    fun combine(shipped: ComponentCatalogue, local: MaterialPackage): ComponentCatalogue {
        require(local.packageVersion == 1) { "Unsupported material package version; file left unchanged." }
        require(runCatching { UUID.fromString(local.namespace) }.isSuccess) { "Invalid library namespace" }
        val c = local.catalogue
        require(c.schemaVersion == 1 && c.units == CatalogueUnits()) { "Unsupported catalogue schema or SI units" }
        require(c.definitions.isEmpty() && c.instances.isEmpty()) { "Phase 3 packages support webbing records only" }
        val ids = c.products.map { it.id } + c.versions.map { it.id } + c.batches.map { it.id } +
            c.datasets.map { it.id } + c.models.map { it.id }
        require(ids.all { Regex("user:[0-9a-f-]{36}:[A-Za-z0-9._/-]+").matches(it) }) { "Local IDs must use the user:<namespace-uuid>:<entity> namespace; bundled IDs cannot be replaced" }
        require(ids.distinct().size == ids.size) { "Duplicate local IDs; each immutable snapshot needs a distinct ID" }
        val bundledIds = shipped.products.map { it.id } + shipped.versions.map { it.id } + shipped.batches.map { it.id } +
            shipped.datasets.map { it.id } + shipped.models.map { it.id }
        require(ids.none { it in bundledIds }) { "Local record collides with bundled ID" }
        require(c.products.all { it.category in listOf(ProductCategory.webbing,ProductCategory.bungee) && it.legacyRecords.isEmpty() } &&
            c.datasets.all { it.legacyRecord == null } && c.models.all { it.legacyRecord == null && !it.curatedDefault }) {
            "User packages cannot bind external legacy curves, set shipped defaults or create non-webbing components"
        }
        require(local.retiredModelIds.distinct().size == local.retiredModelIds.size &&
            local.retiredModelIds.all { id -> c.models.any { it.id == id } }) { "Unknown or duplicate retired model ID" }
        val merged = shipped.copy(products = shipped.products + c.products, versions = shipped.versions + c.versions,
            batches = shipped.batches + c.batches, datasets = shipped.datasets + c.datasets, models = shipped.models + c.models)
        CatalogueValidation.requireValid(merged)
        c.datasets.forEach { d ->
            require(merged.products.single { it.id == d.productId }.category in listOf(ProductCategory.webbing,ProductCategory.bungee)) { "Dataset requires a webbing or bungee product" }
            val raw = d.rawPayload ?: error("User dataset ${d.id} needs retained raw editor/source data")
            require(raw["origin"]?.jsonPrimitive?.content in listOf("own_measured", "manufacturer", "derived_from_source", "estimated")) { "Unsupported data origin" }
            require(raw["branch"]?.jsonPrimitive?.content in listOf("loading", "unloading")) { "Unsupported measurement branch" }
            val edit = CatalogueCodec.json.decodeFromJsonElement<MaterialEdit>(requireNotNull(raw["editor"]) { "Missing raw editor/source record" })
            val normalized = MeasurementCsv.parse(edit)
            require(d.points == if (normalized.errors.isEmpty()) normalized.points else null) { "Raw points and normalized SI values disagree: ${d.id}" }
            raw["parsed_si_rows"]?.let { require(CatalogueCodec.json.decodeFromJsonElement<List<MeasurementPoint>>(it) == normalized.points) { "Partial normalized rows disagree with raw source" } }
            val units = if(edit.strainUnit in listOf("1", "%")) SourceUnits(edit.forceUnit,if(edit.forceUnit=="kN")1000.0 else 1.0,
                edit.strainUnit,if(edit.strainUnit=="%") .01 else 1.0) else null
            require(d.originalUnits == units) { "Source units disagree with raw editor declaration" }
            require(raw["origin"]?.jsonPrimitive?.content == edit.origin && raw["branch"]?.jsonPrimitive?.content == edit.branch) { "Raw provenance mismatch" }
            if (edit.origin == "estimated") require(c.models.none { d.id in it.datasetIds }) { "Estimated observations cannot be promoted" }
            if (raw["branch"]?.jsonPrimitive?.content == "unloading")
                require(c.models.none { d.id in it.datasetIds }) { "Unloading observations are review-only; the static law supports loading only" }
        }
        c.models.forEach { m ->
            if(merged.products.single { it.id == m.productId }.category == ProductCategory.bungee) {
                requireNotNull(m.bungeeLaw()) { "Bungee model requires explicit measurement basis" }.validate()
                val edit = CatalogueCodec.json.decodeFromJsonElement<MaterialEdit>(requireNotNull(merged.datasets.single { it.id == m.datasetIds.single() }.rawPayload?.get("editor")))
                require(edit.category == ProductCategory.bungee && edit.bungee == m.bungeeLaw()) { "Bungee basis disagrees with immutable measurement source" }
            }
            val points = merged.datasets.single { it.id == m.datasetIds.single() }.points.orEmpty()
            val fit = m.fitPointIndices?.map { points[it] } ?: points
            require(m.domain.supportedMaxStrain == fit.last().strain) { "Custom domain must end at its last supported observation" }
            require(m.specifications.densityKgPerM == null || m.specifications.densityKgPerM > 0) { "Custom linear mass must be positive or unknown" }
        }
        return merged
    }
    fun decode(text: String, shipped: ComponentCatalogue): MaterialPackage =
        CatalogueCodec.json.decodeFromString<MaterialPackage>(text).also { combine(shipped, it) }
    fun encode(value: MaterialPackage, shipped: ComponentCatalogue): String {
        combine(shipped, value)
        val c = value.catalogue
        val ordered = value.copy(retiredModelIds = value.retiredModelIds.sorted(), catalogue = c.copy(
            products = c.products.sortedBy { it.id }, versions = c.versions.sortedBy { it.id }, batches = c.batches.sortedBy { it.id },
            datasets = c.datasets.sortedBy { it.id }, models = c.models.sortedBy { it.id }))
        return CatalogueCodec.canonical(CatalogueCodec.json.encodeToJsonElement(ordered))
    }
    fun merge(current: MaterialPackage, incoming: MaterialPackage, shipped: ComponentCatalogue,
        policy: ImportPolicy = ImportPolicy.skip_identical): MaterialPackage {
        combine(shipped, incoming)
        val imported = when(policy) {
            ImportPolicy.import_as_distinct -> fork(incoming)
            ImportPolicy.create_new_revisions -> revisions(current,incoming)
            else -> incoming
        }
        fun <T> mergeRows(a: List<T>, b: List<T>, id: (T) -> String): List<T> {
            val existing = a.associateBy(id)
            b.forEach { row -> require(existing[id(row)] == null || existing[id(row)] == row) {
                "Conflicting immutable record ${id(row)}. Choose Import as distinct; old revisions cannot be replaced."
            } }
            return a + b.filter { id(it) !in existing }
        }
        val a = current.catalogue; val b = imported.catalogue
        val result = current.copy(namespace = if (a.products.isEmpty() && a.datasets.isEmpty() && a.models.isEmpty()) imported.namespace else current.namespace, catalogue = a.copy(
            products = mergeRows(a.products, b.products) { it.id }, versions = mergeRows(a.versions, b.versions) { it.id },
            batches = mergeRows(a.batches, b.batches) { it.id }, datasets = mergeRows(a.datasets, b.datasets) { it.id },
            models = mergeRows(a.models, b.models) { it.id }),
            retiredModelIds = (current.retiredModelIds + imported.retiredModelIds).distinct())
        combine(shipped, result)
        return result
    }
    private fun revisions(current: MaterialPackage, incoming: MaterialPackage): MaterialPackage {
        val a=current.catalogue;val b=incoming.catalogue
        fun origin(m:MaterialModel)="Imported revision of ${m.id}/${m.revision}/${m.contentFingerprint}"
        fun datasetToken(d:MeasurementDataset)=org.linesolver.base.model.materialFingerprint(
            CatalogueCodec.canonical(CatalogueCodec.json.encodeToJsonElement(d)))
        if(b.datasets.isEmpty())return incoming // no numerical/source snapshot to revise; normal metadata conflict rules apply
        // Reimporting the same conflicting numerical package is idempotent too.
        if(b.products.all { p -> a.products.any { it.id==p.id } } &&
            b.datasets.all { d -> a.datasets.any { it==d || it.rawPayload?.get("imported_dataset_fingerprint")?.jsonPrimitive?.contentOrNull==datasetToken(d) } } &&
            b.models.all { m -> a.models.any { it==m || it.fitDescription?.startsWith(origin(m))==true } })
            return incoming.copy(catalogue=ComponentCatalogue(1),retiredModelIds=incoming.retiredModelIds.map { id ->
                val model=b.models.single { it.id==id }
                a.models.first { it==model || it.fitDescription?.startsWith(origin(model))==true }.id
            })
        val mapping=mutableMapOf<String,String>()
        fun newId()="user:${current.namespace}:${UUID.randomUUID()}"
        fun ref(id:String)=mapping[id] ?: id
        val products=b.products.map { p -> a.products.firstOrNull { it.id==p.id } ?: p }
        b.versions.forEach { v -> if(a.versions.any { it.id==v.id && it!=v })mapping[v.id]=newId() }
        val versions=b.versions.map { it.copy(id=ref(it.id)) }
        b.batches.forEach { v -> if(a.batches.any { it.id==v.id && it!=v } || v.versionId?.let(::ref)!=v.versionId)mapping[v.id]=newId() }
        val batches=b.batches.map { it.copy(id=ref(it.id),versionId=it.versionId?.let(::ref)) }
        b.datasets.forEach { d -> if(a.datasets.any { it.id==d.id && it!=d } ||
            d.versionId?.let(::ref)!=d.versionId || d.batchId?.let(::ref)!=d.batchId)mapping[d.id]=newId() }
        val revisionCounts=a.models.groupBy { it.productId }.mapValues { (_,ms) ->
            maxOf(ms.size,ms.maxOf { it.revision.toIntOrNull() ?: 0 }) }.toMutableMap()
        val models=b.models.map { m ->
            val changed=a.models.any { it.id==m.id && it!=m } || m.datasetIds.map(::ref)!=m.datasetIds ||
                m.versionId?.let(::ref)!=m.versionId || m.batchId?.let(::ref)!=m.batchId
            if(changed) {
                mapping[m.id]=newId()
                val revision=(revisionCounts.getOrDefault(m.productId,0)+1).also { revisionCounts[m.productId]=it }.toString()
                m.copy(id=ref(m.id),revision=revision,datasetIds=m.datasetIds.map(::ref),versionId=m.versionId?.let(::ref),
                    batchId=m.batchId?.let(::ref),fitDescription="${origin(m)}; ${m.fitDescription.orEmpty()}")
            } else m
        }
        val datasets=b.datasets.map { d -> if(d.id !in mapping)d else d.copy(id=ref(d.id),versionId=d.versionId?.let(::ref),
            batchId=d.batchId?.let(::ref),rawPayload=JsonObject(d.rawPayload.orEmpty()+buildJsonObject {
                put("import_id_mapping",buildJsonObject { mapping.toSortedMap().forEach { (old,new) -> put(old,new) } })
                put("imported_from_namespace",incoming.namespace)
                put("imported_dataset_fingerprint",datasetToken(d))
            })) }
        val sealed=models.map { m -> if(b.models.any { it==m })m else CatalogueIdentity.seal(m,datasets) }
        return incoming.copy(catalogue=b.copy(products=products,versions=versions,batches=batches,datasets=datasets,models=sealed),
            retiredModelIds=incoming.retiredModelIds.map(::ref))
    }
    /** Explicit fork rewrites only local references and reseals model identities. Bundled references stay fixed. */
    private fun fork(p: MaterialPackage): MaterialPackage {
        val namespace = UUID.randomUUID().toString(); val c = p.catalogue
        val ids = c.products.map { it.id } + c.versions.map { it.id } + c.batches.map { it.id } + c.datasets.map { it.id } + c.models.map { it.id }
        val map = ids.associateWith { "user:$namespace:${UUID.randomUUID()}" }
        fun ref(id: String) = map[id] ?: id
        val datasets = c.datasets.map { dataset -> dataset.copy(id = ref(dataset.id), productId = ref(dataset.productId),
            versionId = dataset.versionId?.let(::ref), batchId = dataset.batchId?.let(::ref),
            rawPayload = JsonObject(dataset.rawPayload.orEmpty() + buildJsonObject {
                put("imported_from_namespace",p.namespace)
                put("import_id_mapping",buildJsonObject { map.toSortedMap().forEach { (old,new) -> put(old,new) } })
            })) }
        val models = c.models.map { CatalogueIdentity.seal(it.copy(id = ref(it.id), productId = ref(it.productId),
            datasetIds = it.datasetIds.map(::ref), versionId = it.versionId?.let(::ref), batchId = it.batchId?.let(::ref)), datasets) }
        return p.copy(namespace = namespace, catalogue = c.copy(products = c.products.map { it.copy(id = ref(it.id)) },
            versions = c.versions.map { it.copy(id = ref(it.id), productId = ref(it.productId)) },
            batches = c.batches.map { it.copy(id = ref(it.id), productId = ref(it.productId), versionId = it.versionId?.let(::ref)) },
            datasets = datasets, models = models), retiredModelIds = p.retiredModelIds.map(::ref))
    }
}

/** Writer must commit atomically. Memory is updated only after validation AND successful disk write. */
class MaterialOverlayStore(val shipped: ComponentCatalogue, read: () -> String?, private val write: (String) -> Unit) {
    var value = MaterialPackage(); private set
    var readError: String? = null; private set
    init {
        try { read()?.let { value = MaterialPackages.decode(it, shipped) } }
        catch (e: Exception) { readError = "Custom library could not be read: ${e.message}. Stored file retained. Restore a valid backup package." }
    }
    fun commit(next: MaterialPackage) {
        check(readError == null) { readError!! }
        val encoded = MaterialPackages.encode(next, shipped)
        write(encoded); value = next
    }
    fun import(text: String, policy: ImportPolicy = ImportPolicy.skip_identical) =
        commit(MaterialPackages.merge(value, MaterialPackages.decode(text, shipped), shipped, policy))
    /** Explicit recovery replaces only an unreadable overlay; never runs automatically. */
    fun restoreUnreadable(text: String) {
        check(readError != null) { "Use normal import for a readable library" }
        val next = MaterialPackages.decode(text, shipped)
        write(MaterialPackages.encode(next, shipped)); value = next; readError = null
    }
    fun export() = MaterialPackages.encode(value, shipped)
    fun retire(modelId: String) {
        require(value.catalogue.models.any { it.id == modelId }) { "Only user-owned models can be retired" }
        commit(value.copy(retiredModelIds = (value.retiredModelIds + modelId).distinct()))
    }
    fun deleteDataset(id: String) {
        require(value.catalogue.datasets.any { it.id == id }) { "Dataset is not user-owned" }
        require(value.catalogue.models.none { id in it.datasetIds }) { "This dataset is referenced by an immutable model. Retire the model; its source snapshot is retained for rigs." }
        commit(value.copy(catalogue = value.catalogue.copy(datasets = value.catalogue.datasets.filterNot { it.id == id })))
    }
    fun deleteProduct(id: String) {
        val c = value.catalogue
        require(c.products.any { it.id == id }) { "Only user-owned products can be deleted" }
        require(c.datasets.none { it.productId == id } && c.models.none { it.productId == id }) {
            "Product has datasets or model snapshots. Delete unreferenced review datasets or retire its models; saved-rig revisions are protected."
        }
        commit(value.copy(catalogue = c.copy(products = c.products.filterNot { it.id == id },
            versions = c.versions.filterNot { it.productId == id }, batches = c.batches.filterNot { it.productId == id })))
    }
}
