package org.linesolver.base

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.linesolver.base.model.*
import org.linesolver.base.ui.SetupViewModel
import java.io.File

/** Execute separately with force-stop between methods; only creates its own uniquely named test rigs. */
class RigLibraryPhoneTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @org.junit.Before fun acknowledgeSession(){rule.acceptSafetyIfShown()}
    private lateinit var vm:SetupViewModel
    private fun initialize() {
        rule.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[SetupViewModel::class.java];it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        rule.waitUntil(10000){rule.activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT}
    }
    private fun click(tag:String) {
        if(tag.startsWith("load-") || tag.startsWith("more-"))rule.onNodeWithTag("rig-list").performScrollToNode(hasTestTag(tag))
        val node=rule.onNodeWithTag(tag)
        if(tag in listOf("save-rig","saved-rigs"))node.performScrollTo()
        node.performSemanticsAction(SemanticsActions.OnClick){it()}
    }
    private fun input(tag:String,value:String){
        val node=rule.onNodeWithTag(tag)
        if(tag !in listOf("rig-name","rig-search"))node.performScrollTo()
        node.performTextReplacement(value)
    }
    private fun file(name:String)=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),name)
    private fun waitSaved(revision:Int) {rule.waitUntil(10000){vm.library.value.revision>revision || vm.library.value.error!=null};assertNull(vm.library.value.error);rule.waitForIdle()}
    private fun save(name:String) {val revision=vm.library.value.revision;click("save-rig");input("rig-name",name);click("confirm-rig-name");waitSaved(revision)}
    private fun menu(id:String,label:String) {click("more-$id");rule.onNodeWithText(label).performSemanticsAction(SemanticsActions.OnClick){it()}}
    private fun screenshot(name:String) {
        rule.activityRule.scenario.onActivity{(it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(it.window.decorView.windowToken,0)}
        rule.waitForIdle();val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(500,5000);val bitmap=automation.takeScreenshot()
        try{file(name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
    }
    private fun calculate() {
        click("solve")
        rule.waitUntil(120000){vm.state.value.result!=null || vm.state.value.issue!=null}
        assertNull(vm.state.value.issue);rule.onNodeWithTag("result-status").assertExists()
    }
    @Test fun saveManageAndPrepareRestart() {
        initialize()
        val materials=vm.materials
        fun selection(product:String)=Selection("RAED",materials.webbings("RAED").first{it.product==product}.id)
        val initial=Draft(span="35",leftHeight="0",rightHeight="0",mass="80",position="12",pretensionKn="1.5",
            sections=listOf(SectionDraft("35",selection("Parsec"),selection("MOTM"),"5")),cutEnabled=true,cutPosition="26",walkerHeight="1.8",leash="1.5")
        rule.runOnIdle{vm.edit{initial}}
        val base="Phone rig ${System.currentTimeMillis()}"
        save(base)
        val a=vm.library.value.selectedId!!
        rule.onNodeWithTag("rig-dirty").assertTextEquals("Saved")
        input("pretension","1.6")
        rule.onNodeWithTag("rig-dirty").performScrollTo().assertTextEquals("Unsaved changes")
        click("save-rig");click("update-rig")
        assertEquals("1.5",vm.library.value.rigs.first{it.id==a}.configuration.pretensionKn)
        val revision=vm.library.value.revision;click("confirm-update-rig");waitSaved(revision)
        assertEquals(a,vm.library.value.selectedId)
        assertEquals("1.6",vm.library.value.rigs.first{it.id==a}.configuration.pretensionKn)
        input("pretension","1.7");save("$base variant")
        val b=vm.library.value.selectedId!!;assertNotEquals(a,b)
        click("save-rig");input("rig-name",base);click("update-rig");click("confirm-update-rig")
        rule.waitUntil(10000){vm.library.value.error!=null}
        rule.onNodeWithTag("update-rig-error").assertTextContains("already exists",substring=true)
        assertEquals("1.6",vm.library.value.rigs.first{it.id==a}.configuration.pretensionKn)
        click("cancel-update-rig");click("cancel-save-rig")
        input("pretension","1.8");click("saved-rigs");click("load-$a")
        rule.onNodeWithTag("discard-and-load").assertExists();screenshot("phase04-unsaved.png")
        click("cancel-rig-load");assertEquals("1.8",vm.state.value.draft.pretensionKn)
        click("load-$a");click("discard-and-load")
        assertEquals("1.6",vm.state.value.draft.pretensionKn)
        click("saved-rigs");menu(b,"Rename");input("rig-name","$base renamed")
        val renameRevision=vm.library.value.revision;click("confirm-rig-name");waitSaved(renameRevision)
        menu(b,"Duplicate");input("rig-name","$base temporary")
        val duplicateRevision=vm.library.value.revision;click("confirm-rig-name");waitSaved(duplicateRevision)
        val c=vm.library.value.rigs.first{it.name=="$base temporary"}.id
        input("rig-search","$base temporary")
        menu(c,"Delete");assertTrue(vm.library.value.rigs.any{it.id==c})
        val deleteRevision=vm.library.value.revision;click("confirm-delete-rig");waitSaved(deleteRevision)
        assertFalse(vm.library.value.rigs.any{it.id==c})
        input("rig-search",base);rule.onNodeWithTag("rig-search").performImeAction();screenshot("phase04-library.png");click("close-library")
        // Non-rig inputs deliberately differ from the values used when the rig was saved.
        input("mass","83");input("walker-height","1.9");input("leash","1.6");input("position","13")
        click("solver-settings");input("gravity","9.7");input("elements","6");click("close-settings")
        rule.onNodeWithTag("rig-dirty").performScrollTo().assertTextEquals("Saved")
        rule.activityRule.scenario.recreate()
        rule.onNodeWithTag("current-rig").performScrollTo().assertTextEquals(base)
        calculate()
        file("phase04-before.json").writeText(vm.state.value.report!!.text())
        file("phase04-rig-id.txt").writeText(a)
    }
    @Test fun restartLoadAndCalculate() {
        initialize()
        val a=file("phase04-rig-id.txt").readText()
        assertEquals("83",vm.state.value.draft.mass);assertEquals("9.7",vm.state.value.draft.gravity)
        val walker=WalkerPreferences.from(vm.state.value.draft);val settings=ModelPreferences.from(vm.state.value.draft)
        click("saved-rigs");click("load-$a")
        assertEquals(walker,WalkerPreferences.from(vm.state.value.draft));assertEquals(settings,ModelPreferences.from(vm.state.value.draft))
        assertEquals("1.6",vm.state.value.draft.pretensionKn)
        rule.onNodeWithTag("rig-dirty").performScrollTo().assertTextEquals("Saved")
        screenshot("phase04-loaded.png")
        calculate()
        val before=Json.parseToJsonElement(file("phase04-before.json").readText()).jsonObject
        assertEquals(before.getValue("states"),vm.state.value.report!!.document.getValue("states"))
        assertEquals(before.getValue("energy_ledger"),vm.state.value.report!!.document.getValue("energy_ledger"))
        file("phase04-after.json").writeText(vm.state.value.report!!.text())
        screenshot("phase04-calculated.png")
    }
}
