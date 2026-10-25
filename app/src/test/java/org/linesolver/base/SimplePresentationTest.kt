package org.linesolver.base

import org.junit.Assert.*
import org.junit.Test
import org.linesolver.base.ui.*
import org.linesolver.base.model.*
import org.linesolver.base.material.*
import org.linesolver.base.energy.Point
import kotlinx.serialization.json.JsonObject
import java.util.Locale

class SimplePresentationTest {
    @Test fun displayUnitsRoundArtifactsWithoutLocaleOrNegativeZero() {
        val old=Locale.getDefault()
        try { Locale.setDefault(Locale.GERMANY)
            assertEquals("5.88 kN",forceKn(5880.123456))
            assertEquals("24 kJ",energyKj(24000.0))
            assertEquals("17.6 m",lengthM(17.600000000002))
            assertEquals("3.21% and 1.59%",displayWarning("3.2076000000000002% and 1.5928999999999998%"))
            assertEquals("0",uiNumber(-0.00000001));assertEquals("Unknown",uiNumber(Double.NaN))
            assertEquals("revision 1.234567",displayWarning("revision 1.234567"))
        } finally { Locale.setDefault(old) }
    }
    private fun joint()=StructuralConnections.labeled(Draft(span="50",uniform=UniformGenerator(count="2",extra="50"))).single{it.label=="C1"}.joint
    @Test fun simpleAllowsOnlyIndependentSingleItemsAndPreservesUnsupportedAssembly() {
        val j=joint();assertTrue(simpleConnectionEditable(j))
        val spec=ConnectorLibrary.shipped.products.single{it.id=="bc_4mm_soft_single_wrap"}
        val main=ConnectorLibrary.item(spec,"main").copy(role=HardwareRole.main)
        val backup=main.copy(id="backup",role=HardwareRole.backup)
        val simple=j.copy(details=JointDetails(hardware=listOf(main,backup)))
        assertTrue(simpleConnectionEditable(simple))
        assertTrue(simpleHardwareSummary(main).contains("12.8 g"))
        assertTrue(connectorName(ConnectorLibrary.shipped.products.single{it.id=="bc_4mm_soft_double_wrap"}).contains("double wrap"))
        assertTrue(simpleHardwareSummary(null).startsWith("Idealized"))
        val variants=listOf(
            simple.copy(details=simple.details.copy(hardware=listOf(main,main.copy(id="second")))),
            simple.copy(details=simple.details.copy(hardware=listOf(main.copy(role=HardwareRole.shared)))),
            simple.copy(details=simple.details.copy(hardware=listOf(main.copy(configuration="Keep note")))),
            simple.copy(details=simple.details.copy(hardware=listOf(main.copy(standingLoad=LoadAssumption(HardwareLoad.unloaded))))),
            simple.copy(details=simple.details.copy(hardware=listOf(HardwareItem("unresolved")))),
            simple.copy(details=simple.details.copy(hardware=listOf(HardwareItem("unknown",productId="unavailable",role=HardwareRole.main)))),
            simple.copy(details=simple.details.copy(typeX=TypeXSeam())))
        variants.forEach { original->val before=original.copy();assertFalse(simpleConnectionEditable(original));assertEquals(before,original) }
    }
    @Test fun warningSummaryGroupsActualOperatingRangesByCurveAndKeepsSourceLimitations() {
        val repo=JsonMaterialRepository(javaClass.classLoader!!.getResource("webbing_material_models_v1.json")!!.readText())
        val m=repo.all().first{it.product=="FireFox"}
        val second=Webbing("second",JsonObject(m.metadata))
        val notices=materialUseNotices(listOf(m to .032076000000000002,m to .2,m to .015928999999999998,second to .21))
        assertEquals(2,notices.size)
        assertEquals("Material extrapolation: 2 curves outside supplied range.",materialWarningSummary(notices))
        val detail=notices.first().details()
        assertTrue(detail.contains("Operating strain: 1.59–20 %"))
        assertTrue(detail.contains("Supplied range: 2.3–17 %"))
        assertTrue(detail.contains("Extrapolated"));assertFalse(detail.contains("999999"))
        assertTrue(materialUseNotices(listOf(m to .05)).isEmpty())
    }
    @Test fun zoomPreservesAspectRatioAndInverseForHitTesting() {
        val fit=Viewport.fit(listOf(Point(0.0,0.0),Point(50.0,-20.0)),320.0,230.0,32.0)
        val view=fit.zoomed(2.0,320.0,230.0,Point(12.0,-4.0))
        assertEquals(fit.scale*2,view.scale,0.0)
        val original=Point(25.0,-10.0);val screen=view.map(original)
        assertEquals(172.0,screen.x,1e-12);assertEquals(111.0,screen.y,1e-12)
        assertEquals(original,view.inverse(screen))
        assertFalse(walkerRepositionAllowed(true));assertTrue(walkerRepositionAllowed(false))
    }
}
