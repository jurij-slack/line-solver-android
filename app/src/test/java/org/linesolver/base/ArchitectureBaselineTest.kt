package org.linesolver.base

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.linesolver.base.diagnostics.DiagnosticRunner
import org.linesolver.base.material.JsonMaterialRepository
import org.linesolver.base.model.*

/** Captured with the pre-Phase-1 implementation; no Python invocation or mutable oracle. */
class ArchitectureBaselineTest {
    private val repo = JsonMaterialRepository(resource("webbing_material_models_v1.json"))
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    private fun snapshot() = buildJsonObject {
        put("materials", buildJsonArray {
            for (m in repo.all().sortedBy { it.id }) add(buildJsonObject {
                put("id", m.id)
                put("fingerprint", materialFingerprint(m.metadata.toString()))
                put("previous_fingerprints", JsonArray(repo.previousRevisions(m.id).map { JsonPrimitive(materialFingerprint(it.toString())) }))
                put("mass_kg_per_m", m.density?.let(::JsonPrimitive) ?: JsonNull)
                put("mbs_N", m.mbs?.let(::JsonPrimitive) ?: JsonNull)
                put("max_strain", m.maxStrain)
                put("continuation_N", m.continuation?.let(::JsonPrimitive) ?: JsonNull)
                val knots = m.metadata.getValue("strain_fraction").jsonArray.map { it.jsonPrimitive.double }
                val strains = (listOf(-.01, 0.0, m.measuredMax * .4321, m.maxStrain,
                    (m.measuredMax + m.maxStrain) / 2) + knots).filter { it <= m.maxStrain }.distinct().sorted()
                put("values", buildJsonArray {
                    for (e in strains) add(buildJsonObject {
                        put("strain", e); put("force_N", m.tension(e)); put("tangent_N", m.tangent(e))
                        put("energy_J_per_m", m.energy(e)); put("inverse_strain", m.strainAt(m.tension(e)))
                    })
                })
            })
        })
        put("libraries", buildJsonArray {
            val sections = repo.all().sortedBy { it.id }.map { m ->
                val selection = Selection(m.manufacturer, m.id)
                SectionDraft("1", selection, selection, "5")
            }
            val config = RigConfiguration("63", "0", "0", "1", sections, kind = SetupKind.MIXED)
            for (version in 1..2) {
                val doc = RigLibraryDocument(version, listOf(SavedRig("baseline", "Every selected model", 1, config, config.fingerprints(repo))))
                val encoded = RigLibraryCodec.encode(doc)
                val restored = RigLibraryCodec.decode(encoded)
                assertEquals(config, restored.rigs.single().configuration)
                assertTrue(restored.rigs.single().materialProblems(repo).isEmpty())
                assertEquals(restored, RigLibraryCodec.decode(RigLibraryCodec.encode(restored)))
                add(buildJsonObject { put("input", encoded); put("reserialized", RigLibraryCodec.encode(restored)) })
            }
            val minimal = """{"rigs":[{"id":"old","name":"No fingerprint","modifiedAt":1,"configuration":{"span":"35","leftHeight":"0","rightHeight":"0","pretensionKn":"1.5","sections":[]}}]}"""
            add(buildJsonObject { put("input", minimal); put("reserialized", RigLibraryCodec.encode(RigLibraryCodec.decode(minimal))) })
        })
        fun select(name: String) = repo.webbings("RAED").first { it.product == name }.let { Selection(it.manufacturer, it.id) }
        val section = SectionDraft("17.5", select("Parsec"), select("MOTM"), "5")
        val input = Draft(span = "35", leftHeight = "0", rightHeight = "0", mass = "80", position = "12",
            pretensionKn = "1.5", kind = SetupKind.MIXED, sections = listOf(section, section), cutEnabled = true,
            cutPosition = "26", walkerHeight = "1.8", leash = "1.5")
        val result = DiagnosticRunner.run(input, repo)
        assertNull(result.failure)
        put("rig_states", result.report.document.getValue("states"))
        put("rig_energy_ledger", result.report.document.getValue("energy_ledger"))
    }

    @Test fun exactLegacyMaterialsRigSerializationAndPhysics() {
        val actual = snapshot()
        assertEquals(Json.parseToJsonElement(resource("phase01-baseline.json")), actual)
    }
}
