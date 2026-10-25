package org.linesolver.base

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.linesolver.base.diagnostics.*
import org.linesolver.base.material.*
import org.linesolver.base.model.*
import org.linesolver.base.solver.*
import org.linesolver.base.ui.*
import org.linesolver.base.energy.*
import java.io.ByteArrayOutputStream
import java.io.IOException

class DiagnosticsTest {
    private val materials=JsonMaterialRepository(javaClass.classLoader!!.getResource("webbing_material_models_v1.json")!!.readText())
    private fun draft():Draft {
        fun selection(product:String)=Selection("RAED",materials.webbings("RAED").first{it.product==product}.id)
        return Draft(span="35",leftHeight="0",rightHeight="0",mass="80",position="12",pretensionKn="1.5",
            sections=listOf(SectionDraft("35",selection("Parsec"),selection("MOTM"),"5")),
            cutEnabled=true,cutPosition="26",walkerHeight="1.8",leash="1.5")
    }
    @Test fun successPreservesInputsMaterialLawsEnergyAndReplay() {
        val input=draft().copy(kind=SetupKind.MIXED,gravity="9.80665")
        val run=DiagnosticRunner.run(input,materials)
        assertNull(run.failure);assertEquals("SUCCESS",run.report.status)
        assertEquals("catch",run.report.withDisplayedState("catch").document.getValue("visualization_state").jsonPrimitive.content)
        val json=Json.parseToJsonElement(run.report.text()).jsonObject
        assertEquals(Json.encodeToJsonElement(Draft.serializer(),input),Json.encodeToJsonElement(Draft.serializer(),
            Json.decodeFromJsonElement(Draft.serializer(),json.getValue("rig_configuration").jsonObject.getValue("draft"))))
        val restored=JsonMaterialRepository(json.getValue("material_data").toString())
        for(id in input.sections.flatMap{listOf(it.main.materialId,it.backup.materialId)}) {
            val a=materials.get(id);val b=restored.get(id)
            assertEquals(Json.encodeToString(JsonObject.serializer(),a.metadata),Json.encodeToString(JsonObject.serializer(),b.metadata))
            for(e in listOf(0.0,a.measuredMax*.5,(a.measuredMax+a.maxStrain)*.5)) {
                assertEquals(a.tension(e),b.tension(e),0.0);assertEquals(a.energy(e),b.energy(e),0.0)
            }
        }
        assertEquals(run.result!!.fallEnergy!!.walkerGravityReleasedJ,json.getValue("energy_ledger").jsonObject.getValue("walker_gravity_released_J").jsonPrimitive.double,0.0)
        System.getProperty("diagnosticFixtures")?.let{java.io.File(it,"success.json").writeText(run.report.text())}
        val replay=DiagnosticRunner.replay(run.report.text())
        assertNull(replay.failure)
        assertEquals(run.report.document.getValue("states"),replay.report.document.getValue("states"))
        assertEquals(run.report.document.getValue("energy_ledger"),replay.report.document.getValue("energy_ledger"))
        assertTrue(run.report.text().length<2_000_000)
    }
    @Test fun nonConvergenceHasStageHistoryAndReproduces() {
        val run=DiagnosticRunner.run(draft(),materials,SolverSettings(maxIterations=1))
        System.getProperty("diagnosticFixtures")?.let{java.io.File(it,"nonconvergence.json").writeText(run.report.text())}
        assertEquals(FailureCategory.NON_CONVERGENCE,failureCategory(run.failure!!))
        val numerical=run.report.document.getValue("numerical_diagnostics").jsonObject
        assertEquals("installation",numerical.getValue("stage").jsonPrimitive.content)
        assertEquals("unavailable",run.report.withDisplayedState("catch").document.getValue("visualization_state").jsonPrimitive.content)
        assertTrue(numerical.getValue("history").jsonArray.isNotEmpty())
        assertTrue(numerical.getValue("last_iterate_not_necessarily_converged") is JsonObject)
        assertEquals(failureCategory(run.failure),failureCategory(DiagnosticRunner.replay(run.report.text()).failure!!))
    }
    @Test fun catchBracketFailureRetainsStandingAndBounds() {
        val run=DiagnosticRunner.run(draft(),materials,SolverSettings(maxCatchSteps=1))
        assertEquals(FailureCategory.ROOT_FINDING,failureCategory(run.failure!!))
        assertTrue(run.report.document.getValue("states").jsonObject.containsKey("standing"))
        assertTrue(run.report.document.getValue("numerical_diagnostics").jsonObject.getValue("catch_drop_bracket_m") is JsonArray)
        assertEquals(FailureCategory.ROOT_FINDING,failureCategory(DiagnosticRunner.replay(run.report.text()).failure!!))
    }
    @Test fun invalidAndNonFiniteInputsAreValidJsonAndReplayable() {
        for(value in listOf("NaN","Infinity","-Infinity","-1")) {
            val run=DiagnosticRunner.run(draft().copy(mass=value),materials)
            assertEquals(FailureCategory.INVALID_CONFIGURATION,failureCategory(run.failure!!))
            assertEquals("FAILED",DiagnosticRunner.replay(run.report.text()).report.status)
        }
        val run=DiagnosticRunner.run(draft(),materials,SolverSettings(forceToleranceN=Double.NaN))
        assertEquals("NaN",Json.parseToJsonElement(run.report.text()).jsonObject.getValue("solver_settings").jsonObject.getValue("forceToleranceN").jsonPrimitive.content)
        assertEquals("FAILED",DiagnosticRunner.replay(run.report.text()).report.status)
        val raw=diagnosticJson(listOf(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY))
        assertEquals("[\"NaN\",\"Infinity\",\"-Infinity\"]",raw.toString())
    }
    @Test fun reportSurvivesInvalidTrialStateAndSanitizesException() {
        val result=DiagnosticRunner.run(draft(),materials).result!!
        val trace=SolverTrace();trace.stage="standing"
        val state=result.standing
        val invalid=state.value.copy(elastic=Double.NaN,strains=DoubleArray(state.value.strains.size){Double.NaN})
        trace.trial(state.network,state.r,invalid,3,Double.POSITIVE_INFINITY)
        val report=DiagnosticRunner.report(draft(),materials,SolverSettings(),trace,null,SolverWarning("Bad value /home/person/private.txt",FailureCategory.NON_FINITE))
        assertFalse(report.text().contains("/home/person"));Json.parseToJsonElement(report.text())
        assertEquals("NON_FINITE",report.document.getValue("failure").jsonObject.getValue("category").jsonPrimitive.content)
    }
    @Test fun actualNonFiniteAndMaterialEvaluationFailuresAreDistinguished() {
        val state=DiagnosticRunner.run(draft(),materials).result!!.standing
        val nonFinite=runCatching{Equilibrium().solve(state.network.copy(gravity=Double.POSITIVE_INFINITY),state.r)}.exceptionOrNull() as Exception
        assertEquals(FailureCategory.NON_FINITE,failureCategory(nonFinite))
        val bad=object:Material by state.network.elements.first().material {
            override fun energy(strain:Double):Double=throw IllegalStateException("bad curve")
        }
        val elements=state.network.elements.toMutableList();elements[0]=elements[0].copy(material=bad)
        val evaluation=runCatching{state.network.copy(elements=elements).evaluate(state.r)}.exceptionOrNull() as Exception
        assertEquals(FailureCategory.MATERIAL_EVALUATION,failureCategory(evaluation))
        val stretched=state.r.map{it*100}.toDoubleArray()
        val limit=runCatching{state.network.evaluate(stretched)}.exceptionOrNull() as Exception
        assertEquals(FailureCategory.MATERIAL_LIMIT,failureCategory(limit))
    }
    @Test fun categoriesDoNotConfuseMaterialLimitsWithNumericalFailure() {
        assertEquals(FailureCategory.MATERIAL_LIMIT,failureCategory(MaterialLimit("test",.5,true)))
        assertEquals(FailureCategory.ENERGY_CONSISTENCY,failureCategory(SolverWarning("energy",FailureCategory.ENERGY_CONSISTENCY)))
        assertEquals(FailureCategory.UNEXPECTED_EXCEPTION,failureCategory(IllegalStateException("bug")))
    }
    @Test fun backupThreadsRingAndFullSpanBodyUsesSingleScale() {
        val result=DiagnosticRunner.run(draft(),materials).result!!
        assertTrue(result.standing.backupThreading(.2).isNotEmpty())
        for(state in listOf(result.catch!!,result.rest!!)) {
            val body=state.walkerDrawing()!!
            val threading=state.backupThreading(.2)
            assertEquals(2,threading.size)
            for((a,b) in threading) {
                assertEquals(state.attachmentPosition,a)
                val n=state.network.attachment!!.backupNode
                assertTrue(state.network.elements.filter{it.cable==Cable.BACKUP && (it.a==n || it.b==n)}.any{e->
                    val other=if(e.a==n)e.b else e.a
                    kotlin.math.abs((b.x-a.x)*(state.r[2*other+1]-a.y)-(b.y-a.y)*(state.r[2*other]-a.x))<1e-10
                })
            }
            val points=state.r.toList().chunked(2).map{Point(it[0],it[1])}+body.bounds
            val view=Viewport.fit(points,400.0,220.0,48.0)
            for(p in points) {val q=view.map(p);assertTrue(q.x in 48.0..352.000001);assertTrue(q.y in 47.999999..172.000001)}
            assertEquals(1.5*view.scale,view.map(body.harness).y-view.map(body.attachment).y,1e-10)
            assertEquals(state.walkerElevations!!.comM,body.com.y,0.0)
            assertTrue(body.harness.y<body.attachment.y)
        }
    }
    @Test fun exportClosesStreamAndHandlesProviderFailures() {
        var closed=false
        val output=object:ByteArrayOutputStream(){override fun close(){closed=true;super.close()}}
        assertTrue(writeDebugReport("{\"test\":1}"){output}.isSuccess);assertTrue(closed)
        assertEquals("{\"test\":1}",output.toString("UTF-8"))
        assertTrue(writeDebugReport("{}"){null}.isFailure)
        assertTrue(writeDebugReport("{}"){throw SecurityException("denied")}.isFailure)
        assertTrue(writeDebugReport("{}"){object:java.io.OutputStream(){override fun write(b:Int){throw IOException("disk full")}}}.isFailure)
        assertTrue(writeDebugReport("{}"){object:ByteArrayOutputStream(){override fun close(){throw IOException("close failed")}}}.isFailure)
    }
}
