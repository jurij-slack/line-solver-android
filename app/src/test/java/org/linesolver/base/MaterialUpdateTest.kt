package org.linesolver.base

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.linesolver.base.material.*
import org.linesolver.base.model.*
import kotlin.math.abs

class MaterialUpdateTest {
    private val repo=JsonMaterialRepository(javaClass.classLoader!!.getResource("webbing_material_models_v1.json")!!.readText())
    private fun product(name:String)=repo.all().first{it.product==name}
    @Test fun provisionalLawsAreBoundedAnalyticalAndClearlyMarked() {
        val models=repo.all().filter{it.provisionalLinear}
        assertEquals(setOf("Jumanji","Sigma Impact Low Stretch","Sigma ReBL","Y.a.W. 2.0"),models.map{it.product}.toSet())
        for(m in models) {
            val k=m.tangent(0.0)
            assertEquals("Linear approximation — insufficient force–elongation data.",m.modelWarning)
            assertEquals(m.mbs!!/k,m.maxStrain,1e-12)
            for(i in 0..20) {
                val e=if(i==20)m.maxStrain else m.maxStrain*i/20
                assertEquals(k*e,m.tension(e),1e-8)
                assertEquals(.5*k*e*e,m.energy(e),1e-8)
                assertEquals(k,m.tangent(e),1e-8)
                assertEquals(e,m.strainAt(m.tension(e)),1e-12)
                assertEquals(m.tension(e)/1000,m.plotForce(e*100),1e-8)
            }
            val plot=m.curvePlot(0.0,m.maxStrain*100)
            assertNull(plot.error);assertEquals(1,plot.samples.size);assertEquals(1,plot.boundaries.size)
            assertTrue(plot.segments.all{it.region==CurveRegion.EXTRAPOLATED})
            assertEquals(CurveRegion.SUPPLIED,m.curveRegion(m.measuredMax))
            assertTrue(curveUseWarnings(listOf(m to m.measuredMax)).single().contains(m.modelWarning!!))
            assertThrows(MaterialLimit::class.java){m.tension(m.maxStrain+1e-8)}
            assertThrows(IllegalArgumentException::class.java){m.strainAt(m.mbs!!+1)}
        }
    }
    @Test fun provisionalCannotInventSlopeOrUseContradictoryEndpoint() {
        val original=product("Jumanji")
        fun modified(vararg pairs:Pair<String,JsonElement>)=Webbing("fixture",JsonObject(original.metadata+pairs.toMap()))
        assertThrows(IllegalArgumentException::class.java){modified("strain_fraction" to JsonArray(listOf(JsonPrimitive(0.0))))}
        assertThrows(IllegalArgumentException::class.java){modified("breaking_strain" to JsonPrimitive(.5),"breaking_strain_source" to JsonPrimitive("test"))}
        val unknown=Webbing("fixture",JsonObject(original.metadata-"breaking_strength_N"))
        assertEquals(.029,unknown.maxStrain,0.0);assertNull(unknown.continuation)
        assertThrows(MaterialLimit::class.java){unknown.tension(.03)}
    }
    @Test fun dyneemiteProHasActualContinuousStrengthBoundedContinuation() {
        val m=product("Dyneemite PRO");assertEquals(.022,m.density!!,0.0);assertEquals(34000.0,m.mbs!!,0.0)
        val k=m.continuation!!;val end=m.measuredMax;val force=m.tension(end)
        assertEquals(end+(34000-force)/k,m.maxStrain,1e-12)
        assertEquals(34000.0,m.tension(m.maxStrain),1e-8)
        val e=(end+m.maxStrain)/2;val z=e-end
        assertEquals(m.energy(end)+force*z+.5*k*z*z,m.energy(e),1e-8)
        assertEquals(end+(30000-force)/k,m.strainAt(30000.0),1e-12)
        assertEquals(CurveRegion.EXTRAPOLATED,m.curveRegion(e))
        assertTrue(m.curvePlot(0.0,m.maxStrain*100).segments.any{it.region==CurveRegion.EXTRAPOLATED && it.points.first().percent>=end*100})
        assertThrows(MaterialLimit::class.java){m.energy(m.maxStrain+1e-8)}
    }
    @Test fun updatedMassesAndQualifiedStrengthsAreNotSubstituted() {
        assertEquals(.060,product("Plasma").density!!,0.0);assertEquals(.054,product("Rodeo").density!!,0.0)
        val j=product("Jumanji");assertEquals(.031,j.density!!,0.0);assertEquals(10000.0,j.tension(.029),1e-8)
        assertTrue(j.metadata.getValue("strength_tests").jsonArray.any{it.jsonObject["compatible"]?.jsonPrimitive?.booleanOrNull==false})
        val spider=product("Spider Silk MK5");assertNull(spider.mbs)
        assertEquals(33790.0,spider.metadata.getValue("researched_batch_specifications").jsonArray[0].jsonObject.getValue("breaking_strength_N").jsonPrimitive.double,0.0)
    }
    @Test fun knownSavedRevisionsRequireReviewAndUnknownRevisionsStayBlocked() {
        val m=product("Dyneemite PRO");val old=repo.previousRevisions(m.id).single()
        val selection=Selection(m.manufacturer,m.id)
        val d=Draft(sections=listOf(SectionDraft(main=selection,backup=selection)))
        val config=RigConfiguration.from(d)
        val entry=SavedRig("old","Old rig",1,config,mapOf(m.id to materialFingerprint(old.toString())))
        assertTrue(entry.materialProblems(repo).isEmpty())
        assertEquals(1,entry.materialRevisionWarnings(repo).size)
        assertEquals(config,entry.configuration)
        val unknown=entry.copy(materialFingerprints=mapOf(m.id to "unknown"))
        assertTrue(unknown.materialProblems(repo).isNotEmpty())
        assertTrue(unknown.materialRevisionWarnings(repo).isEmpty())
        val current=entry.copy(materialFingerprints=config.fingerprints(repo))
        assertTrue(current.materialProblems(repo).isEmpty());assertTrue(current.materialRevisionWarnings(repo).isEmpty())
        assertEquals(d,config.applyTo(d))
    }
}
