package org.linesolver.base

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.linesolver.base.material.catalogue.*
import org.linesolver.base.model.*
import org.linesolver.base.ui.*

/** Direct instrumentation only after verified backup of existing private data. */
class BungeePhoneTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private fun click(tag:String)=rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick){it()}
    private fun screenshot(name:String) {
        rule.waitForIdle()
        val inst=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        java.io.File(inst.targetContext.cacheDir,"demo-bungee-$name.png").outputStream().use {
            inst.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
    }
    @Test fun disabledCheckboxCannotExposeEditorsEvenWithRestoredPreference() {
        rule.acceptSafetyIfShown()
        lateinit var vm:SetupViewModel
        rule.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[SetupViewModel::class.java]}
        val original=vm.state.value.draft
        try {
            for(kind in SetupKind.entries)for(visible in listOf(false,true)) {
                rule.runOnIdle{vm.edit{Draft(kind=kind,showBungeeOptions=visible)}}
                click("solver-settings")
                rule.onNodeWithTag("show-bungee-options").assertIsDisplayed().assertIsNotEnabled().assertIsOff().performTouchInput{click()}
                rule.onNodeWithTag("show-bungee-options-status").assertTextEquals("Currently unavailable").assertIsDisplayed()
                screenshot("settings")
                assertEquals(visible,vm.state.value.draft.showBungeeOptions)
                // Other settings work without overwriting the retained preference.
                click("model-connections");assertEquals(visible,vm.state.value.draft.showBungeeOptions)
                click("close-settings")
                for(tag in listOf("bungee-editor","add-left-bungee","add-right-bungee","insert-bungee"))rule.onNodeWithTag(tag).assertDoesNotExist()
            }
        } finally {rule.runOnIdle{vm.edit{original}}}
    }
    @Test fun savedBungeeConfigurationIsRetainedAndCannotReachCalculations() {
        rule.acceptSafetyIfShown()
        lateinit var vm:SetupViewModel
        rule.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[SetupViewModel::class.java]}
        val original=vm.state.value.draft;val originalEdit=vm.materialEdit;val materials=vm.materialStore.value;val rigs=vm.library.value.rigs
        try {
            rule.runOnIdle {
                vm.editMaterial(MaterialEdit(name="Demo retained fixture",brand="Fixture",category=ProductCategory.bungee,
                    bungee=BungeeLaw(basis=BungeeBasis.physical_cord,testedPhysicalLengthM=2.0,totalMassKg=1.0,source="Synthetic fixture",idealEqualLegs=true),
                    source="Synthetic fixture",table="1,100\n3,200"));vm.saveMaterial()
            }
            rule.waitUntil(20000){!vm.materialBusy}
            val model=vm.materialStore.value.catalogue.models.last()
            val m=vm.materials.webbings("RAED").first{it.product=="Parsec"}.let{Selection(it.manufacturer,it.id)}
            rule.runOnIdle { vm.edit { Draft(kind=SetupKind.MIXED,span="20",leftHeight="0",rightHeight="0",mass="80",position="5",pretensionKn="1",elements="2",showBungeeOptions=true,
                sections=listOf(SectionDraft("10",m,m,"50",id="s1"),SectionDraft("10",m,m,"50",id="s2")),
                bungees=BungeeLayout(banks=listOf(BungeeBank("bank",BungeePlacement.series_right,assemblies=listOf(
                    InstalledBungee("cord",model.reference(),2.0,limiterPhysicalLengthM=4.0,source="Preserve installed source",uncertainty="Preserve notes")))))) } }
            val retained=vm.state.value.draft
            assertNotNull(retained.validate(vm.materials).setup)
            rule.onNodeWithTag("solve").assertIsNotEnabled()
            rule.onNodeWithTag("hidden-bungee-summary").performScrollTo().assertTextContains("configuration is retained unchanged",substring=true)
            screenshot("retained-rig")
            for(action in listOf<()->Unit>({vm.solve()},{vm.moveWalker(6.0)},{vm.failSection(0)},{vm.captureInvalidInput()})) {
                rule.runOnIdle{action()}
                assertFalse(vm.state.value.busy);assertNull(vm.state.value.result);assertNull(vm.state.value.report)
                assertEquals(DemoBungeePolicy.retainedMessage,vm.state.value.issue?.message)
                assertEquals(retained,vm.state.value.draft)
            }
            rule.activityRule.scenario.recreate();rule.waitForIdle()
            rule.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[SetupViewModel::class.java]}
            assertEquals(retained,vm.state.value.draft);rule.onNodeWithTag("solve").assertIsNotEnabled()
            click("more-menu");click("my-materials")
            rule.onNodeWithTag("new-bungee-material").performScrollTo().assertIsNotEnabled()
            rule.onNodeWithTag("retained-bungee-material").performScrollTo().assertIsDisplayed()
            screenshot("retained-material")
            rule.onNodeWithTag("save-material").assertDoesNotExist();rule.onNodeWithTag("editor-name").assertDoesNotExist()
            assertEquals(rigs,vm.library.value.rigs)
        } finally {rule.runOnIdle{vm.materialStore.commit(materials);vm.editMaterial(originalEdit);vm.edit{original}}}
    }
}
