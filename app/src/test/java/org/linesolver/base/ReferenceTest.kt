package org.linesolver.base

import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
import org.linesolver.base.material.*
import org.linesolver.base.model.*
import org.linesolver.base.energy.*
import org.linesolver.base.contact.*
import org.linesolver.base.solver.*
import org.linesolver.base.ui.Viewport

class ReferenceTest {
    private fun resource(name:String)=javaClass.classLoader!!.getResource(name)!!.readText()
    private val repository by lazy{JsonMaterialRepository(resource("webbing_material_models_v1.json"))}
    private fun JsonObject.d(k:String)=getValue(k).jsonPrimitive.double
    private fun JsonElement.numbers()=jsonArray.map{it.jsonPrimitive.double}
    @Test fun allPythonMaterialLawsAndEnergy() {
        val historical=Json.parseToJsonElement(resource("webbing_material_models_v1.json")).jsonObject["previous_model_revisions"]!!.jsonObject
        for(entry in Json.parseToJsonElement(resource("materials.json")).jsonArray) {
            val e=entry.jsonObject;val id=e.getValue("id").jsonPrimitive.content
            val m=historical[id]?.jsonArray?.firstOrNull()?.let{Webbing(id,it.jsonObject)}?:repository.get(id)
            assertEquals(m.name,e.d("max_strain"),m.maxStrain,2e-10)
            for(value in e.getValue("values").jsonArray) {
                val v=value.jsonObject;val strain=v.d("strain")
                assertEquals(m.name,v.d("tension"),m.tension(strain),1e-5)
                assertEquals(m.name,v.d("energy"),m.energy(strain),1e-6)
                assertEquals(m.name,v.d("tangent"),m.tangent(strain),.002)
            }
            val h=m.measuredMax*1e-6;val x=m.measuredMax*.4321
            assertEquals(m.tension(x),(m.energy(x+h)-m.energy(x-h))/(2*h),.002)
            try {m.tension(m.maxStrain+1e-5);fail("Domain must not extrapolate beyond limit")}catch(_:MaterialLimit){}
        }
    }
    @Test fun linearLawAndInversion() {
        val m=LinearMaterial(500000.0,.077)
        assertEquals(1500.0,m.tension(.003),0.0);assertEquals(2.25,m.energy(.003),1e-12)
        assertEquals(.003,m.strainAt(1500.0),0.0);assertEquals(0.0,m.tension(-.1),0.0)
    }
    @Test fun pythonPhysicalPointFirstMeshes() {
        for(e in Json.parseToJsonElement(resource("meshes.json")).jsonArray) {
            val o=e.jsonObject
            assertArrayEquals(o.getValue("grid").numbers().toDoubleArray(),materialGrid(o.getValue("count").jsonPrimitive.int,o.getValue("mandatory").numbers()).toDoubleArray(),1e-15)
        }
    }
    private fun setup(e:JsonObject):LineSetup {
        val lengths=e.getValue("lengths").numbers();val materials=e.getValue("materials").jsonArray;val h=e.getValue("heights").numbers()
        return LineSetup(Geometry(e.d("span"),h[0],h[1]),lengths.mapIndexed{i,l->Section(l,materials[i].jsonArray[0].jsonPrimitive.content,materials[i].jsonArray[1].jsonPrimitive.content,e.d("extra_ratio"))},e.d("pretension"),Walker(e.d("mass"),e.d("position")),e["cut"]?.jsonPrimitive?.doubleOrNull?.let{Cut(it,1.8,1.5)},ContactOptions(),9.81,8)
    }
    @Test fun completePythonStaticAndFixedCatchComparisons()=compare("static_cases.json",true)
    @Test fun independentSupportPythonComparisons()=compare("independent_static_cases.json",false)
    private fun compare(file:String,historical:Boolean) {
        for(item in Json.parseToJsonElement(resource(file)).jsonArray) {
            val e=item.jsonObject;val name=e.getValue("name").jsonPrimitive.content
            val result=SimpleSolver(repository,networkBuilder=if(historical)::historicalSharedRing else ::buildNetwork).solve(setup(e))
            assertArrayEquals(name,e.getValue("installed_lengths").numbers().toDoubleArray(),result.installedLengths.toDoubleArray(),1e-8)
            assertEquals(name,e.d("achieved_pretension"),result.achievedPretensionN,1e-4)
            for((key,state) in listOf("standing" to result.standing,"catch" to result.catch,"rest" to result.rest)) if(state!=null) {
                val oracle=e.getValue(key).jsonObject
                assertArrayEquals("$name/$key positions",oracle.getValue("coordinates").jsonArray.flatMap{it.numbers()}.toDoubleArray(),state.r,2e-4)
                assertArrayEquals("$name/$key tension",oracle.getValue("tensions").numbers().toDoubleArray(),state.value.tensions,.02)
                assertArrayEquals(oracle.getValue("rest_lengths").numbers().toDoubleArray(),state.network.elements.map{it.rest}.toDoubleArray(),1e-8)
                assertArrayEquals(oracle.getValue("masses").numbers().toDoubleArray(),state.network.elements.map{it.mass}.toDoubleArray(),1e-9)
                // Historical Python standing network uses a foot-level point mass;
                // its catch adds the COM constant separately. Phase 1 reports that
                // same constant in the standing state itself, without changing forces.
                val standingCom=if(key=="standing" && result.catch!=null)setup(e).walker.massKg*9.81*.55*1.8 else 0.0
                assertEquals("$name/$key energy",oracle.d("total")+standingCom,state.value.total,.002)
                assertTrue(state.residualN<2e-6)
            }
            if(result.catch!=null) {
                assertEquals(e.d("initial_energy"),result.initialEnergyJ!!,.002)
                assertEquals(0.0,result.catchResidualJ!!,1e-5)
                assertEquals(e.d("relaxation_loss"),result.relaxationLossJ!!,.002)
                for(kind in Cable.entries) {
                    val before=result.standing.network.elements.filter{it.cable==kind};val after=result.catch.network.elements.filter{it.cable==kind}
                    assertEquals(before.sumOf{it.rest},after.sumOf{it.rest},1e-10);assertEquals(before.sumOf{it.mass},after.sumOf{it.mass},1e-10)
                }
            }
        }
    }
    @Test fun emptyAndInvalidFormsNeverInventRigInputs() {
        val d=Draft();assertNull(d.validate(repository).setup)
        assertEquals("",d.mass);assertEquals("",d.pretensionKn);assertEquals("",d.sections[0].main.manufacturer)
        assertEquals("",d.sections[0].backup.materialId);assertEquals("",d.sections[0].extra)
        assertNull(d.copy(span="NaN",mass="-1").validate(repository).setup)
        assertEquals(d.sections,d.copy(mode=Mode.ADVANCED).sections)
    }
    @Test fun catalogueNeutralAndViewportIsIsotropic() {
        assertEquals(repository.manufacturers().sortedWith(String.CASE_INSENSITIVE_ORDER),repository.manufacturers());assertTrue(repository.webbings("").isEmpty())
        val v=Viewport.fit(listOf(Point(0.0,-10.0),Point(100.0,2.0)),400.0,200.0,20.0)
        assertEquals(v.map(Point(1.0,0.0)).x-v.map(Point(0.0,0.0)).x,v.map(Point(0.0,0.0)).y-v.map(Point(0.0,1.0)).y,1e-12)
        assertEquals(12.0,v.inverse(v.map(Point(12.0,-3.0))).x,1e-12)
    }
    @Test fun explicitUnitsSerializationAndRejectedOverflow() {
        val main=repository.webbings("RAED").first{it.product=="Parsec"}
        val backup=repository.webbings("RAED").first{it.product=="MOTM"}
        val section=SectionDraft("35",Selection("RAED",main.id),Selection("RAED",backup.id),"5")
        val draft=Draft(span="35",leftHeight="0",rightHeight="0",mass="80",position="12",pretensionKn="1.5",sections=listOf(section))
        val setup=draft.validate(repository).setup!!
        assertEquals(1500.0,setup.pretensionN,0.0)
        val metres=draft.copy(sections=listOf(section.copy(extra="1.75",extraUnit=ExtraUnit.METRES))).validate(repository).setup!!
        assertEquals(setup.sections,metres.sections)
        assertEquals(section,section.withExtraUnit(ExtraUnit.PERCENT))
        assertEquals(1.75,section.withExtraUnit(ExtraUnit.METRES).extra.number()!!,0.0)
        assertEquals(5.0,section.withExtraUnit(ExtraUnit.METRES).withExtraUnit(ExtraUnit.PERCENT).extra.number()!!,0.0)
        assertEquals(draft,Json.decodeFromString<Draft>(Json.encodeToString(draft)))
        assertNull(draft.copy(sections=emptyList()).validate(repository).setup)
        assertNull(draft.copy(pretensionKn="1e308").validate(repository).setup)
        assertNull(draft.copy(sections=listOf(section.copy(extra="1e308",length="1e308"))).validate(repository).setup)
        assertEquals(ConnectionBehavior.STOP,section.connection)
    }
}

/** Test-only reconstruction of historical Python common-ring topology. Not an app option. */
internal fun historicalSharedRing(s:LineSetup,m:MaterialRepository,lengths:List<Double>,loaded:Boolean):Network {
    val n=buildNetwork(s,m,lengths,loaded);val a=n.attachment?:return n
    if(a.mainNode==a.backupNode)return n
    check(a.backupNode==n.coordinates.size/2-1)
    return n.copy(coordinates=n.coordinates.dropLast(2).toDoubleArray(),
        elements=n.elements.map{e->e.copy(a=if(e.a==a.backupNode)a.mainNode else e.a,b=if(e.b==a.backupNode)a.mainNode else e.b)},
        attachment=a.copy(backupNode=a.mainNode))
}
