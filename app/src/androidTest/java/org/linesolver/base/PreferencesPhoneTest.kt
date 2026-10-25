package org.linesolver.base

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.linesolver.base.ui.SetupViewModel
import androidx.lifecycle.SavedStateHandle
import android.app.Application
import java.io.File
import android.graphics.Bitmap

/** Run the two methods separately with adb force-stop between them to verify process persistence. */
class PreferencesPhoneTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @org.junit.Before fun acknowledgeSession(){rule.acceptSafetyIfShown()}
    private fun input(tag:String,value:String) {rule.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)}
    private fun freshDraft()=SetupViewModel(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application,SavedStateHandle()).state.value.draft
    private fun screenshot(name:String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        rule.waitForIdle();instrumentation.uiAutomation.waitForIdle(500,5000)
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        try {File(instrumentation.targetContext.getExternalFilesDir(null),name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
    }
    @Test fun editPreferencesBeforeProcessRestart() {
        rule.onNodeWithTag("gravity").assertDoesNotExist()
        input("mass","83");input("walker-height","1.9");input("leash","1.6");input("position","13")
        screenshot("walker-settings.png")
        rule.onNodeWithTag("mode-ADVANCED").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
        rule.onNodeWithTag("mode-ADVANCED").assertIsSelected()
        rule.onNodeWithTag("solver-settings").performClick()
        input("gravity","9.7");input("elements","6");input("resistance","310")
        assertEquals("9.7",freshDraft().gravity)
        input("gravity","0");rule.onNodeWithTag("settings-errors").performScrollTo().assertExists()
        assertEquals("9.7",freshDraft().gravity)
        rule.onNodeWithTag("close-settings").performClick()
        rule.onNodeWithTag("solver-settings").performClick()
        rule.onNodeWithTag("gravity").assertTextContains("9.7")
        screenshot("solver-settings.png")
        rule.activityRule.scenario.recreate()
        rule.onNodeWithTag("gravity").assertTextContains("9.7")
        rule.onNodeWithTag("close-settings").performClick()
        rule.onNodeWithTag("mass").performScrollTo().assertTextContains("83")
    }
    @Test fun verifyAfterProcessRestartAndRestoreDefaults() {
        // A new instrumentation/app process with a new SavedStateHandle reads the actual preferences.
        val restored=freshDraft()
        assertEquals("83",restored.mass);assertEquals("1.9",restored.walkerHeight)
        assertEquals("1.6",restored.leash);assertEquals("13",restored.position)
        assertEquals("9.7",restored.gravity);assertEquals("6",restored.elements);assertEquals("310",restored.resistance)
        rule.onNodeWithTag("mass").performScrollTo().assertTextContains("83")
        rule.onNodeWithTag("walker-height").performScrollTo().assertTextContains("1.9")
        rule.onNodeWithTag("leash").performScrollTo().assertTextContains("1.6")
        rule.onNodeWithTag("position").performScrollTo().assertTextContains("13")
        rule.onNodeWithTag("solver-settings").performClick()
        rule.onNodeWithTag("gravity").assertTextContains("9.7")
        rule.onNodeWithTag("elements").assertTextContains("6")
        rule.onNodeWithTag("restore-solver-defaults").performScrollTo().performClick()
        rule.onNodeWithTag("gravity").performScrollTo().assertTextContains("9.81")
        rule.onNodeWithTag("close-settings").performClick()
        val defaults=freshDraft()
        assertEquals("9.81",defaults.gravity);assertEquals("8",defaults.elements);assertEquals("200",defaults.resistance)
        assertEquals("83",defaults.mass)
        rule.onNodeWithTag("gravity").assertDoesNotExist()
    }
}
