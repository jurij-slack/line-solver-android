package org.linesolver.base

import android.graphics.Bitmap
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.linesolver.base.model.*
import org.linesolver.base.ui.*
import org.linesolver.base.energy.*
import java.io.File

class Phase08PhoneTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @org.junit.Before fun acknowledgeSession(){rule.acceptSafetyIfShown()}
    private lateinit var vm:SetupViewModel
    private fun click(tag:String,scroll:Boolean=true) {
        val n=rule.onNodeWithTag(tag);if(scroll)n.performScrollTo()
        n.performSemanticsAction(SemanticsActions.OnClick){it()}
    }
    private fun complete() {rule.waitUntil(120000){!vm.state.value.busy};assertNull(vm.state.value.issue);assertNotNull(vm.state.value.result);rule.waitForIdle()}
    private fun screenshot(name:String) {
        rule.waitForIdle();rule.waitUntil(10000){rule.activity.hasWindowFocus()};val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500,5000)
        val b=instrumentation.uiAutomation.takeScreenshot()
        try{File(instrumentation.targetContext.cacheDir,name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{b.recycle()}
    }
    @Test fun standingRingTracksDragBeforeRecalculation() {
        rule.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[SetupViewModel::class.java];it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        rule.waitUntil(10000){rule.activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT}
        val main=vm.materials.webbings("RAED").first{it.product=="Parsec"}.id
        val backup=vm.materials.webbings("RAED").first{it.product=="MOTM"}.id
        rule.runOnIdle{vm.edit{Draft(span="70",leftHeight="0",rightHeight="0",mass="80",position="12",walkerHeight="1.8",leash="1.5",pretensionKn="1.5").withUniform(
            UniformGenerator("2",Selection("RAED",main),Selection("RAED",backup),"5"))}}
        click("solve",false);complete()
        val result=vm.state.value.result!!;val coordinates=result.standing.r.copyOf()
        rule.onNodeWithTag("full-span-drawing").performScrollTo();screenshot("leash-ring-standing.png")
        val slider=rule.onNodeWithTag("equilibrium-walker-slider").performScrollTo()
        slider.performTouchInput {down(Offset(width*.18f,centerY));moveTo(Offset(width*.72f,centerY),300)}
        rule.waitForIdle()
        assertSame("No solve while the finger is down",result,vm.state.value.result)
        assertArrayEquals(coordinates,result.standing.r,0.0)
        rule.onNodeWithContentDescription("Solved mainline with schematic backup loops through the leash ring. Main solid, backup dashed. Equal horizontal and vertical metre scale.").assertExists()
        screenshot("leash-ring-drag.png")
        slider.performTouchInput {up()};complete()
        assertTrue(vm.state.value.draft.position.number()!!>35.0)
        assertNotEquals(result.standing.attachmentPosition,vm.state.value.result!!.standing.attachmentPosition)
        rule.onNodeWithTag("full-span-drawing").performScrollTo();screenshot("leash-ring-moved.png")
    }
    @Test fun previewTapFailureResetMoveDetailsAndResetRig() {
        rule.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[SetupViewModel::class.java];it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        rule.waitUntil(10000){rule.activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT}
        val main=vm.materials.webbings("RAED").first{it.product=="Parsec"}.id
        val backup=vm.materials.webbings("RAED").first{it.product=="MOTM"}.id
        rule.runOnIdle{vm.edit{Draft(span="70",leftHeight="0",rightHeight="0",mass="80",position="12",walkerHeight="1.8",leash="1.5",pretensionKn="1.5").withUniform(
            UniformGenerator("2",Selection("RAED",main),Selection("RAED",backup),"5"))}}
        rule.onNodeWithTag("walker-slider").performScrollTo().assertIsDisplayed();rule.onNodeWithTag("full-span-drawing").performScrollTo();screenshot("phase08-preview.png")
        rule.onNodeWithTag("segment-failure-hint").assertDoesNotExist()
        click("solve",false);complete()
        rule.onNodeWithTag("segment-failure-hint").performScrollTo()
            .assertTextEquals("Tap a mainline segment to simulate a failure in that section.")
        val original=vm.state.value.result!!.standing.r.copyOf()
        val state=vm.state.value.result!!.standing
        rule.onNodeWithTag("full-span-drawing").performScrollTo().performTouchInput {
            val points=state.network.elements.filter{it.cable==Cable.MAIN}.flatMap{e->listOf(e.a,e.b).map{Point(state.r[2*it],state.r[2*it+1])}}+
                state.backupDrawing().points+state.walkerDrawing()?.bounds.orEmpty()
            val density=rule.activity.resources.displayMetrics.density
            val view=Viewport.fit(points,width.toDouble(),height.toDouble(),32*density.toDouble())
            val edge=state.network.elements.first{it.cable==Cable.MAIN && it.section==1 && it.from>=.4}
            val p=view.map(Point((state.r[2*edge.a]+state.r[2*edge.b])/2,(state.r[2*edge.a+1]+state.r[2*edge.b+1])/2))
            click(Offset(p.x.toFloat(),p.y.toFloat()))
        }
        rule.onNodeWithText("Fail mainline section 2?").assertExists()
        assertNull("Tapping must only request confirmation",vm.state.value.result!!.catch)
        assertFalse(vm.state.value.draft.cutEnabled)
        click("confirm-failure",false);complete()
        rule.onNodeWithTag("segment-failure-hint").assertDoesNotExist()
        assertTrue(vm.state.value.result!!.catch!!.network.attachment!!.contacts.main.threaded)
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"phase08-catch-report.json").writeText(vm.state.value.report!!.text())
        rule.onNodeWithText("Catch").assertIsSelected()
        rule.onNodeWithTag("full-span-drawing").performScrollTo();screenshot("phase08-catch.png")
        rule.onNodeWithText("Rest").performScrollTo().performClick()
        rule.onNodeWithTag("segment-failure-hint").assertDoesNotExist()
        rule.onNodeWithTag("full-span-drawing").performScrollTo();screenshot("leash-ring-rest.png")
        rule.onNodeWithText("Catch").performScrollTo().performClick()
        click("detailed-results");rule.onNodeWithText("Numerical diagnostics").performScrollTo().assertExists()
        rule.onNodeWithTag("close-details").performScrollTo();click("close-details",false)
        click("reset-failure");assertArrayEquals(original,vm.state.value.result!!.standing.r,0.0);assertFalse(vm.state.value.draft.cutEnabled)
        rule.onNodeWithTag("segment-failure-hint").assertExists()
        click("failure-section");click("failure-section-option-Section 1")
        rule.onNodeWithText("Fail mainline section 1?").assertExists();click("confirm-failure",false);complete()
        assertFalse(vm.state.value.result!!.catch!!.network.attachment!!.contacts.main.threaded)
        rule.onNodeWithTag("equilibrium-walker-slider").performScrollTo().assertIsNotEnabled()
        val failed=vm.state.value.result
        rule.runOnIdle{vm.moveWalker(22.0)}
        assertSame(failed,vm.state.value.result)
        click("reset-failure")
        rule.onNodeWithTag("equilibrium-walker-slider").performScrollTo().assertIsEnabled().performSemanticsAction(SemanticsActions.SetProgress){it(22f)}
        complete();assertNull(vm.state.value.result!!.catch);assertEquals(22.0,vm.state.value.draft.position.number()!!,1e-3)
        rule.onNodeWithText("Standing").assertIsSelected();rule.onNodeWithTag("full-span-drawing").performScrollTo();screenshot("phase08-standing.png")
        rule.onNodeWithText("Edit rig").performClick()
        val prefs=WalkerPreferences.from(vm.state.value.draft);val model=ModelPreferences.from(vm.state.value.draft);val rigs=vm.library.value.rigs
        click("reset-rig");click("confirm-reset-rig",false)
        assertEquals(prefs,WalkerPreferences.from(vm.state.value.draft));assertEquals(model,ModelPreferences.from(vm.state.value.draft));assertEquals(rigs,vm.library.value.rigs)
        click("setup-MIXED");rule.onNodeWithText("Start Mixed").performClick();assertEquals(SetupKind.MIXED,vm.state.value.draft.kind)
        click("add-section");assertEquals(2,vm.state.value.draft.sections.size)
        rule.runOnIdle{vm.edit{it.copy(span="100").withUniform(UniformGenerator("100",Selection("RAED",main),Selection("RAED",backup),"5"))}}
        click("edit-individual-sections");assertEquals(100,vm.state.value.draft.sections.size)
        rule.onNodeWithTag("length-0").assertExists();rule.onNodeWithTag("length-99").assertDoesNotExist()
        click("section-editor");rule.onNode(hasText("Search section to edit") and hasSetTextAction()).performTextReplacement("Section 100");click("section-editor-option-Section 100")
        rule.onNodeWithTag("extra-99").performScrollTo().performTextReplacement("7")
        assertEquals("7",vm.state.value.draft.sections[99].extra)
        rule.onNodeWithTag("length-0").assertDoesNotExist()

    }
}
