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

/** Requires a verified external current-data snapshot. Direct instrumentation; retain both APKs. */
class ConnectionsPhoneTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private fun click(tag:String)=rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick){it()}
    @Test fun sharedSettingEditorRestartConversionAndLargeOverviewPreserveAssignments() {
        rule.acceptSafetyIfShown()
        lateinit var vm:SetupViewModel
        rule.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[SetupViewModel::class.java]}
        val original=vm.state.value.draft;val rigs=vm.library.value.rigs
        try {
            val selection=vm.materials.webbings("RAED").first{it.product=="Parsec"}.let{Selection(it.manufacturer,it.id)}
            rule.runOnIdle { vm.edit{Draft(span="20",leftHeight="0",rightHeight="0",mass="80",position="5",pretensionKn="1",elements="2",
                uniform=UniformGenerator(count="2",main=selection,backup=selection,extra="50"),sections=emptyList())} }
            rule.onNodeWithTag("connection-overview").assertDoesNotExist()
            click("solver-settings");rule.onNodeWithTag("model-connections").assertIsOff();click("model-connections");click("close-settings")
            assertTrue(vm.state.value.draft.modelConnections)
            rule.onNodeWithTag("connection-overview").performScrollTo();click("connection-overview")
            click("joint-C1")
            rule.onNodeWithTag("connection-advanced").assertDoesNotExist()
            val id=StructuralConnections.labeled(vm.state.value.draft).single{it.label=="C1"}.joint.id
            rule.onAllNodesWithText("Idealized connection").assertCountEquals(2)
            click("save-connection");click("close-connections")
            // Advanced records are imported through persistence, never created by Simple.
            rule.runOnIdle { vm.edit { d ->
                val layout=StructuralConnections.reconcile(d)
                d.copy(connections=layout.copy(joints=layout.joints.map{j->if(j.id==id)j.copy(details=JointDetails("Phone joint fixture",listOf(HardwareItem("legacy-1"),HardwareItem("legacy-2")))) else j}))
            } }
            val configured=vm.state.value.draft.connections!!.joints.single{it.id==id}
            assertEquals(2,configured.details.hardware.size);assertNull(configured.details.typeX)
            rule.onNodeWithTag("edit-individual-sections").performScrollTo();click("edit-individual-sections")
            assertEquals(SetupKind.MIXED,vm.state.value.draft.kind)
            assertEquals(configured,vm.state.value.draft.connections!!.joints.single{it.id==id})
            click("solver-settings");rule.onNodeWithTag("model-connections").assertIsOn();click("model-connections");click("close-settings")
            rule.onNodeWithTag("connection-overview").assertDoesNotExist()
            assertEquals(configured,vm.state.value.draft.connections!!.joints.single{it.id==id})
            rule.activityRule.scenario.recreate();rule.waitForIdle()
            rule.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[SetupViewModel::class.java]}
            assertFalse(vm.state.value.draft.modelConnections)
            assertEquals(configured,vm.state.value.draft.connections!!.joints.single{it.id==id})
            click("solver-settings");click("model-connections");click("close-settings")
            click("solve");rule.waitUntil(30000){!vm.state.value.busy && vm.state.value.result!=null}
            val result=vm.state.value.result!!
            rule.onNodeWithTag("result-status").assertExists()
            click("solver-settings");click("model-connections");click("close-settings")
            assertSame(result,vm.state.value.result)
            click("solver-settings");click("model-connections");click("close-settings")
            assertSame(result,vm.state.value.result)
            rule.onNodeWithText("Edit rig").performClick()
            rule.runOnIdle { vm.edit { d -> d.copy(sections=List(100){i->SectionDraft("1",selection,selection,"50",id="many-$i")},span="100") } }
            rule.onNodeWithTag("connection-overview").performScrollTo();click("connection-overview")
            rule.onNodeWithTag("connection-search").performTextReplacement("C99")
            rule.onNodeWithTag("joint-C99").assertExists();click("joint-C99")
            rule.onAllNodesWithText("Idealized connection").assertCountEquals(2);rule.onNodeWithTag("connection-advanced").assertDoesNotExist();click("save-connection");click("close-connections")
            assertEquals(99,StructuralConnections.labeled(vm.state.value.draft).count{it.label.startsWith("C")})
            assertTrue(vm.state.value.draft.connections!!.detached.any{it.id==id})
            assertEquals(rigs,vm.library.value.rigs)
        } finally { rule.runOnIdle { vm.edit{original} } }
    }
}
