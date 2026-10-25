package org.linesolver.base

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import org.linesolver.base.ui.RigDrawing
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.linesolver.base.model.*
import org.linesolver.base.ui.SetupViewModel
import kotlin.math.abs

/** Run directly only after an external private-data backup; never use connected cleanup. */
class ConnectionRefinementPhoneTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private fun click(tag:String) {
        if(tag.startsWith("connector-option-"))rule.onNodeWithTag("connector-catalogue-list").performScrollToNode(hasTestTag(tag))
        rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick){it()}
    }
    private fun screenshot(name:String) {
        rule.waitForIdle()
        android.os.SystemClock.sleep(350) // Allow platform dialog/window animations to settle.
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        File(instrumentation.targetContext.cacheDir,"refinement-$name.png").outputStream().use { out ->
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,out)
        }
    }
    @Test fun denseGraphAt320DpWrapsLinksAndLegend() {
        val d=Draft(kind=SetupKind.MIXED,span="100",leftHeight="0",rightHeight="0",modelConnections=true,
            sections=List(100){SectionDraft(length="1",id="dense-$it")})
        val labels=StructuralConnections.labeled(d).filter{it.label.startsWith("C")}
        var tapped:String?=null;var overview=false
        rule.activityRule.scenario.onActivity { activity -> activity.setContent {
            MaterialTheme {
                val density=LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density,1.3f)) {
                    RigDrawing(d,Modifier.width(320.dp).testTag("small-graph"),onJointTap={tapped=it},onConnections={overview=true})
                }
            }
        } }
        rule.onAllNodes(hasText("Edit C",substring=true) and hasClickAction()).assertCountEquals(8)
        val graph=rule.onNodeWithTag("small-graph").fetchSemanticsNode().boundsInRoot
        val strip=rule.onNodeWithTag("connection-schematic").fetchSemanticsNode().boundsInRoot
        assertTrue(strip.left>=graph.left && strip.right<=graph.right)
        assertTrue(strip.height<graph.width)
        val layouts=mutableListOf<TextLayoutResult>()
        screenshot("small-graph")
        repeat(2) {
            layouts.clear()
            rule.onNodeWithText("━",substring=true).performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
            rule.waitForIdle()
        }
        assertTrue("Legend layout missing",layouts.isNotEmpty())
        assertTrue("Legend clipping: "+layouts.map{"${it.size}, width=${it.didOverflowWidth}, height=${it.didOverflowHeight}, lines=${it.lineCount}"},layouts.all{!it.hasVisualOverflow})
        rule.onNodeWithTag("edit-joint-${labels[1].joint.id}").performClick()
        assertEquals(labels[1].joint.id,tapped)
        click("all-graph-connections");assertTrue(overview)
        screenshot("small-graph")
    }
    @Test fun legacyMetadataRegularConnectorsRenumberingAndModeInstructions() {
        rule.acceptSafetyIfShown()
        lateinit var vm:SetupViewModel
        rule.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[SetupViewModel::class.java]}
        val original=vm.state.value.draft;val saved=vm.library.value.rigs
        try {
            val selection=vm.materials.webbings("RAED").first{it.product=="Parsec"}.let{Selection(it.manufacturer,it.id)}
            rule.runOnIdle { vm.edit{Draft(kind=SetupKind.MIXED,span="50",leftHeight="0",rightHeight="0",mass="80",position="12",pretensionKn="1",elements="2",walkerHeight="1.8",leash="1.5",
                sections=listOf(SectionDraft("25",selection,selection,"50",id="left"),SectionDraft("25",selection,selection,"50",id="right")))} }
            val seam=TypeXSeam(SeamState.broken,"Legacy exact assembly","Original reference",12000.0,true)
            rule.runOnIdle { vm.edit { d ->
                val layout=StructuralConnections.reconcile(d)
                val joint=StructuralConnections.labeled(d).single{it.label=="C1"}.joint
                d.copy(connections=layout.copy(joints=layout.joints.map{if(it.id==joint.id)it.copy(details=it.details.copy(typeX=seam,notes="Legacy TypeX details")) else it}))
            } }
            click("solver-settings")
            for((tag,label) in listOf("model-connections" to "Model connections","show-bungee-options" to "Show bungee options")) {
                val checkbox=rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                val text=rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
                assertTrue(abs(checkbox.center.y-text.center.y)<1f)
            }
            assertEquals(rule.onNodeWithTag("model-connections").fetchSemanticsNode().boundsInRoot.left,
                rule.onNodeWithTag("show-bungee-options").fetchSemanticsNode().boundsInRoot.left,0f)
            screenshot("settings");click("model-connections");click("close-settings")
            rule.onNodeWithTag("connection-overview").performScrollTo();click("connection-overview");click("joint-C1")
            rule.onNodeWithTag("joint-stable-id").assertDoesNotExist()
            rule.onNodeWithText("Between Section 1 and Section 2").assertExists()
            rule.onNodeWithTag("saved-assembly").assertExists()
            rule.onNodeWithTag("connection-typex").assertDoesNotExist();rule.onNodeWithTag("connection-advanced").assertDoesNotExist()
            rule.onNodeWithTag("simple-main-selector").assertDoesNotExist()
            screenshot("retained-editor");click("save-connection");click("close-connections")
            // Fixture import of a legacy assembly tests masses and stable navigation without exposing slots.
            rule.runOnIdle { vm.edit { d -> val layout=StructuralConnections.reconcile(d)
                val specs=listOf("bc_4mm_soft_single_wrap","peguet_mrnz06.0")
                d.copy(connections=layout.copy(joints=layout.joints.map{j->if(j.details.typeX!=null)j.copy(details=j.details.copy(hardware=specs.mapIndexed{i,id->ConnectorLibrary.item(ConnectorLibrary.shipped.products.single{it.id==id},"legacy-$i").copy(role=HardwareRole.main)})) else j}))
            } }
            val joint=StructuralConnections.labeled(vm.state.value.draft).single{it.label=="C1"}.joint
            assertEquals(seam,joint.details.typeX)
            // Insert a modeling section before the retained boundary: C1 becomes C2.
            rule.runOnIdle { vm.edit{d->d.copy(span="75",sections=listOf(SectionDraft("25",selection,selection,"50",id="new-left"))+d.sections)} }
            click("solve");rule.waitUntil(30000){!vm.state.value.busy && vm.state.value.result!=null}
            assertEquals(.0128+.035,vm.state.value.result!!.standing.network.connectorMasses.sumOf{it.massKg},1e-15)
            rule.onNodeWithTag("edit-joint-${joint.id}").performScrollTo().assertTextEquals("Edit C2").performClick()
            rule.onNodeWithText("C2").assertExists()
            rule.onNodeWithTag("seam-broken").assertDoesNotExist()
            rule.onNodeWithTag("connection-typex").assertDoesNotExist()
            rule.onNodeWithTag("saved-assembly").assertExists()
            click("save-connection");click("close-connections")
            rule.onNodeWithTag("walker-instruction").performScrollTo().assertTextContains("Standing shown",substring=true)
            val report=vm.state.value.report!!.text()
            assertTrue(report.contains("Legacy TypeX details"));assertTrue(report.contains(joint.id))
            rule.onNodeWithTag("detailed-results").performScrollTo().performClick()
            rule.onNodeWithText("Connectors").performScrollTo().assertExists()
            rule.onNodeWithText("Export Diagnostics").performScrollTo().assertExists()
            click("close-details")
            assertEquals(report,vm.state.value.report!!.text())
            // Prescribed cut exercises actual Catch/Rest result selection without changing slider behavior.
            rule.runOnIdle { vm.edit{it.copy(cutEnabled=true,cutPosition="60")} ;vm.solve() }
            rule.waitUntil(30000){!vm.state.value.busy && vm.state.value.result?.catch!=null}
            rule.onNodeWithTag("walker-instruction").performScrollTo().assertTextContains("Catch shown",substring=true)
            rule.onNodeWithTag("equilibrium-walker-slider").assertIsNotEnabled()
            val failed=vm.state.value.result
            val failedDraft=vm.state.value.draft
            rule.runOnIdle{vm.moveWalker(30.0)}
            assertSame(failed,vm.state.value.result);assertEquals(failedDraft,vm.state.value.draft)
            screenshot("catch-results")
            rule.onNodeWithText("Rest",useUnmergedTree=true).performScrollTo().performClick()
            rule.onNodeWithTag("walker-instruction").performScrollTo().assertTextContains("Rest shown",substring=true)
            val standing=vm.state.value.result!!.standing
            rule.onNodeWithTag("reset-failure").performScrollTo().performClick()
            rule.onNodeWithTag("equilibrium-walker-slider").performScrollTo().assertIsEnabled()
            assertNull(vm.state.value.result!!.catch);assertEquals(standing,vm.state.value.result!!.standing)
            rule.activityRule.scenario.recreate();rule.waitForIdle()
            rule.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[SetupViewModel::class.java]}
            assertEquals(joint.details,vm.state.value.draft.connections!!.joints.single{it.id==joint.id}.details)
            assertEquals(saved,vm.library.value.rigs)
        } finally { rule.runOnIdle{vm.edit{original}} }
    }
}
