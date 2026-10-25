package org.linesolver.base

import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import kotlinx.serialization.json.*
import org.linesolver.base.diagnostics.DiagnosticRunner
import java.io.File
import kotlin.math.abs

/** Run with -PdebugReport=/absolute/path/to/report.json. Output is a sibling .replayed.json. */
class ReplayReportTest {
    @Test fun reproduceExportedReport() {
        val path=System.getProperty("debugReport")
        assumeTrue("Supply -PdebugReport to replay an external report",!path.isNullOrBlank())
        val file=File(path!!);val text=file.readText()
        val original=Json.parseToJsonElement(text).jsonObject
        val replay=DiagnosticRunner.replay(text).report
        File(file.parentFile,file.nameWithoutExtension+".replayed.json").writeText(replay.text())
        assertEquals(original.getValue("calculation_status"),replay.document.getValue("calculation_status"))
        fun compare(a:JsonElement,b:JsonElement,path:String) {
            when(a) {
                is JsonObject->{assertEquals(path,a.keys,b.jsonObject.keys);a.forEach{(k,v)->compare(v,b.jsonObject.getValue(k),"$path.$k")}}
                is JsonArray->{assertEquals(path,a.size,b.jsonArray.size);a.indices.forEach{compare(a[it],b.jsonArray[it],"$path[$it]")}}
                is JsonPrimitive->{val x=a.doubleOrNull;val y=(b as? JsonPrimitive)?.doubleOrNull
                    if(x!=null && y!=null && x.isFinite() && y.isFinite())assertEquals(path,x,y,1e-7+abs(x)*1e-9)
                    else assertEquals(path,a,b)}
            }
        }
        for(key in listOf("states","energy_ledger"))compare(original.getValue(key),replay.document.getValue(key),key)
        if(original.getValue("failure") is JsonObject) {
            for(key in listOf("category","stage"))assertEquals(original.getValue("failure").jsonObject[key],replay.document.getValue("failure").jsonObject[key])
        }
    }
}
