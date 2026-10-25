package org.linesolver.base

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.linesolver.base.model.*
import org.linesolver.base.ui.SetupViewModel

/** Direct instrumentation only, after verified external backup. No custom-library writes. */
class ConnectionDeactivationPhoneTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private fun click(tag:String)=rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick){it()}
    private fun screenshot(name:String) {
        rule.waitForIdle();android.os.SystemClock.sleep(350)
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        java.io.File(instrumentation.targetContext.cacheDir,"deactivation-$name.png").outputStream().use { out ->
            instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out)
        }
    }
    private fun noInactiveControls() {
        for(tag in listOf("connection-typex","connection-regular","seam-intact","seam-broken","seam-published-bound","continuous-main","continuous-backup"))rule.onNodeWithTag(tag).assertDoesNotExist()
        rule.onNodeWithText("Transverse sewing").assertDoesNotExist()
        rule.onNodeWithText("TypeX").assertDoesNotExist()
    }
    @Test fun bothWorkflowsCatalogueAndDynamicsPreserveLegacyRecords() {
        rule.acceptSafetyIfShown()
        lateinit var vm:SetupViewModel
        rule.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[SetupViewModel::class.java]}
        val original=vm.state.value.draft;val saved=vm.library.value.rigs
        try {
            val material=vm.materials.webbings("RAED").first{it.product=="Parsec"}.let{Selection(it.manufacturer,it.id)}
            val custom=ConnectorLibrary.custom("Fixture connector","Fixture",ConnectorCategory.soft_shackle,.02,9000.0,"MBS","single wrap fixture","Test declaration","Not verified")
            val inactive=ConnectorLibrary.custom("TypeX legacy seam","Fixture",ConnectorCategory.sewn_transverse,null,1.0,"MBS","Legacy pair","Legacy declaration","Unsupported")
            val seam=TypeXSeam(SeamState.broken,"Legacy assembly","Source",12000.0,true)
            var retainedId=""
            for(kind in listOf(SetupKind.UNIFORM,SetupKind.MIXED)) {
                rule.runOnIdle { vm.edit {
                    val d=Draft(kind=kind,span="20",leftHeight="0",rightHeight="0",mass="80",position="5",pretensionKn="1",elements="2",modelConnections=true,
                        uniform=if(kind==SetupKind.UNIFORM)UniformGenerator(count="2",main=material,backup=material,extra="50",instanceIds=listOf("left","right")) else null,
                        sections=if(kind==SetupKind.MIXED)listOf(SectionDraft("10",material,material,"50",id="left"),SectionDraft("10",material,material,"50",id="right")) else emptyList())
                    val layout=StructuralConnections.reconcile(d)
                    retainedId=StructuralConnections.labeled(d).single{it.label=="C1"}.joint.id
                    d.copy(connections=layout.copy(joints=layout.joints.map{if(it.id==retainedId)it.copy(details=JointDetails("Legacy TypeX notes",listOf(ConnectorLibrary.item(custom,"custom-main").copy(role=HardwareRole.main),ConnectorLibrary.item(inactive,"legacy-seam")),seam)) else it}))
                } }
                val preserved=vm.state.value.draft.connections!!.joints.single{it.id==retainedId}
                rule.onNodeWithTag("connection-overview").performScrollTo();click("connection-overview")
                rule.onNodeWithTag("joint-Left attachment").assertDoesNotExist();rule.onNodeWithTag("joint-Right attachment").assertDoesNotExist()
                rule.onNodeWithTag("joint-C1").assertTextEquals("C1 · Section 1 ↔ Section 2")
                click("joint-C1");noInactiveControls()
                rule.onNodeWithTag("saved-assembly").assertExists()
                rule.onNodeWithTag("simple-main-selector").assertDoesNotExist()
                screenshot("retained-editor");click("save-connection");click("close-connections")
                assertEquals(preserved,vm.state.value.draft.connections!!.joints.single{it.id==retainedId})
                // Ordinary stored custom snapshot has one item and can be selected without advanced management.
                rule.runOnIdle{vm.edit{d->val layout=StructuralConnections.reconcile(d)
                    d.copy(connections=layout.copy(joints=layout.joints.map{j->if(j.id==retainedId)j.copy(details=JointDetails(hardware=listOf(ConnectorLibrary.item(custom,"custom-main").copy(role=HardwareRole.main)))) else j}))}}
                rule.onNodeWithTag("connection-overview").performScrollTo();click("connection-overview");click("joint-C1")
                rule.onNodeWithTag("choose-connector-custom-main").performClick()
                val search=rule.onNode(hasText("Search exact products/configurations") and hasSetTextAction())
                search.performTextReplacement("Fixture connector")
                rule.onNodeWithTag("connector-catalogue-list").performScrollToNode(hasTestTag("connector-group-custom"))
                rule.onNodeWithTag("connector-group-custom").assertTextEquals("Custom")
                rule.onNodeWithTag("connector-catalogue-list").performScrollToNode(hasTestTag("connector-option-${custom.id}"))
                rule.onNodeWithTag("connector-option-${custom.id}").assertTextContains("MBS 9 kN",substring=true).performClick()
                rule.onNode(hasAnyAncestor(hasTestTag("simple-backup-selector")) and hasClickAction()).performClick()
                rule.onNode(hasText("Search exact products/configurations") and hasSetTextAction()).performTextReplacement("bc_4mm")
                rule.onNodeWithTag("connector-group-softShackles").assertTextEquals("Soft shackles")
                rule.onNodeWithTag("connector-catalogue-list").performScrollToNode(hasTestTag("connector-option-bc_4mm_soft_double_wrap"))
                rule.onNodeWithTag("connector-option-bc_4mm_soft_double_wrap").assertTextContains("double wrap",substring=true).performClick()
                screenshot("compact-editor")
                rule.onNodeWithTag("choose-connector-custom-main").performClick()
                rule.onNode(hasText("Search exact products/configurations") and hasSetTextAction()).performTextReplacement("mrni08_0_en")
                rule.onNodeWithTag("connector-group-quicklinks").assertTextEquals("Quicklinks")
                rule.onNodeWithTag("connector-option-peguet_mrni08_0_en").assertTextContains("BL 55 kN",substring=true)
                rule.onNodeWithText("Review only",substring=true).assertDoesNotExist();noInactiveControls()
                rule.onNodeWithText("New custom connector").assertDoesNotExist()
                rule.onNodeWithText("Close catalogue").performClick()
                click("save-connection");click("close-connections")
                val updated=vm.state.value.draft.connections!!.joints.single{it.id==retainedId}
                assertEquals(listOf(HardwareRole.main,HardwareRole.backup),updated.details.hardware.map{it.role})
                rule.onNodeWithTag("connection-overview").performScrollTo();click("connection-overview");click("joint-C1")
                rule.onNode(hasAnyAncestor(hasTestTag("simple-backup-selector")) and hasClickAction()).performClick()
                click("choose-idealized");click("save-connection");click("close-connections")
                assertEquals(updated.details.hardware.filter{it.role==HardwareRole.main},vm.state.value.draft.connections!!.joints.single{it.id==retainedId}.details.hardware)
                // Restore exact imported assembly for the final mechanics/compatibility check.
                rule.runOnIdle{vm.edit{d->val layout=StructuralConnections.reconcile(d)
                    d.copy(connections=layout.copy(joints=layout.joints.map{j->if(j.id==retainedId)preserved else j}))}}
                click("mode-ADVANCED");rule.onNodeWithTag("mode-ADVANCED").assertTextEquals("Dynamics").assertIsSelected()
                rule.onNodeWithTag("solve").assertIsNotEnabled();noInactiveControls()
                click("mode-SIMPLE")
            }
            click("solve");rule.waitUntil(30000){!vm.state.value.busy && vm.state.value.result!=null}
            val state=vm.state.value.result!!.standing
            assertEquals(.02,state.network.connectorMasses.sumOf{it.massKg},1e-15)
            rule.onNodeWithTag("edit-joint-$retainedId").performScrollTo().performClick()
            noInactiveControls();click("save-connection");click("close-connections")
            assertEquals(seam,vm.state.value.draft.connections!!.joints.single{it.id==retainedId}.details.typeX)
            assertEquals(saved,vm.library.value.rigs)
        } finally {rule.runOnIdle{vm.edit{original}}}
    }
}
