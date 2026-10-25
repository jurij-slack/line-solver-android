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
class UniformPhoneTest {
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
        if(tag in listOf("save-rig","saved-rigs","edit-individual-sections","setup-UNIFORM"))node.performScrollTo()
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
    @Test fun generateConvertEditSave() {
        initialize()
        // Start a new recipe without deleting the existing library or preferences.
        rule.runOnIdle{vm.edit{freshGeneratorDraft().copy(mass="80",position="12",walkerHeight="1.8",leash="1.5")}}
        input("span","35");input("left-height","0");input("right-height","0");input("pretension","1.5")
        input("uniform-count","5");input("extra-0","5")
        fun choose(tag:String,name:String) {
            rule.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
            rule.onNodeWithTag("$tag-option-$name").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
        }
        choose("main-0-manufacturer","RAED");choose("main-0-webbing","Parsec")
        choose("backup-0-manufacturer","RAED");choose("backup-0-webbing","MOTM")
        rule.onNodeWithTag("uniform-summary").performScrollTo().assertTextContains("5 sections × 7.0000 m",substring=true)
        val uniform=vm.state.value.draft;assertEquals(5,uniform.activeSections.size)
        screenshot("phase05-generator.png")
        val base="Phone uniform ${System.currentTimeMillis()}";save(base)
        val uniformId=vm.library.value.selectedId!!
        calculate();val before=vm.state.value.report!!.document
        file("phase05-uniform.json").writeText(vm.state.value.report!!.text())
        rule.onNodeWithText("Edit rig").performClick();click("edit-individual-sections")
        assertEquals(uniform.validate(vm.materials).setup,vm.state.value.draft.validate(vm.materials).setup)
        rule.onNodeWithTag("rig-dirty").performScrollTo().assertTextEquals("Unsaved changes")
        calculate();assertEquals(before.getValue("states"),vm.state.value.report!!.document.getValue("states"))
        file("phase05-converted.json").writeText(vm.state.value.report!!.text())
        rule.onNodeWithText("Edit rig").performClick();input("extra-2","7")
        val customized=vm.state.value.draft
        click("setup-UNIFORM");click("cancel-uniform");assertEquals(customized,vm.state.value.draft)
        click("setup-UNIFORM");click("confirm-uniform");assertNotEquals(customized,vm.state.value.draft)
        // Restore the tested individual edit, then save through the ordinary library UI.
        rule.runOnIdle{vm.edit{customized}}
        save("$base mixed");val mixedId=vm.library.value.selectedId!!
        calculate();assertNotEquals(before.getValue("states"),vm.state.value.report!!.document.getValue("states"))
        file("phase05-edited.json").writeText(vm.state.value.report!!.text())
        file("phase05-ids.txt").writeText("$uniformId\n$mixedId")
        screenshot("phase05-edited.png")
    }
    @Test fun restartLoadBothAndCalculate() {
        initialize();val ids=file("phase05-ids.txt").readLines()
        for((i,id) in ids.withIndex()) {
            val walker=WalkerPreferences.from(vm.state.value.draft);val model=ModelPreferences.from(vm.state.value.draft)
            click("saved-rigs");click("load-$id")
            assertEquals(walker,WalkerPreferences.from(vm.state.value.draft));assertEquals(model,ModelPreferences.from(vm.state.value.draft))
            if(i==0) {assertEquals("5",vm.state.value.draft.uniform!!.count);assertTrue(vm.state.value.draft.sections.isEmpty())}
            else {assertNull(vm.state.value.draft.uniform);assertEquals("7",vm.state.value.draft.sections[2].extra)}
            calculate()
            val previous=Json.parseToJsonElement(file(if(i==0)"phase05-uniform.json" else "phase05-edited.json").readText()).jsonObject
            assertEquals(previous.getValue("states"),vm.state.value.report!!.document.getValue("states"))
            file("phase05-reloaded-$i.json").writeText(vm.state.value.report!!.text())
            rule.onNodeWithText("Edit rig").performClick()
        }
        rule.onNodeWithTag("length-2").performScrollTo();screenshot("phase05-reloaded.png")
    }
}
