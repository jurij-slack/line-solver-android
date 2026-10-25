package org.linesolver.base

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.linesolver.base.material.*

class CurveExplorerTest {
    private val repo=JsonMaterialRepository(javaClass.classLoader!!.getResource("webbing_material_models_v1.json")!!.readText())
    private fun material()=repo.all().first{it.product=="FireFox"}
    @Test fun discoveryIncludesEveryVariantAndSelectionIsIndependent() {
        assertEquals(63,repo.all().size);assertEquals(repo.models.keys,repo.all().map{it.id}.toSet())
        val original=repo.models.mapValues{it.value.metadata.toString()}
        var ids=emptyList<String>();val a=repo.all()[0].id;val b=repo.all()[1].id
        ids=toggleCurve(ids,a);assertEquals(listOf(a),selectedCurves(repo.all(),ids).map{it.id})
        ids=toggleCurve(ids,b);assertEquals(2,selectedCurves(repo.all(),ids).size)
        ids=toggleCurve(ids,a);assertEquals(listOf(b),ids)
        assertEquals(63,selectedCurves(repo.all(),repo.all().map{it.id}).size)
        assertTrue(selectedCurves(repo.all(),emptyList()).isEmpty());assertEquals(original,repo.models.mapValues{it.value.metadata.toString()})
    }
    @Test fun allPlottedValuesUseSolverForceAndRespectLimits() {
        for(m in repo.all()) {
            val p=m.curvePlot(0.0,m.maxStrain*100+1)
            assertNull(m.id,p.error)
            for(s in p.segments)for(point in s.points){
                assertTrue(point.percent<=m.maxStrain*100+1e-12)
                assertEquals(m.tension((point.percent/100).coerceAtMost(m.maxStrain))/1000,point.kn,1e-10)
            }
            assertEquals(m.tension(.001)/1000,m.plotForce(.1),0.0)
        }
    }
    @Test fun sourceKnotsAgreeWithSolverWithoutInventedOriginMarkers() {
        for(m in repo.all())for(p in m.curvePlot(0.0,m.maxStrain*100).samples)
            assertEquals(m.plotForce(p.percent),p.kn,1e-9)
        val m=material();val p=m.curvePlot(0.0,m.maxStrain*100)
        assertFalse(p.samples.any{it.percent==0.0});assertTrue(p.samples.any{it.percent==2.3 && it.kn==.5})
    }
    @Test fun imposedOriginAndUpperContinuationAreDashed() {
        val m=material();val d=m.suppliedDomain()!!
        assertEquals(.023,d.lower,0.0);assertEquals(.17,d.upper,0.0)
        val p=m.curvePlot(0.0,m.maxStrain*100)
        assertEquals(CurveRegion.EXTRAPOLATED,p.segments.first().region)
        assertTrue(p.segments.any{it.region==CurveRegion.SUPPLIED})
        assertEquals(CurveRegion.EXTRAPOLATED,p.segments.last().region)
        assertEquals(listOf(2.3,17.0),p.boundaries.map{it.percent})
    }
    @Test fun unknownRangesAndFitSamplesAreNotClaimedAsMeasured() {
        val m=material()
        val unknown=Webbing("unknown",JsonObject(m.metadata-"valid_strain_range"))
        val p=unknown.curvePlot(0.0,20.0)
        assertNull(unknown.suppliedDomain());assertTrue(p.samples.isEmpty());assertTrue(p.boundaries.isEmpty())
        assertTrue(p.segments.all{it.region==CurveRegion.UNKNOWN})
        val fit=Webbing("fit",JsonObject(m.metadata+("source_method" to JsonPrimitive("supplied fitted curve"))))
        assertTrue(fit.curvePlot(0.0,20.0).samples.isEmpty())
    }
    @Test fun rangeValidationClippingAndAbsentContinuation() {
        for((a,b) in listOf("NaN" to "2","0" to "Infinity","-1" to "3","2" to "1","" to "3"))assertNotNull(curveRangeError(a,b))
        assertNull(curveRangeError("0","2,5"))
        val m=repo.all().first{it.continuation==null && it.breakingStrain==null}
        assertEquals(m.maxStrain*100,m.curvePlot(0.0,m.maxStrain*200).segments.last().points.last().percent,1e-12)
        assertTrue(m.curvePlot(m.maxStrain*100+1,m.maxStrain*100+2).segments.isEmpty())
        assertNotNull(m.curvePlot(2.0,1.0).error)
        assertEquals(repo.all().maxOf{it.maxStrain*100},automaticCurveLimit(repo.all()),0.0)
    }
    @Test fun invalidProvenanceIsUnknownAndBrokenModelIsRejected() {
        val m=material();val broken=JsonObject(m.metadata+("valid_strain_range" to JsonArray(listOf(JsonPrimitive(-1),JsonPrimitive(2)))))
        assertNull(Webbing("invalid-range",broken).suppliedDomain())
        assertThrows(IllegalArgumentException::class.java){Webbing("units",JsonObject(m.metadata+("tension_unit" to JsonPrimitive("kN"))))}
        assertThrows(Exception::class.java){Webbing("invalid",JsonObject(m.metadata+("tension_N" to JsonArray(listOf(JsonPrimitive(0))))))}
    }
    @Test fun resultWarningsCoverBelowAboveAndUnknownSourceRange() {
        val m=material()
        assertTrue(curveUseWarnings(listOf(m to .05)).isEmpty())
        assertEquals(1,curveUseWarnings(listOf(m to .01,m to .2)).size)
        assertTrue(curveUseWarnings(listOf(m to .01)).single().contains("outside"))
        val unknown=Webbing("unknown",JsonObject(m.metadata-"valid_strain_range"))
        assertTrue(curveUseWarnings(listOf(unknown to .05)).single().contains("unknown"))
    }
}
