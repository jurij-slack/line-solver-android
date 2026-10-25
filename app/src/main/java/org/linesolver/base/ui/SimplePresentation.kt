package org.linesolver.base.ui

import org.linesolver.base.model.*
import org.linesolver.base.material.*
import org.linesolver.base.solver.*
import org.linesolver.base.energy.*
import java.util.Locale

/** Display precision only. Never round a value passed back into the model. */
fun uiNumber(value:Double,decimals:Int=2):String {
    if(!value.isFinite())return "Unknown"
    val rounded=String.format(Locale.ROOT,"%.${decimals}f",value)
    val clean=if(decimals>0)rounded.trimEnd('0').trimEnd('.') else rounded
    return if(clean=="-0")"0" else clean
}
fun forceKn(value:Double)="${uiNumber(value/1000)} kN"
fun energyKj(value:Double)="${uiNumber(value/1000)} kJ"
fun lengthM(value:Double)="${uiNumber(value)} m"
fun displayWarning(text:String)=Regex("(?<![A-Za-z0-9_])(-?\\d+\\.\\d{4,})(?=\\s*(?:%|kN|kJ|m\\b|kg\\b|N\\b|J\\b|[–]))")
    .replace(text){uiNumber(it.value.toDouble())}

/** Do not reinterpret or edit an advanced installed assembly in the simple editor. */
fun simpleConnectionEditable(j:StructuralJoint):Boolean = !j.hasInactiveConnectionMetadata() &&
    j.details.hardware.all { h -> h.productId!=null && runCatching{ConnectorLibrary.resolve(h)}.getOrNull()!=null && h.role in listOf(HardwareRole.main,HardwareRole.backup) &&
        h.attachmentPorts.isEmpty() && h.configuration.isBlank() && h.standingLoad==LoadAssumption() &&
        h.failedLoad==LoadAssumption() && !h.ratedInstallationConfirmed } &&
    listOf(HardwareRole.main,HardwareRole.backup).all{role->j.details.hardware.count{it.role==role}<=1}
fun simpleHardwareSummary(h:HardwareItem?):String {
    if(h==null)return "Idealized · no added mass or strength rating"
    val s=runCatching{ConnectorLibrary.resolve(h)}.getOrNull() ?: return "Properties unavailable"
    val mass=s.massKg?.let{"${uiNumber(it*1000)} g"} ?: "Mass unknown"
    val rating=when {s.mbsN!=null->"MBS ${forceKn(s.mbsN)}";s.blN!=null->"BL ${forceKn(s.blN)}"
        s.mbsLowerBoundN!=null->"Lower bound ≥ ${forceKn(s.mbsLowerBoundN)}";else->"Strength unknown"}
    return "$mass · $rating"
}
fun walkerRepositionAllowed(failureApplied:Boolean)=!failureApplied

data class MaterialUseNotice(val id:String,val name:String,val operatingMin:Double,val operatingMax:Double,
    val supplied:SuppliedDomain?,val extrapolated:Boolean,val assumption:String) {
    fun details()=buildString {
        append("$name\nOperating strain: ${uiNumber(operatingMin*100)}–${uiNumber(operatingMax*100)} %\n")
        append(supplied?.let{"Supplied range: ${uiNumber(it.lower*100)}–${uiNumber(it.upper*100)} %"} ?: "Supplied range unknown")
        append("\n$assumption")
    }
}
fun materialUseNotices(values:List<Pair<Material,Double>>):List<MaterialUseNotice> = values.filter{it.first is Webbing && it.second.isFinite() && it.second>0}
    .groupBy{(it.first as Webbing).id}.mapNotNull{(id,uses)->
        val m=uses.first().first as Webbing;val domain=m.suppliedDomain();val strains=uses.map{it.second}
        val outside=domain!=null && strains.any{!domain.contains(it)}
        if(!outside && domain!=null && !m.provisionalLinear)return@mapNotNull null
        MaterialUseNotice(id,m.name,strains.min(),strains.max(),domain,outside,displayWarning(when {
            m.provisionalLinear->m.modelWarning ?: "Provisional linear approximation; source support is limited."
            domain==null->"Source support cannot be established."
            else->"Extrapolated outside supplied data. ${m.extrapolationReason}"
        }))
    }
fun materialWarningSummary(notices:List<MaterialUseNotice>):String {
    val count=notices.count{it.extrapolated}
    return if(count>0)"Material extrapolation: $count ${if(count==1)"curve" else "curves"} outside supplied range."+if(notices.size>count)" ${notices.size-count} additional source assumptions." else ""
        else "Material assumptions: ${notices.size} ${if(notices.size==1)"curve" else "curves"} with limited source support."
}
fun StaticResult.materialNotices()=materialUseNotices(listOfNotNull(standing,catch,rest).flatMap{s->s.network.elements.mapIndexed{i,e->e.material to s.value.strains[i]}})
fun StaticState.cableMaximum(cable:Cable)=network.elements.indices.filter{network.elements[it].cable==cable}.maxOfOrNull{value.tensions[it]} ?: 0.0
fun StaticState.walkerSag(d:Draft):Double?=attachmentPosition?.let{a->(d.leftHeight.number()?:0.0)*(1-a.x/network.span)+(d.rightHeight.number()?:0.0)*a.x/network.span-a.y}
