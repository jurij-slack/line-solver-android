package org.linesolver.base

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.linesolver.base.material.*
import kotlin.math.*

class MaterialAuditTest {
    private fun resource(name:String)=javaClass.classLoader!!.getResource(name)!!.readText()
    private val repo=JsonMaterialRepository(resource("webbing_material_models_v1.json"))
    private fun modified(vararg pairs:Pair<String,JsonElement>,remove:List<String> = emptyList()):Webbing {
        val base=repo.all().first{it.product=="FireFox"}.metadata
        return Webbing("fixture",JsonObject(base-remove+pairs.toMap()))
    }
    private fun arr(vararg values:Double)=JsonArray(values.map(::JsonPrimitive))
    @Test fun completeInventoryAndFreshPythonOracleAgree() {
        val raw=Json.parseToJsonElement(resource("webbing_material_models_v1.json")).jsonObject.getValue("models").jsonObject
        assertEquals(raw.keys,repo.all().map{it.id}.toSet());assertEquals(63,repo.all().size)
        assertEquals(0,repo.all().count{it.density==null});assertEquals(1,repo.all().count{it.mbs==null})
        assertEquals(61,repo.all().count{it.continuation!=null})
        val oracle=Json.parseToJsonElement(resource("material_audit_oracle.json")).jsonArray
        assertEquals(raw.keys,oracle.map{it.jsonObject.getValue("id").jsonPrimitive.content}.toSet())
        for(entry in oracle) {
            val o=entry.jsonObject;val m=repo.get(o.getValue("id").jsonPrimitive.content)
            assertEquals(m.id,o.getValue("max_strain").jsonPrimitive.double,m.maxStrain,2e-10)
            for(v in o.getValue("values").jsonArray) {
                val a=v.jsonObject;val e=a.getValue("strain").jsonPrimitive.double
                assertEquals(m.id,a.getValue("tension").jsonPrimitive.double,m.tension(e),1e-5)
                assertEquals(m.id,a.getValue("energy").jsonPrimitive.double,m.energy(e),1e-6)
                assertEquals(m.id,a.getValue("tangent").jsonPrimitive.double,m.tangent(e),.002)
                assertEquals(m.id,m.tension(e)/1000,m.plotForce(e*100),1e-7)
            }
        }
    }
    @Test fun sourceOriginsAreNotMeasuredAndUnitsMatch() {
        for(m in repo.all()) {
            assertTrue(m.id,m.hasImposedOrigin)
            val x=m.metadata.getValue("strain_fraction").jsonArray;val y=m.metadata.getValue("tension_N").jsonArray
            assertEquals(x[1].jsonPrimitive.double,m.suppliedDomain()!!.lower,0.0)
            val plot=m.curvePlot(0.0,m.maxStrain*100)
            assertNull(m.id,plot.error);assertFalse(plot.samples.any{it.percent==0.0})
            assertEquals(CurveRegion.EXTRAPOLATED,plot.segments.first().region)
            for(i in x.indices)if(x[i].jsonPrimitive.double<=m.maxStrain)assertEquals(m.id,y[i].jsonPrimitive.double,m.tension(x[i].jsonPrimitive.double),1e-7)
            assertEquals("N",m.metadata.getValue("tension_unit").jsonPrimitive.content)
            assertEquals(0.0,m.tension(-.1),0.0);assertEquals(0.0,m.energy(-.1),0.0)
        }
    }
    @Test fun continuationForceAndEnergyAreContinuousAndDerivativeConsistent() {
        for(m in repo.all()) {
            val end=m.measuredMax
            if(m.continuation!=null) {
                val h=min(1e-8,(m.maxStrain-end)*1e-5)
                assertEquals(m.tension(end),m.tension(end+h),m.continuation!!*h*1.01+1e-7)
                assertEquals(m.energy(end),m.energy(end+h),m.tension(end+h)*h*1.01+1e-7)
                for(e in listOf(end*.41,end,end+(m.maxStrain-end)*.5))assertEquals(m.id,m.tension(e),(m.energy(e+h)-m.energy(e-h))/(2*h),.05)
                assertEquals(m.mbs!!,m.tension(m.maxStrain),1e-7)
            }
            assertThrows(MaterialLimit::class.java){m.tension(m.maxStrain+1e-6)}
            assertThrows(MaterialLimit::class.java){m.energy(m.maxStrain+1e-6)}
            assertThrows(MaterialLimit::class.java){m.plotForce(m.maxStrain*100+.001)}
        }
    }
    @Test fun sparseAndDocumentedEndpointsRespectPythonConventions() {
        val pair=arrayOf("strain_fraction" to arr(0.0,.1),"tension_N" to arr(0.0,1000.0))
        val sparse=modified(*pair);assertNull(sparse.continuation);assertEquals(.1,sparse.maxStrain,0.0)
        assertThrows(MaterialLimit::class.java){sparse.tension(.11)}
        val known=modified(*pair,"breaking_strain" to JsonPrimitive(.5),"breaking_strain_source" to JsonPrimitive("synthetic test fixture"))
        assertEquals(.5,known.maxStrain,0.0);assertEquals(26000.0,known.tension(.5),1e-9)
        assertEquals(known.tension(.3),(known.energy(.300001)-known.energy(.299999))/.000002,1e-5)
        assertNull(modified(remove=listOf("breaking_strength_N")).continuation)
        val inside=modified("breaking_strength_N" to JsonPrimitive(5000));assertNull(inside.continuation);assertEquals(.09,inside.maxStrain,1e-12)
    }
    @Test fun malformedOrContradictoryInputsAreRejectedWithoutInventingValues() {
        for(key in listOf("mass_per_m","breaking_strength_N","breaking_strain"))for(v in listOf(JsonPrimitive("NaN"),JsonPrimitive(-1),JsonPrimitive("unknown")))
            assertThrows(IllegalArgumentException::class.java){modified(key to v)}
        assertThrows(IllegalArgumentException::class.java){modified("breaking_strength_N" to JsonPrimitive(5000),"breaking_strain" to JsonPrimitive(.2),"breaking_strain_source" to JsonPrimitive("test"))}
        assertThrows(IllegalArgumentException::class.java){modified("breaking_strain" to JsonPrimitive(.5))}
        for((key,value) in listOf("tension_unit" to "kN","independent_variable" to "percent","interpolation" to "linear"))assertThrows(IllegalArgumentException::class.java){modified(key to JsonPrimitive(value))}
        assertThrows(IllegalArgumentException::class.java){modified("strain_fraction" to arr(0.0,.1,.1),"tension_N" to arr(0.0,1000.0,2000.0))}
        assertThrows(IllegalArgumentException::class.java){modified("strain_fraction" to arr(0.0,.1,.2),"tension_N" to arr(0.0,2000.0,1000.0))}
        assertNull(modified(remove=listOf("mass_per_m")).density)
    }
}
