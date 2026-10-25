package org.linesolver.base

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class SafetyPhoneTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private fun click(tag:String,scroll:Boolean=false) {
        val node=rule.onNodeWithTag(tag);if(scroll)node.performScrollTo()
        node.performSemanticsAction(SemanticsActions.OnClick){it()}
    }
    private fun file(name:String)=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"branding-"+name)
    private fun screenshot(name:String){
        rule.waitForIdle();val ui=InstrumentationRegistry.getInstrumentation().uiAutomation;ui.waitForIdle(500,5000)
        val bitmap=ui.takeScreenshot();try{file(name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
    }
    private fun assertBlocked(){rule.onNodeWithTag("accept-safety").assertIsDisplayed();rule.onNodeWithTag("solve").assertDoesNotExist();rule.onNodeWithTag("more-menu").assertDoesNotExist()}
    @Test fun acknowledgeNavigateAndPrepareRestart() {
        assertBlocked()
        rule.activityRule.scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()};assertBlocked()
        rule.activityRule.scenario.recreate();assertBlocked();screenshot("phase07-startup.png")
        rule.onNodeWithText("Highline at your own risk.").performScrollTo().assertExists();screenshot("phase07-warning.png")
        click("accept-safety");rule.onNodeWithTag("solve").assertExists()
        rule.activityRule.scenario.recreate();rule.onNodeWithTag("accept-safety").assertDoesNotExist()
        click("solver-settings");click("close-settings");click("more-menu");click("about-menu")
        rule.onNodeWithText("About the Solver").assertExists()
        screenshot("phase07-about.png")
        click("about-safety",true);rule.onNodeWithTag("safety-notice").assertExists();rule.onNodeWithTag("accept-safety").assertDoesNotExist()
        rule.activityRule.scenario.recreate();rule.onNodeWithTag("close-safety").assertExists()
        click("close-safety");rule.onNodeWithTag("about-page").assertExists()
        rule.onNodeWithTag("app-version").performScrollTo().assertTextEquals("Version "+rule.activity.packageManager.getPackageInfo(rule.activity.packageName,0).versionName)
        val contact=rule.activity.getString(R.string.developer_contact)
        rule.onNodeWithTag("developer-contact").performScrollTo().assertTextEquals(if(contact.isBlank())"Developer contact has not been configured." else contact)
        screenshot("phase07-information.png")
        rule.activityRule.scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()}
        rule.onNodeWithTag("solve").assertExists();rule.onNodeWithTag("accept-safety").assertDoesNotExist()
        file("phase07-accepted.txt").writeText("accepted before force-stop")
    }
    /** Invoke after the method above, with adb force-stop between invocations. */
    @Test fun noticeReturnsAfterProcessRestart() {
        assertTrue(file("phase07-accepted.txt").exists());assertBlocked()
        screenshot("phase07-restart.png");click("accept-safety");rule.onNodeWithTag("solve").assertExists()
    }
    @Test fun newRootTaskRequiresAcknowledgmentEvenInSameProcess() {
        assertBlocked();click("accept-safety");rule.activityRule.scenario.close()
        ActivityScenario.launch(MainActivity::class.java).use{assertBlocked();click("accept-safety");rule.onNodeWithTag("solve").assertExists()}
    }
}
