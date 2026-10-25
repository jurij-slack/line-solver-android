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
import org.linesolver.base.ui.SetupViewModel

class MaterialEditorPhoneTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private fun click(tag:String)=rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick){it()}
    private fun scroll(tag:String) { rule.onNodeWithTag("my-materials-list").performScrollToNode(hasTestTag(tag)) }
    private fun field(tag:String,text:String) { scroll(tag);rule.onNodeWithTag(tag).performTextReplacement(text) }
    private fun screenshot(name:String) {
        rule.waitForIdle()
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        try { java.io.File(instrumentation.targetContext.getExternalFilesDir(null),name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        } } finally { bitmap.recycle() }
    }
    @Test fun createCurveReviewRevisionSelectorAndRecreation() {
        rule.acceptSafetyIfShown()
        lateinit var vm:SetupViewModel
        rule.activityRule.scenario.onActivity { vm=ViewModelProvider(it)[SetupViewModel::class.java] }
        val original=vm.state.value.draft;val originalEdit=vm.materialEdit;val originalLibrary=vm.materialStore.value
        val savedRigs=vm.library.value.rigs
        try {
            click("more-menu");click("my-materials")
            scroll("new-material");click("new-material");click("confirm-material-action")
            field("editor-name","Synthetic phone fixture only");field("editor-brand","Phone test")
            field("editor-mass","0.05");field("editor-mbs","30000")
            scroll("add-point");click("add-point")
            field("point-force-0","1");field("point-strain-0","1")
            scroll("add-point");click("add-point")
            field("point-force-1","2");field("point-strain-1","2.5")
            scroll("add-point");click("add-point")
            field("point-force-2","3");field("point-strain-2","4.5")
            rule.onNodeWithTag("point-strain-2").performImeAction()
            rule.waitUntil(20000) { vm.materialEdit.table.contains("4.5") }
            assertNotNull("Entered points should construct a native law: ${vm.materialEdit}",vm.previewMaterial(vm.materialEdit).second)
            scroll("save-material")
            rule.waitUntil(20000) { rule.onAllNodesWithTag("editor-preview").fetchSemanticsNodes().isNotEmpty() }
            scroll("editor-preview");rule.onNodeWithTag("editor-preview").assertExists()
            scroll("editor-legend");rule.onNodeWithTag("editor-legend").assertTextContains("Imposed synthetic origin",substring=true)
            screenshot("editor-preview.png")
            scroll("save-material");click("save-material")
            rule.waitUntil(20000) { !vm.materialBusy && vm.materialStore.value.catalogue.models.size==originalLibrary.catalogue.models.size+1 }
            val first=vm.materialStore.value.catalogue.models.last();val d=vm.materialStore.value.catalogue.datasets.last()
            rule.runOnIdle { vm.editMaterial(MaterialEditing.load(MaterialPackages.combine(vm.materialStore.shipped,vm.materialStore.value),d,first.id).copy(table="1,1\n2,1")) }
            scroll("save-material")
            rule.waitUntil(20000) { rule.onAllNodesWithTag("editor-no-model").fetchSemanticsNodes().isNotEmpty() }
            scroll("editor-no-model");rule.onNodeWithTag("editor-no-model").assertTextContains("Review only",substring=true)
            screenshot("editor-review.png")
            scroll("save-material");click("save-material")
            rule.waitUntil(20000) { !vm.materialBusy && vm.materialStore.value.catalogue.datasets.size==originalLibrary.catalogue.datasets.size+2 }
            assertEquals(originalLibrary.catalogue.models.size+1,vm.materialStore.value.catalogue.models.size)
            rule.runOnIdle { vm.editMaterial(MaterialEditing.load(MaterialPackages.combine(vm.materialStore.shipped,vm.materialStore.value),d,first.id).copy(table="1,1.1\n2,2.7\n3,4.7")) }
            scroll("save-material")
            rule.waitUntil(20000) { rule.onAllNodesWithTag("editor-preview").fetchSemanticsNodes().isNotEmpty() }
            scroll("editor-preview");scroll("save-material");click("save-material")
            rule.waitUntil(20000) { !vm.materialBusy && vm.materialStore.value.catalogue.models.size==originalLibrary.catalogue.models.size+2 }
            val second=vm.materialStore.value.catalogue.models.last()
            val backup=java.io.File(rule.activity.getExternalFilesDir(null),"phase03-roundtrip.json")
            rule.runOnIdle { vm.exportMaterials(android.net.Uri.fromFile(backup)) }
            rule.waitUntil(20000) { !vm.materialBusy && backup.exists() && backup.length()>0 }
            val count=vm.materialStore.value.catalogue.models.size
            rule.runOnIdle { vm.importMaterials(android.net.Uri.fromFile(backup),false) }
            rule.waitUntil(20000) { !vm.materialBusy && vm.materialMessage?.startsWith("Material package imported")==true }
            assertEquals(count,vm.materialStore.value.catalogue.models.size)
            val csv=java.io.File(rule.activity.getExternalFilesDir(null),"phase03-measurement.csv")
            rule.runOnIdle { vm.exportMaterials(android.net.Uri.fromFile(csv),d.id) }
            rule.waitUntil(20000) { !vm.materialBusy && csv.exists() && csv.length()>0 }
            assertEquals(d.points,MeasurementCsv.parse(vm.materialEdit.copy(table=csv.readText())).points)
            click("close-my-materials")
            rule.runOnIdle { vm.edit { original.copy(span="35").withUniform(UniformGenerator("1",Selection("Phone test",first.id),Selection("Phone test",first.id),"5")) } }
            rule.onNodeWithTag("main-0-model").performScrollTo();click("main-0-model")
            rule.onNodeWithText(vm.materials.get(second.id).modelChoiceLabel()).performClick()
            assertEquals(second.id,vm.state.value.draft.uniform!!.main.materialId);assertEquals(first.id,vm.state.value.draft.uniform!!.backup.materialId)
            rule.runOnIdle {
                // Cold initialization: no retained ViewModel and no activity SavedStateHandle draft.
                val fresh=SetupViewModel(rule.activity.application,androidx.lifecycle.SavedStateHandle())
                assertEquals(second.id,fresh.state.value.draft.uniform!!.main.materialId)
                assertEquals(first.id,fresh.state.value.draft.uniform!!.backup.materialId)
                assertEquals(first.contentFingerprint,fresh.materialStore.value.catalogue.models.single { it.id==first.id }.contentFingerprint)
                assertEquals(vm.materialEdit.table,fresh.materialEdit.table)
            }
            rule.activityRule.scenario.recreate()
            rule.activityRule.scenario.onActivity { vm=ViewModelProvider(it)[SetupViewModel::class.java] }
            assertEquals(second.id,vm.state.value.draft.uniform!!.main.materialId)
            assertEquals(first.id,vm.state.value.draft.uniform!!.backup.materialId)
            assertEquals(savedRigs,vm.library.value.rigs)
        } finally {
            rule.runOnIdle { vm.edit { original };vm.editMaterial(originalEdit);vm.materialStore.commit(originalLibrary) }
            rule.activityRule.scenario.recreate()
        }
    }
}
