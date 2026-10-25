package org.linesolver.base

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.linesolver.base.model.*
import org.linesolver.base.material.JsonMaterialRepository
import org.linesolver.base.diagnostics.DiagnosticRunner

class PreferencesTest {
    private val materials=JsonMaterialRepository(javaClass.classLoader!!.getResource("webbing_material_models_v1.json")!!.readText())
    private fun rig():Draft {
        fun selection(product:String)=Selection("RAED",materials.webbings("RAED").first{it.product==product}.id)
        return Draft(span="35",leftHeight="0",rightHeight="0",mass="80",position="12",pretensionKn="1.5",
            sections=listOf(SectionDraft("35",selection("Parsec"),selection("MOTM"),"5")),
            cutEnabled=true,cutPosition="26",walkerHeight="1.8",leash="1.5")
    }
    private fun store(data:MutableMap<String,String>)=AppPreferences(data::get){k,v->data[k]=v}
    @Test fun freshStoreRestoresWalkerAndGlobalModelWithoutSavingRig() {
        val data=mutableMapOf<String,String>()
        val input=rig().copy(mass="83",position="13",walkerHeight="1.9",leash="1.6",gravity="9.7",elements="6",resistance="310")
        store(data).save(input)
        val restarted=store(data).restore(Draft())
        assertEquals(WalkerPreferences.from(input),WalkerPreferences.from(restarted))
        assertEquals(ModelPreferences.from(input),ModelPreferences.from(restarted))
        assertEquals("",restarted.span);assertEquals(listOf(SectionDraft()),restarted.sections)
        // Activity restoration must not overwrite newer global preferences.
        assertEquals("9.7",store(data).restore(rig()).gravity)
    }
    @Test fun legacyDraftMigrationRetainsUnitsAndPhysicalMeaning() {
        val legacy=Json.decodeFromString<Draft>(Json.encodeToString(rig().copy(gravity="9,8",elements="6")))
        val data=mutableMapOf<String,String>()
        assertEquals(legacy,store(data).restore(legacy))
        val restored=store(data).restore(legacy.copy(mass="1",gravity="1"))
        val setup=restored.validate(materials).setup!!
        assertEquals(80.0,setup.walker.massKg,0.0);assertEquals(12.0,setup.walker.referencePositionM,0.0)
        assertEquals(1.8,setup.cut!!.walkerHeightM,0.0);assertEquals(1.5,setup.cut.leashLengthM,0.0)
        assertEquals(9.8,setup.gravity,0.0);assertEquals(6,setup.elements)
    }
    @Test fun invalidWalkerEditsStayVisibleAndCannotReachSolver() {
        for(bad in listOf("","NaN","Infinity","-1")) {
            val data=mutableMapOf<String,String>();store(data).save(rig().copy(mass=bad))
            val restored=store(data).restore(rig())
            assertEquals(bad,restored.mass);assertNull(restored.validate(materials).setup)
            assertNotNull(DiagnosticRunner.run(restored,materials).failure)
        }
        assertNull(rig().copy(position="36").validate(materials).setup)
        assertNull(rig().copy(leash="0.8").validate(materials).setup)
    }
    @Test fun invalidModelSettingsCannotApplyOrReplacePersistedValues() {
        val data=mutableMapOf<String,String>();store(data).save(rig().copy(gravity="9.7"))
        val invalid=listOf(ModelPreferences(gravity="0"),ModelPreferences(gravity="NaN"),ModelPreferences(gravity="Infinity"),
            ModelPreferences(elements="1"),ModelPreferences(elements="33"),ModelPreferences(elements="8.5"),ModelPreferences(resistance="-1"))
        for(model in invalid) {
            assertTrue(model.errors().isNotEmpty())
            assertThrows(IllegalArgumentException::class.java){model.applyTo(rig())}
        }
        store(data).save(rig().copy(gravity="-1"))
        assertEquals("9.7",store(data).restore(Draft()).gravity)
    }
    @Test fun restoreDefaultsPersistsAndPreservesWalkerAndRig() {
        val data=mutableMapOf<String,String>()
        val changed=rig().copy(gravity="9.7",elements="6",resistance="330")
        store(data).save(changed)
        val reset=ModelPreferences().applyTo(changed);store(data).save(reset)
        assertEquals(ModelPreferences(),ModelPreferences.from(store(data).restore(Draft())))
        assertEquals(WalkerPreferences.from(changed),WalkerPreferences.from(reset))
        assertEquals(changed.sections,reset.sections)
    }
    @Test fun corruptStorageAndMissingFieldsKeepExistingDefaultsUsable() {
        val data=mutableMapOf("walker_v1" to "broken", "model_v1" to "broken")
        assertEquals(rig(),store(data).restore(rig()))
        assertEquals(Draft(),store(mutableMapOf()).restore(Draft()))
        val old=Json.decodeFromString<Draft>("""{"mass":"80","walkerHeight":"1.8","leash":"1.5"}""")
        assertEquals("9.81",old.gravity);assertEquals("8",old.elements)
    }
    @Test fun restoredPreferencesDrivePhysicsAndActiveDiagnosticSettings() {
        val data=mutableMapOf<String,String>()
        val input=rig().copy(gravity="9.7",elements="6",mass="83",position="13",walkerHeight="1.9",leash="1.6")
        store(data).save(input)
        val restored=store(data).restore(rig())
        val run=DiagnosticRunner.run(restored,materials);assertNull(run.failure)
        val direct=DiagnosticRunner.run(input,materials)
        assertEquals(direct.report.document.getValue("states"),run.report.document.getValue("states"))
        val ledger=run.result!!.fallEnergy!!
        assertEquals(83*9.7*(ledger.initial.walkerComYM-ledger.turning.walkerComYM),ledger.walkerGravityReleasedJ,1e-8)
        assertNotEquals(DiagnosticRunner.run(rig(),materials).result!!.standing.value.total,run.result.standing.value.total)
        val settings=run.report.document.getValue("solver_settings").jsonObject
        assertEquals("9.7",settings.getValue("gravity_m_s2_input").jsonPrimitive.content)
        assertEquals("6",settings.getValue("elements_per_section_input").jsonPrimitive.content)
        assertEquals("200",settings.getValue("contact_resistance_N_input").jsonPrimitive.content)
        assertEquals(run.report.document.getValue("states"),DiagnosticRunner.replay(run.report.text()).report.document.getValue("states"))
        // Older schema-v1 reports had these settings only inside the captured Draft.
        val oldSettings=JsonObject(settings - setOf("gravity_m_s2_input","elements_per_section_input","contact_resistance_N_input"))
        val oldReport=JsonObject(run.report.document + ("solver_settings" to oldSettings))
        assertEquals(run.report.document.getValue("states"),DiagnosticRunner.replay(oldReport.toString()).report.document.getValue("states"))
    }
}
