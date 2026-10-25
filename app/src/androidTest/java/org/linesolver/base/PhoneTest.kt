package org.linesolver.base

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

class PhoneTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @org.junit.Before fun acknowledgeSession(){rule.acceptSafetyIfShown()}
    private fun input(tag:String,value:String){rule.onNodeWithTag(tag).performScrollTo().performClick().performTextReplacement(value)}
    private fun choose(tag:String,name:String) {
        // Invoke the button's accessibility action: IME bring-into-view animation
        // can move its touch centre immediately after automated text replacement.
        rule.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
        rule.waitUntil(10000){rule.onAllNodesWithTag("$tag-option-$name").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("$tag-option-$name").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
    }
    private fun screenshot(name:String) {
        rule.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        // Compose idleness alone does not wait for the OS rotation/surface animation.
        instrumentation.uiAutomation.waitForIdle(1000,5000)
        instrumentation.uiAutomation.takeScreenshot().useBitmap { bitmap ->
            File(instrumentation.targetContext.getExternalFilesDir(null),name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
    }
    private fun Bitmap.useBitmap(action:(Bitmap)->Unit) {try{action(this)}finally{recycle()}}
    private fun exportWithPicker() {
        rule.onNodeWithTag("export-diagnostics").performScrollTo().performClick()
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun findSave(node:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
            if(node==null)return null
            if(node.isClickable && node.text?.toString()?.lowercase() in listOf("save","speichern"))return node
            for(i in 0 until node.childCount)findSave(node.getChild(i))?.let{return it}
            return null
        }
        val deadline=android.os.SystemClock.uptimeMillis()+15000
        while(findSave(automation.rootInActiveWindow)==null && android.os.SystemClock.uptimeMillis()<deadline)
            android.os.SystemClock.sleep(100)
        check(findSave(automation.rootInActiveWindow)!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        rule.waitUntil(15000){rule.onAllNodesWithText("Debug report saved.").fetchSemanticsNodes().isNotEmpty()}
    }
    @Test fun editSelectDragRotateAndSolveOnPhone() {
        rule.onNodeWithTag("solve").assertIsNotEnabled()
        rule.onNodeWithTag("main-0-webbing").performScrollTo().assertIsNotEnabled()
        input("span","35");input("left-height","0");input("right-height","0");input("uniform-count","1")
        input("mass","80");input("pretension","1.5");input("position","12");input("extra-0","5")
        choose("main-0-manufacturer","RAED");choose("main-0-webbing","Parsec")
        choose("backup-0-manufacturer","RAED");choose("backup-0-webbing","MOTM")
        rule.onNodeWithTag("walker-slider").performScrollTo().performTouchInput{swipeLeft(startX=right*.65f,endX=right*.4f)}
        input("position","12")
        rule.onNodeWithTag("mode-ADVANCED").performScrollTo().performClick();rule.onNodeWithTag("solve").assertIsNotEnabled()
        rule.onNodeWithTag("mode-SIMPLE").performClick();rule.onNodeWithTag("setup-MIXED").performClick()
        rule.onNodeWithTag("add-section").performScrollTo().performClick();rule.onNodeWithTag("length-1").assertExists()
        rule.onNodeWithTag("setup-UNIFORM").performScrollTo().performClick()
        rule.onNodeWithTag("confirm-uniform").performSemanticsAction(SemanticsActions.OnClick){it()}
        input("uniform-count","1")
        rule.activityRule.scenario.recreate();rule.onNodeWithTag("span").performScrollTo().assertTextContains("35")
        rule.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
        rule.waitUntil(10000){rule.activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE}
        rule.onNodeWithTag("solve").assertIsEnabled()
        screenshot("landscape.png")
        rule.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        rule.waitUntil(10000){rule.activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT}
        rule.onNodeWithTag("solve").performClick()
        rule.waitUntil(120000){rule.onAllNodesWithTag("result-status").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithTag("solver-issue").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("result-status").assertTextEquals("OK · calculation complete").assertIsDisplayed()
        screenshot("equilibrium.png")
        rule.onNodeWithText("Edit rig").performClick();rule.onNodeWithTag("cut-enabled").performScrollTo().performClick()
        input("cut-position","26");input("walker-height","1.8");input("leash","1.5")
        rule.onNodeWithTag("solve").performClick()
        rule.waitUntil(120000){rule.onAllNodesWithTag("result-status").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithTag("solver-issue").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("result-status").assertTextEquals("OK · calculation complete").assertIsDisplayed()
        rule.onNodeWithText("Catch").performSemanticsAction(SemanticsActions.OnClick){it()}
        rule.onNodeWithText("Catch").assertIsSelected()
        rule.onNodeWithTag("hanging-detail").assertDoesNotExist()
        rule.onNodeWithTag("full-span-drawing").performScrollTo().assertIsDisplayed()
        screenshot("catch.png")
        rule.onNodeWithTag("detailed-results").performScrollTo().performClick()
        rule.onNodeWithText("Post-failure main spatial maximum:",substring=true).performScrollTo().assertExists()
        rule.onNodeWithText("Walker COM:",substring=true).performScrollTo().assertTextContains("leash supported",substring=true)
        rule.onNodeWithText("Walker gravity released:",substring=true).performScrollTo().assertExists()
        rule.onNodeWithText("Backup elastic:",substring=true).performScrollTo().assertExists()
        screenshot("energy.png")
        rule.onNodeWithTag("close-details").performScrollTo().performClick()
        rule.onNodeWithText("Rest").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
        rule.onNodeWithTag("full-span-drawing").performScrollTo().assertIsDisplayed()
        screenshot("rest.png")
        exportWithPicker()
        rule.onNodeWithText("Standing").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
        rule.onNodeWithTag("detailed-results").performScrollTo().performClick()
        rule.onNodeWithText("Walker COM:",substring=true).performScrollTo().assertTextContains("standing",substring=true)
        rule.onNodeWithTag("close-details").performScrollTo().performClick()
        rule.onNodeWithText("Edit rig").performClick()
        input("mass","-1")
        rule.onNodeWithText("Diagnose invalid inputs").performScrollTo().performClick()
        rule.waitUntil(10000){rule.onAllNodesWithTag("export-diagnostics").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("export-diagnostics").assertTextEquals("Save Debug Report")
        exportWithPicker()
        input("mass","80")
        rule.activityRule.scenario.onActivity { activity ->
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken,0)
        }
        rule.onNodeWithText("Walker",useUnmergedTree=true).performScrollTo()
        screenshot("walker.png")
        rule.onNodeWithTag("solver-settings").performClick()
        screenshot("model-settings.png")
        rule.onNodeWithTag("close-settings").performClick()
    }
}
