package org.linesolver.base.material.catalogue

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.linesolver.base.material.Material
import kotlin.math.abs

/** Versioned payload in Specifications.information; old catalogue hashes are unaffected. */
@Serializable enum class BungeeBasis { physical_cord, folded_assembly, strand_or_layer }
@Serializable data class BungeeLaw(
    val schemaVersion: Int = 1,
    val basis: BungeeBasis,
    val testedPhysicalLengthM: Double,
    val testedLegs: Int = 1,
    val scalablePerLength: Boolean = false,
    val scalingEvidence: String = "",
    val rubberLayers: Int? = null,
    val totalMassKg: Double? = null,
    val source: String,
    val idealEqualLegs: Boolean = false
) {
    fun validate() {
        require(schemaVersion == 1) { "Unsupported bungee law version" }
        require(testedPhysicalLengthM.isFinite() && testedPhysicalLengthM > 0) { "Measured physical reference length L0/m must be positive" }
        require(testedLegs in 1..16) { "Measured equal-leg count must be 1–16" }
        require(basis != BungeeBasis.physical_cord || testedLegs == 1) { "A physical-cord curve must represent an unfolded cord" }
        require(!scalablePerLength || (basis == BungeeBasis.physical_cord && scalingEvidence.isNotBlank())) { "Length scaling needs a physical-cord law and documented evidence" }
        require(rubberLayers == null || rubberLayers > 0) { "Rubber layers must be positive metadata" }
        require(totalMassKg == null || (totalMassKg.isFinite() && totalMassKg >= 0)) { "Total mass kg must be finite and nonnegative" }
        require(source.isNotBlank()) { "Measurement basis needs a source reference" }
    }
}
fun MaterialModel.bungeeLaw(): BungeeLaw? = specifications.information["bungee_law"]?.let {
    CatalogueCodec.json.decodeFromJsonElement<BungeeLaw>(it).also(BungeeLaw::validate)
}

/** Fold geometry changes force, while the physical cord's mass/energy are counted once. */
class FoldedBungeeMaterial(val rubber: Material, val physicalRestM: Double, val legs: Int,
    val law: BungeeLaw, val physicalMassKg: Double) : Material {
    val effectiveRestM = physicalRestM / legs
    private val factor = if (law.basis == BungeeBasis.folded_assembly) 1.0 else legs.toDouble()
    init {
        law.validate()
        require(physicalRestM.isFinite() && physicalRestM > 0 && legs in 1..16)
        require(physicalMassKg.isFinite() && physicalMassKg >= 0)
        require(law.basis != BungeeBasis.strand_or_layer) { "Strand/layer curves need a verified assembly conversion; enter a physical-cord measurement" }
        require(legs == 1 || law.idealEqualLegs) { "Folding requires the explicit ideal equal-leg assumption; unequal legs/friction unsupported" }
        require(law.basis != BungeeBasis.folded_assembly || legs == law.testedLegs) { "A measured folded assembly cannot be automatically converted to another leg count" }
        require(law.scalablePerLength || abs(physicalRestM-law.testedPhysicalLengthM) <= 1e-10*law.testedPhysicalLengthM) {
            "Whole-cord measurements are restricted to tested physical length ${law.testedPhysicalLengthM} m; documented per-length scaling is required"
        }
    }
    override val name get() = rubber.name
    override val density get() = physicalMassKg / effectiveRestM
    override val maxStrain get() = rubber.maxStrain
    override val measuredMax get() = rubber.measuredMax
    override val breakingStrain get() = rubber.breakingStrain
    override fun tension(strain: Double) = factor * rubber.tension(strain)
    override fun tangent(strain: Double) = factor * rubber.tangent(strain)
    override fun energy(strain: Double) = factor * rubber.energy(strain)
    override fun strainAt(force: Double) = rubber.strainAt(force / factor)
}
