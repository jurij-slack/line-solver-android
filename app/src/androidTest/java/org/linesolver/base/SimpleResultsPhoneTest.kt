package org.linesolver.base

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.linesolver.base.model.*
import org.linesolver.base.ui.*
import org.linesolver.base.material.SuppliedDomain

class SimpleResultsPhoneTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @Test fun smallScreenMetricsAndLongMaterialWarningsRetainDetails() {
        rule.acceptSafetyIfShown()
        lateinit var vm:SetupViewModel
        rule.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[SetupViewModel::class.java]}
        val original=vm.state.value.draft
        try {
            val material=vm.materials.webbings("RAED").first{it.product=="Parsec"}.let{Selection(it.manufacturer,it.id)}
            rule.runOnIdle { vm.edit{Draft(span="50",leftHeight="20",rightHeight="20",mass="80",position="12",pretensionKn="1",elements="2",
                sections=emptyList(),uniform=UniformGenerator(count="2",main=material,backup=material,extra="50"),walkerHeight="1.8",leash="1.5")};vm.solve() }
            rule.waitUntil(30000){!vm.state.value.busy}
            assertNull(vm.state.value.issue?.message,vm.state.value.issue)
            assertNotNull(vm.state.value.report?.text(),vm.state.value.result)
            val d=vm.state.value.draft;val result=vm.state.value.result!!
            val notices=List(2){i->MaterialUseNotice("curve-$i","Long material name with manufacturer, model and production revision $i",.015928999999999998,.2,SuppliedDomain(.023,.17),true,"Extrapolated outside supplied data. Original fit assumption retained.")}
            rule.activityRule.scenario.onActivity { activity->activity.setContent { MaterialTheme {
                val density=LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density,1.3f)) {
                    Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()).testTag("small-results")) {
                        RigDrawing(d,state=result.standing)
                        CompactResults(result.standing,d)
                        MaterialWarningInformation(notices)
                    }
                }
            } } }
            rule.onNodeWithTag("rig-zoom-in").performClick();rule.onNodeWithTag("rig-fit").performClick()
            for(text in listOf("Mainline","Backup","Walker sag","Elastic energy",forceKn(result.standing.cableMaximum(org.linesolver.base.energy.Cable.MAIN)))) {
                rule.onNodeWithText(text).performScrollTo()
                val layouts=mutableListOf<TextLayoutResult>()
                val inst=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                java.io.File(inst.targetContext.cacheDir,"simple-small-metrics.png").outputStream().use{inst.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                repeat(2) {
                    layouts.clear()
                    rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
                    rule.waitForIdle()
                }
                assertTrue("Layout missing: $text",layouts.isNotEmpty())
                assertTrue("Clipped $text: "+layouts.map{"${it.size}, width=${it.didOverflowWidth}, height=${it.didOverflowHeight}, lines=${it.lineCount}"},layouts.all{!it.hasVisualOverflow})
            }
            rule.onNodeWithTag("material-warnings").performScrollTo().assertTextContains("2 curves outside supplied range.",substring=true).performClick()
            rule.onAllNodesWithText("Long material name",substring=true).assertCountEquals(2)
            rule.onAllNodesWithText("Operating strain: 1.59–20 %",substring=true).assertCountEquals(2)
            rule.onNodeWithText("Supplied data may include fitted curves; extrapolated results are not experimentally validated.").performScrollTo().assertIsDisplayed()
            val inst=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            java.io.File(inst.targetContext.cacheDir,"simple-small-warnings.png").outputStream().use{inst.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            assertSame(result,vm.state.value.result)
        } finally {rule.runOnIdle{vm.edit{original}}}
    }
}
