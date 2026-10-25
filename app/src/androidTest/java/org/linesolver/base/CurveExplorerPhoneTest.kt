package org.linesolver.base

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.linesolver.base.model.*
import org.linesolver.base.ui.SetupViewModel
import java.io.File

class CurveExplorerPhoneTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @org.junit.Before fun acknowledgeSession(){rule.acceptSafetyIfShown()}
    private fun click(tag:String,scroll:Boolean=true) {
        if(scroll)rule.onNodeWithTag("curve-list").performScrollToNode(hasTestTag(tag))
        rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick){it()}
    }
    private fun screenshot(name:String) {
        rule.activityRule.scenario.onActivity{(it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(it.window.decorView.windowToken,0)}
        rule.waitForIdle();val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500,5000)
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        try{File(instrumentation.targetContext.getExternalFilesDir(null),name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
    }
    @Test fun selectCompareZoomAndKeepRigUnchanged() {
        lateinit var vm:SetupViewModel
        rule.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[SetupViewModel::class.java]}
        val firefox=vm.materials.all().first{it.product=="FireFox"}
        val axiom=vm.materials.all().first{it.product=="Axiom"}
        rule.runOnIdle{vm.edit{it.withUniform(UniformGenerator("5",Selection(firefox.manufacturer,firefox.id),Selection(axiom.manufacturer,axiom.id),"5"))}}
        val before=vm.state.value.draft;val library=vm.library.value.rigs
        screenshot("phase06-entry.png")
        click("more-menu",false)
        click("material-curves",false)
        rule.onNodeWithTag("curve-chart").assertExists()
        rule.onNodeWithTag("curve-list").performScrollToNode(hasTestTag("curve-count"))
        rule.onNodeWithTag("curve-count").assertTextContains("2 / ${vm.materials.all().size}",substring=true)
        click("curve-all");rule.onNodeWithTag("curve-count").assertTextContains("${vm.materials.all().size} / ${vm.materials.all().size}",substring=true)
        click("curve-clear");rule.onNodeWithTag("curve-count").assertTextContains("0 / ${vm.materials.all().size}",substring=true)
        click("curve-select-${firefox.id}");click("curve-select-${axiom.id}")
        rule.onNodeWithTag("curve-list").performScrollToNode(hasTestTag("curve-chart"));screenshot("phase06-comparison.png")
        rule.onNodeWithTag("curve-list").performScrollToNode(hasTestTag("curve-max"))
        rule.onNodeWithTag("curve-max").performTextReplacement("5");rule.onNodeWithTag("curve-max").performImeAction()
        rule.onNodeWithTag("curve-list").performScrollToNode(hasTestTag("curve-chart"));screenshot("phase06-zoom.png")
        click("curve-select-${axiom.id}")
        rule.onNodeWithTag("curve-list").performScrollToNode(hasTestTag("curve-max"));rule.onNodeWithTag("curve-max").assertTextContains("5")
        rule.onNodeWithTag("curve-max").performTextReplacement("-1")
        rule.onNodeWithText("Enter 0 ≤ minimum < maximum ≤ 1,000,000 %.",substring=true).assertExists()
        click("curve-reset")
        rule.onNodeWithTag("curve-list").performScrollToNode(hasTestTag("curve-search"))
        rule.onNodeWithTag("curve-search").performTextReplacement("FireFox");rule.onNodeWithTag("curve-search").performImeAction()
        click("curve-details-${firefox.id}");rule.onNodeWithText("Supplied range: 2.30–17.0 %").assertExists();screenshot("phase06-details.png")
        click("close-curve-details",false)
        rule.activityRule.scenario.recreate()
        rule.onNodeWithTag("curve-list").performScrollToNode(hasTestTag("curve-count"));rule.onNodeWithTag("curve-count").assertTextContains("1 / ${vm.materials.all().size}",substring=true)
        click("close-curves",false)
        assertEquals(before,vm.state.value.draft);assertEquals(library,vm.library.value.rigs)
        rule.onNodeWithTag("more-menu").assertExists()
    }
    @Test fun provisionalAndDyneemiteCurvesUseUpdatedModels() {
        lateinit var vm:SetupViewModel
        rule.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[SetupViewModel::class.java]}
        val j=vm.materials.all().first{it.product=="Jumanji"}
        val d=vm.materials.all().first{it.product=="Dyneemite PRO"}
        val before=vm.state.value.draft
        rule.runOnIdle{vm.edit{it.withUniform(UniformGenerator("1",Selection(j.manufacturer,j.id),Selection(d.manufacturer,d.id),"5"))}}
        click("more-menu",false);click("material-curves",false)
        rule.onNodeWithTag("curve-chart").assertExists()
        screenshot("material-update-curves.png")
        click("curve-details-${j.id}")
        rule.onNodeWithText("Linear approximation — insufficient force–elongation data. Bounded by stated MBS.").assertExists()
        rule.onNodeWithText("Supplied range: 2.90–2.90 %").assertExists()
        screenshot("material-update-jumanji.png")
        click("close-curve-details",false)
        click("curve-details-${d.id}")
        rule.onNodeWithText("Estimated terminal stiffness; unmeasured linear continuation to MBS",substring=true).assertExists()
        screenshot("material-update-dyneemite.png")
        click("close-curve-details",false);click("close-curves",false)
        rule.runOnIdle{vm.edit{before}}
    }

}
