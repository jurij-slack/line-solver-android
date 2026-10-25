package org.linesolver.base

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.linesolver.base.ui.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Run directly after a verified current-data backup; screenshots are cache-only. */
class BrandingPhoneTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private fun click(tag:String)=rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick){it()}
    private fun screenshot(name:String) {
        rule.waitForIdle()
        val inst=InstrumentationRegistry.getInstrumentation()
        File(inst.targetContext.cacheDir,"branding-$name.png").outputStream().use{inst.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun safetyOrderAcknowledgmentNavigationAndExternalLicenseLinks() {
        rule.onNodeWithTag("accept-safety").assertIsDisplayed();rule.onNodeWithTag("solve").assertDoesNotExist()
        val context=rule.activity
        val intro=context.getString(R.string.safety_intro);val heading=context.getString(R.string.webbing_data_acknowledgement_title);val warning=context.getString(R.string.safety_estimates)
        val order=rule.onAllNodes(hasText(intro) or hasText(heading) or hasText(warning)).fetchSemanticsNodes().map{n->n.config[SemanticsProperties.Text].joinToString(""){it.text}}
        assertEquals(listOf(intro,heading,warning),order)
        rule.onNodeWithTag("webbing-data-acknowledgement").performScrollTo().assertIsDisplayed();screenshot("safety")
        rule.activityRule.scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()}
        rule.onNodeWithTag("accept-safety").assertExists()
        rule.activityRule.scenario.recreate();rule.onNodeWithTag("accept-safety").assertExists()
        click("accept-safety");rule.onNodeWithText("Line Solver").assertExists()
        click("more-menu");click("about-menu")
        rule.onNodeWithText(context.getString(R.string.data_sources_licensing_title)).performScrollTo().assertIsDisplayed()
        screenshot("about-data")
        val opened=CopyOnWriteArrayList<String>()
        val monitor=object:Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? {
                if(intent.action==Intent.ACTION_VIEW && intent.data?.scheme=="https") {
                    opened+=intent.dataString!!
                    return Instrumentation.ActivityResult(Activity.RESULT_OK,null)
                }
                return null
            }
        }
        val inst=InstrumentationRegistry.getInstrumentation();inst.addMonitor(monitor)
        try {
            for(tag in listOf("isa-repository","isa-data-license","isa-cc-license"))rule.onNodeWithTag(tag).performScrollTo().performClick()
            assertEquals(listOf(context.getString(R.string.isa_repository_url),context.getString(R.string.isa_data_license_url),context.getString(R.string.isa_cc_license_url)),opened.toList())
        } finally {inst.removeMonitor(monitor)}
        rule.onNodeWithText(context.getString(R.string.isa_license_scope)).performScrollTo().assertExists();screenshot("license-scope")
        rule.onNodeWithText("Line Solver").performScrollTo().assertExists()
        rule.onNodeWithTag("about-safety").performScrollTo().performClick()
        rule.onNodeWithTag("close-safety").assertExists();rule.onNodeWithTag("accept-safety").assertDoesNotExist()
        rule.onNodeWithTag("webbing-data-acknowledgement").assertExists()
        click("close-safety");rule.onNodeWithTag("about-page").assertExists()
        click("close-about");rule.onNodeWithTag("solve").assertExists()
    }
    @Test fun attributionFitsSmallScreensInDarkAndLightThemes() {
        for(dark in listOf(false,true)) {
            rule.activityRule.scenario.onActivity{activity->activity.setContent {
                val density=LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density,1.3f)) {
                    MaterialTheme(colorScheme=if(dark)darkColorScheme() else lightColorScheme()) {
                        Box(Modifier.width(320.dp).fillMaxHeight()){SafetyScreen(false){}}
                    }
                }
            }}
            for(id in listOf(R.string.webbing_data_acknowledgement_title,R.string.webbing_data_acknowledgement,R.string.webbing_data_no_endorsement)) {
                val text=rule.activity.getString(id);val node=rule.onNodeWithText(text).performScrollTo()
                val layouts=mutableListOf<TextLayoutResult>()
                repeat(2){layouts.clear();node.performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)};rule.waitForIdle()}
                assertTrue(layouts.isNotEmpty());assertTrue(text,layouts.all{!it.hasVisualOverflow})
            }
            screenshot(if(dark)"safety-dark-small" else "safety-light-small")
            rule.activityRule.scenario.onActivity{activity->activity.setContent {
                val density=LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density,1.3f)) {
                    MaterialTheme(colorScheme=if(dark)darkColorScheme() else lightColorScheme()) {
                        Box(Modifier.width(320.dp).fillMaxHeight()){AboutScreen{}}
                    }
                }
            }}
            rule.onNodeWithTag("isa-cc-license").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText(rule.activity.getString(R.string.isa_license_scope)).performScrollTo().assertIsDisplayed()
            screenshot(if(dark)"about-dark-small" else "about-light-small")
        }
    }
    @Test fun packageIconUsesAdaptiveLayersAndMonochromeWithUnchangedApplicationId() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val info=context.packageManager.getApplicationInfo(context.packageName,0)
        assertEquals("org.linesolver.base",context.packageName);assertEquals("Line Solver",info.loadLabel(context.packageManager).toString())
        val published=info.loadIcon(context.packageManager)
        val icon=context.getDrawable(info.icon)
        val value=android.util.TypedValue();context.resources.getValue(info.icon,value,true)
        File(context.cacheDir,"branding-icon-metadata.txt").writeText("Package icon: ${published.javaClass.name}\nManifest resource: ${icon?.javaClass?.name}\nResource path: ${value.string}\nLabel: ${info.loadLabel(context.packageManager)}\n")
        val publishedBitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
        published.setBounds(0,0,256,256);published.draw(Canvas(publishedBitmap))
        File(context.cacheDir,"branding-launcher-facing-icon.png").outputStream().use{publishedBitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        assertTrue("Published package icon must be adaptive: ${published.javaClass.name}",published is AdaptiveIconDrawable)
        assertTrue("Raw manifest icon must be adaptive: ${icon?.javaClass?.name} (${value.string})",icon is AdaptiveIconDrawable)
        val adaptive=icon as AdaptiveIconDrawable
        val size=256;val sheet=Bitmap.createBitmap(size*3,size*2,Bitmap.Config.ARGB_8888);val sheetCanvas=Canvas(sheet);sheetCanvas.drawColor(Color.LTGRAY)
        val masks=listOf(Path().apply{addCircle(size/2f,size/2f,size/2f,Path.Direction.CW)},
            Path().apply{addRoundRect(0f,0f,size.toFloat(),size.toFloat(),size*.22f,size*.22f,Path.Direction.CW)},
            Path().apply{for(i in 0..360){val a=i*Math.PI/180;val c=kotlin.math.cos(a);val s=kotlin.math.sin(a);val x=(size/2+size/2*kotlin.math.sign(c)*kotlin.math.sqrt(kotlin.math.abs(c))).toFloat();val y=(size/2+size/2*kotlin.math.sign(s)*kotlin.math.sqrt(kotlin.math.abs(s))).toFloat();if(i==0)moveTo(x,y)else lineTo(x,y)};close()})
        for((index,mask) in masks.withIndex()) {
            val bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);val c=Canvas(bitmap)
            c.clipPath(mask)
            val bounds=Rect(-size/4,-size/4,size*5/4,size*5/4)
            adaptive.background.bounds=bounds;adaptive.foreground.bounds=bounds;adaptive.background.draw(c);adaptive.foreground.draw(c)
            var cyan=0;var gold=0
            for(y in 0 until size)for(x in 0 until size){val p=bitmap.getPixel(x,y);if(Color.green(p)>160 && Color.blue(p)>145 && Color.red(p)<110)cyan++;if(Color.red(p)>180 && Color.green(p)>110 && Color.blue(p)<150)gold++}
            assertTrue("Mainline/anchors missing in mask $index",cyan>20);assertTrue("Walker missing in mask $index",gold>10)
            sheetCanvas.drawBitmap(bitmap,index*size.toFloat(),0f,null)
            if(Build.VERSION.SDK_INT>=33) {
                val mono=adaptive.monochrome;assertNotNull(mono)
                val themed=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);val t=Canvas(themed);t.clipPath(mask);t.drawColor(Color.rgb(218,233,240))
                mono!!.mutate().setTint(Color.rgb(20,63,86));mono.bounds=bounds;mono.draw(t)
                sheetCanvas.drawBitmap(themed,index*size.toFloat(),size.toFloat(),null)
            }
        }
        File(context.cacheDir,"branding-android-icon-masks.png").outputStream().use{sheet.compress(Bitmap.CompressFormat.PNG,100,it)}
        assertTrue(context.assets.open("licenses/SLACKDATA_LICENSE_DATA.txt").bufferedReader().readText().contains("CC-BY-SA-4.0"))
    }
}
