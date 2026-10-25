package org.linesolver.base

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.linesolver.base.model.*
import org.linesolver.base.energy.Cable
import org.linesolver.base.material.catalogue.*
import org.linesolver.base.ui.SetupViewModel

/** Only direct focused instrumentation after an externally verified private-data backup. */
class ConnectorPhoneTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private fun click(tag:String) {
        if(tag.startsWith("connector-option-"))rule.onNodeWithTag("connector-catalogue-list").performScrollToNode(hasTestTag(tag))
        rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick){it()}
    }
    @Test fun exactProductsBackupStatusAndBungeeVisibilityPersistWithoutDisconnecting() {
        rule.acceptSafetyIfShown()
        lateinit var vm:SetupViewModel
        rule.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[SetupViewModel::class.java]}
        val original=vm.state.value.draft;val rigs=vm.library.value.rigs
        val materialPackage=vm.materialStore.value
        val materialEdit=vm.materialEdit
        try {
            val selection=vm.materials.webbings("RAED").first{it.product=="Parsec"}.let{Selection(it.manufacturer,it.id)}
            rule.runOnIdle { vm.edit{Draft(span="20",leftHeight="0",rightHeight="0",mass="80",position="5",pretensionKn="1",elements="2",
                uniform=UniformGenerator(count="2",main=selection,backup=selection,extra="50"),sections=emptyList())} }
            rule.onNodeWithTag("add-right-bungee").assertDoesNotExist()
            click("solver-settings");rule.onNodeWithTag("show-bungee-options").assertIsOff();click("model-connections");click("close-settings")
            rule.onNodeWithTag("connection-overview").performScrollTo();click("connection-overview");click("joint-C1")
            val jointId=StructuralConnections.labeled(vm.state.value.draft).single{it.label=="C1"}.joint.id
            rule.onNode(hasAnyAncestor(hasTestTag("simple-backup-selector")) and hasClickAction()).performClick()
            rule.onNode(hasText("Search exact products/configurations") and hasSetTextAction()).performTextInput("bc_4mm")
            click("connector-option-bc_4mm_soft_double_wrap")
            rule.onNodeWithTag("connection-advanced").assertDoesNotExist()
            click("save-connection");click("close-connections")
            // Retain pre-existing load-state annotations; Simple has no controls to create them.
            rule.runOnIdle { vm.edit { d -> val layout=StructuralConnections.reconcile(d)
                d.copy(connections=layout.copy(joints=layout.joints.map{j->if(j.id==jointId)j.copy(details=j.details.copy(hardware=j.details.hardware.map{it.copy(standingLoad=LoadAssumption(HardwareLoad.unloaded),failedLoad=LoadAssumption(HardwareLoad.loaded))})) else j}))
            } }
            val item=vm.state.value.draft.connections!!.joints.single{it.id==jointId}.details.hardware.single()
            assertEquals("bc_4mm_soft_double_wrap",item.productId);assertEquals(HardwareRole.backup,item.role)
            assertEquals(HardwareLoad.unloaded,item.standingLoad.status);assertEquals(HardwareLoad.loaded,item.failedLoad.status)
            rule.onNodeWithTag("edit-individual-sections").performScrollTo();click("edit-individual-sections")
            assertEquals(item,vm.state.value.draft.connections!!.joints.single{it.id==jointId}.details.hardware.single())
            click("solve");rule.waitUntil(30000){!vm.state.value.busy && vm.state.value.result!=null}
            val result=vm.state.value.result!!;assertEquals(4,result.standing.network.physicalJoints.single{it.joint.id==jointId}.joint.ends.size)
            assertEquals(.0128,result.standing.network.connectorMasses.single().massKg,0.0)
            click("solver-settings");rule.onNodeWithTag("show-bungee-options").assertIsNotEnabled();click("close-settings");assertSame(result,vm.state.value.result)
            rule.onNodeWithText("Edit rig").performClick();rule.onNodeWithTag("add-right-bungee").assertDoesNotExist()
            click("solver-settings");rule.onNodeWithTag("show-bungee-options").assertIsNotEnabled();click("close-settings");rule.onNodeWithTag("add-right-bungee").assertDoesNotExist()
            // Use the existing measured-law editor; no production catalogue changes.
            rule.runOnIdle {
                vm.editMaterial(MaterialEdit(name="Synthetic Phase 6 phone cord",brand="Fixture",category=ProductCategory.bungee,
                    bungee=BungeeLaw(basis=BungeeBasis.physical_cord,testedPhysicalLengthM=2.0,totalMassKg=1.0,source="Synthetic phone test",idealEqualLegs=true),
                    source="Synthetic fixture only",table="1,100\n3,200"));vm.saveMaterial()
            }
            rule.waitUntil(20000){!vm.materialBusy && vm.materialStore.value.catalogue.models.size==materialPackage.catalogue.models.size+1}
            val model=vm.materialStore.value.catalogue.models.last()
            rule.runOnIdle { vm.edit { d->d.copy(bungees=BungeeLayout(banks=listOf(BungeeBank("phone-bank",BungeePlacement.series_right,cable=Cable.MAIN,
                assemblies=listOf(InstalledBungee("phone-cord",model.reference(),2.0,limiterPhysicalLengthM=4.0,source="Synthetic instance")))))) } }
            rule.onNodeWithTag("hidden-bungee-summary").performScrollTo().assertExists()
            rule.onNodeWithTag("add-right-bungee").assertDoesNotExist()
            val hidden=vm.state.value.draft.bungees
            rule.activityRule.scenario.recreate();rule.waitForIdle()
            rule.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[SetupViewModel::class.java]}
            assertFalse(vm.state.value.draft.showBungeeOptions);assertEquals(hidden,vm.state.value.draft.bungees)
            assertEquals(item,vm.state.value.draft.connections!!.joints.single{it.id==jointId}.details.hardware.single())
            click("solver-settings");rule.onNodeWithTag("show-bungee-options").assertIsNotEnabled();click("close-settings")
            rule.onNodeWithTag("add-right-bungee").assertDoesNotExist();assertEquals(hidden,vm.state.value.draft.bungees)
            assertEquals(rigs,vm.library.value.rigs)
        } finally { rule.runOnIdle { vm.materialStore.commit(materialPackage);vm.editMaterial(materialEdit);vm.edit{original} } }
    }
    private fun hasTestTagPrefix(prefix:String)=SemanticsMatcher("Test tag starts $prefix") { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith(prefix)==true }
}
