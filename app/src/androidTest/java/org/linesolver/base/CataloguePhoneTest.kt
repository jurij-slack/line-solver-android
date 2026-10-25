package org.linesolver.base

import android.content.pm.ActivityInfo
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

class CataloguePhoneTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun click(tag: String) = rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) { it() }
    private fun curve(tag: String) { rule.onNodeWithTag("curve-list").performScrollToNode(hasTestTag(tag)); click(tag) }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500, 5000)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
    }

    @Test fun alternativesProvisionalAndReviewEvidencePreserveRigSelection() {
        rule.acceptSafetyIfShown()
        lateinit var vm: SetupViewModel
        rule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            vm = ViewModelProvider(it)[SetupViewModel::class.java]
        }
        val before = vm.state.value.draft
        rule.waitUntil(10000) { !vm.library.value.busy }
        val library = vm.library.value.rigs
        val old = vm.materials.get("balance_community__aero2__manufacturer_load_elongation_curve_readout")
        try {
            rule.runOnIdle { vm.edit { it.withUniform(UniformGenerator("1", Selection(old.manufacturer, old.id), Selection(old.manufacturer, old.id), "5")) } }
            rule.onNodeWithTag("main-0-model").performScrollTo(); click("main-0-model")
            rule.onNodeWithText("Imported nonlinear · catalogue #10").performClick()
            assertEquals("webbings:model:10", vm.state.value.draft.uniform!!.main.materialId)
            assertEquals(old.id, vm.state.value.draft.uniform!!.backup.materialId)
            screenshot("catalogue-alternative.png")
            rule.activityRule.scenario.recreate()
            rule.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[SetupViewModel::class.java] }
            assertEquals("webbings:model:10", vm.state.value.draft.uniform!!.main.materialId)
            click("more-menu"); click("material-curves")
            curve("curve-clear")
            curve("curve-select-webbings:model:257")
            rule.onNodeWithTag("curve-list").performScrollToNode(hasTestTag("curve-chart"))
            screenshot("catalogue-provisional-plot.png")
            curve("curve-details-webbings:model:257")
            rule.onNodeWithText("Nominal MBS unknown; no breaking strain inferred.").assertExists()
            screenshot("catalogue-provisional-details.png")
            click("close-curve-details")
            rule.onNodeWithTag("curve-list").performScrollToNode(hasTestTag("curve-search"))
            rule.onNodeWithTag("curve-search").performTextReplacement("Spider Silk MK3")
            rule.onNodeWithTag("curve-search").performImeAction()
            rule.onNodeWithTag("curve-select-webbings:model:190").assertDoesNotExist()
            curve("catalogue-webbings:dataset:190")
            rule.onNodeWithText("review only").assertExists()
            rule.onNodeWithText("Force and strain must both increase strictly; this series cannot define an invertible law.").assertExists()
            screenshot("catalogue-review.png")
            click("close-source-details")
            click("close-curves")
            assertEquals("webbings:model:10", vm.state.value.draft.uniform!!.main.materialId)
            assertEquals(library, vm.library.value.rigs)
        } finally { rule.runOnIdle { vm.edit { before } } }
    }
}
