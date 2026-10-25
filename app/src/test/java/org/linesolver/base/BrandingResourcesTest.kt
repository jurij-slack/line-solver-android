package org.linesolver.base

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.*

class BrandingResourcesTest {
    private val root=File(requireNotNull(System.getProperty("catalogueProjectRoot")))
    private val res=File(root,"app/src/main/res")
    private val ns="http://schemas.android.com/apk/res/android"
    private fun xml(f:File)=DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true}.newDocumentBuilder().parse(f)
    private fun strings(f:File)=xml(f).getElementsByTagName("string").let{nodes->(0 until nodes.length).associate{i->val n=nodes.item(i);n.attributes.getNamedItem("name").nodeValue to n.textContent}}
    @Test fun launcherIdentityAndAllDensityResourcesArePortable() {
        assertEquals("Line Solver",strings(File(res,"values/strings.xml"))["app_name"])
        val app=xml(File(root,"app/src/main/AndroidManifest.xml")).getElementsByTagName("application").item(0) as org.w3c.dom.Element
        assertEquals("@string/app_name",app.getAttributeNS(ns,"label"))
        assertEquals("@mipmap/ic_launcher",app.getAttributeNS(ns,"icon"))
        assertEquals("@mipmap/ic_launcher_round",app.getAttributeNS(ns,"roundIcon"))
        assertTrue(File(root,"app/build.gradle.kts").readText().contains("applicationId = \"org.linesolver.base\""))
        for((density,size) in listOf("mdpi" to 48,"hdpi" to 72,"xhdpi" to 96,"xxhdpi" to 144,"xxxhdpi" to 192))for(name in listOf("ic_launcher","ic_launcher_round")) {
            val image=ImageIO.read(File(res,"mipmap-$density/$name.png"))
            assertEquals(size,image.width);assertEquals(size,image.height)
            assertEquals(0,image.getRGB(0,0).ushr(24));assertEquals(255,image.getRGB(size/2,size/2).ushr(24))
        }
        for(name in listOf("ic_launcher","ic_launcher_round")) {
            val doc=xml(File(res,"mipmap-anydpi-v26/$name.xml"))
            assertEquals("adaptive-icon",doc.documentElement.tagName)
            assertEquals(1,doc.getElementsByTagName("background").length)
            assertEquals(1,doc.getElementsByTagName("foreground").length)
            assertEquals(1,doc.getElementsByTagName("monochrome").length)
        }
    }
    @Test fun originalSafetyWarningsAndAcknowledgmentTextRemainUnchanged() {
        val expected=Json.parseToJsonElement(javaClass.classLoader!!.getResource("branding-safety-baseline.json")!!.readText()).jsonObject
        val current=strings(File(res,"values/safety.xml"))
        expected.forEach{(id,text)->assertEquals(id,text.jsonPrimitive.content,current[id])}
        assertEquals("Line Solver — Important Safety Information",current["safety_title"])
    }
    @Test fun bundledDataNoticeRetainsLicenseAndExactSourceIdentities() {
        val folder=File(root,"app/src/main/assets/licenses")
        val license=File(folder,"SLACKDATA_LICENSE_DATA.txt").readText()
        assertTrue(license.contains("SPDX-License-Identifier: CC-BY-SA-4.0"))
        assertTrue(license.contains("Section 4 -- Sui Generis Database Rights."))
        val notice=File(folder,"SLACKDATA_NOTICE.txt").readText()
        assertTrue(notice.contains("International Slackline Association"));assertTrue(notice.contains("does not license the entire application"))
        for(path in listOf("data/materials/webbings.json","catalogue/webbing-catalogue-v1.json","catalogue/inventory-v1.json")) {
            val hash=MessageDigest.getInstance("SHA-256").digest(File(root,path).readBytes()).joinToString(""){"%02x".format(it)}
            assertTrue(path,notice.contains("$path\nSHA-256: $hash"))
        }
        val values=strings(File(res,"values/strings.xml"))
        assertEquals("https://github.com/International-Slackline-Association/SlackData",values["isa_repository_url"])
        assertEquals("https://github.com/International-Slackline-Association/SlackData/blob/main/LICENSE-DATA",values["isa_data_license_url"])
        assertEquals("https://creativecommons.org/licenses/by-sa/4.0/",values["isa_cc_license_url"])
    }
}
