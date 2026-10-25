package org.linesolver.base.model

import kotlinx.serialization.Serializable
import org.linesolver.base.energy.Cable
import org.linesolver.base.material.catalogue.*
import org.linesolver.base.material.MaterialRepository

@Serializable enum class BungeePlacement { series_before, series_right, parallel_section }
@Serializable data class InstalledBungee(
    val id: String,
    val model: ModelReference,
    val physicalRestM: Double,
    val equalLegs: Int = 1,
    val physicalCordCount: Int = 1,
    val limiterPhysicalLengthM: Double? = null,
    val limiterEnabled: Boolean = true,
    val source: String,
    val uncertainty: String = ""
)
/** A bank is a physical pair of nodes; its independent assemblies share those nodes. */
@Serializable data class BungeeBank(
    val id: String,
    val placement: BungeePlacement,
    val sectionId: String? = null,
    val cable: Cable = Cable.MAIN,
    val assemblies: List<InstalledBungee>
)
@Serializable data class BungeeLayout(val schemaVersion: Int = 1, val banks: List<BungeeBank> = emptyList()) {
    fun errors(sections: List<SectionDraft>, materials: MaterialRepository): List<String> = buildList {
        if(schemaVersion != 1) add("Unsupported bungee layout version")
        val ids = banks.map { it.id } + banks.flatMap { it.assemblies.map { a -> a.id } }
        if(ids.any { it.isBlank() } || ids.distinct().size != ids.size) add("Bungee banks/instances need unique stable IDs")
        if(banks.size > 32 || banks.sumOf { it.assemblies.size } > 64) add("Static mobile bungee limit: 32 banks / 64 physical assemblies")
        for(bank in banks) {
            if(bank.assemblies.isEmpty()) add("${bank.id}: a bank needs at least one assembly")
            if(bank.placement != BungeePlacement.series_right && sections.count { it.id == bank.sectionId } != 1)
                add("${bank.id}: referenced section is missing; move or remove this bank")
            for(a in bank.assemblies) runCatching { a.material(materials) }.exceptionOrNull()?.let { add("${a.id}: ${it.message}") }
        }
    }
}
fun InstalledBungee.material(materials: MaterialRepository): FoldedBungeeMaterial {
    val repository = materials as? CatalogueMaterialRepository ?: error("Bungees require the versioned material catalogue")
    val record = CatalogueSelection.resolve(repository.document, model)
    require(repository.document.products.single { it.id == record.productId }.category == ProductCategory.bungee) { "Choose a bungee model, not a webbing law" }
    val law = record.bungeeLaw() ?: error("Bungee curve lacks explicit measurement basis/reference length")
    require(source.isNotBlank()) { "Installed lengths require a source/measurement reference" }
    require(physicalCordCount == 1) { "Configure distinct physical bungees as independent parallel assemblies; strand multiplication unsupported" }
    require(physicalRestM.isFinite() && physicalRestM > 0 && equalLegs in 1..16) { "Physical L0/m must be positive; equal legs must be 1–16" }
    limiterPhysicalLengthM?.let {
        require(it.isFinite() && it >= physicalRestM) { "Limiter total physical length must be finite and at least physical rest length" }
    }
    val mass = if(law.scalablePerLength) record.specifications.densityKgPerM?.times(physicalRestM)
        ?: error("Scalable bungee requires sourced reference linear mass kg/m")
        else law.totalMassKg ?: record.specifications.densityKgPerM?.times(physicalRestM)
        ?: error("Physical mass is unknown; gravity calculation is unavailable")
    return FoldedBungeeMaterial(repository.resolve(model), physicalRestM, equalLegs, law, mass)
}
