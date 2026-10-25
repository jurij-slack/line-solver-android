package org.linesolver.base.ui

import org.linesolver.base.model.*
import java.util.Locale

/** Presentation only: ratings retain their source term, never a computed combined capacity. */
enum class ConnectorGroup(val title:String) { quicklinks("Quicklinks"), softShackles("Soft shackles"), custom("Custom") }
fun connectorGroup(s:ConnectorSpec)=if(s.origin==SpecificationOrigin.user_declared)ConnectorGroup.custom else
    if(s.category==ConnectorCategory.oval_maillon)ConnectorGroup.quicklinks else ConnectorGroup.softShackles
private fun compactQuantity(value:Double)=String.format(Locale.ROOT,"%.3f",value).trimEnd('0').trimEnd('.')
fun connectorListLabel(s:ConnectorSpec):String {
    val rating=when { s.mbsN!=null->"MBS ${compactQuantity(s.mbsN/1000)} kN"
        s.blN!=null->"BL ${compactQuantity(s.blN/1000)} kN"
        s.mbsLowerBoundN!=null->"Lower bound ≥ ${compactQuantity(s.mbsLowerBoundN/1000)} kN"
        else->"Strength unknown" }
    return "${connectorName(s)} · $rating"
}
fun connectorName(s:ConnectorSpec):String {
    val size=s.diameterM?.let{" ${compactQuantity(it*1000)} mm"} ?: ""
    return if(s.origin==SpecificationOrigin.user_declared)s.name else when(s.category) {
        ConnectorCategory.oval_maillon->"Quicklink$size"+when {
            s.material.contains("stainless",true)->" inox"
            s.material.contains("zinc",true)->" zinc"
            else->""
        }
        ConnectorCategory.soft_shackle->"Soft shackle$size · "+if(s.ratedConfiguration.contains("double_wrap"))"double wrap" else "single wrap"
        else->s.name
    }
}
fun connectorMatches(s:ConnectorSpec,query:String)=listOf(connectorListLabel(s),s.name,s.manufacturer,s.article.orEmpty(),s.id,s.size,s.ratedConfiguration)
    .any{it.contains(query,true)}
fun connectionComponentNames(d:Draft,j:StructuralJoint)=j.ends.map {
    val section=StructuralConnections.sectionIds(d).indexOf(it.instanceId)
    val bungee=d.bungees?.banks.orEmpty().flatMap{bank->bank.assemblies}.indexOfFirst{a->a.id==it.instanceId}
    if(section>=0)"Section ${section+1}" else "Bungee ${bungee+1}"
}.distinct()
fun connectionOverviewLabel(d:Draft,l:LabeledJoint)="${l.label} · "+connectionComponentNames(d,l.joint).joinToString(" ↔ ")
fun regularConnectorItems(j:StructuralJoint)=j.details.hardware.filterNot{it.specification?.let(ConnectorLibrary::inactive)==true}
fun unresolvedConnectorGroups(j:StructuralJoint)=listOf(HardwareRole.main,HardwareRole.backup).filter { role ->
    regularConnectorItems(j).none{it.role==role && it.productId!=null}
}
