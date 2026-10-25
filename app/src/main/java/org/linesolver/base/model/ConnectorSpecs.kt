package org.linesolver.base.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.linesolver.base.material.catalogue.CatalogueCodec
import java.util.UUID

@Serializable enum class ConnectorCategory { soft_shackle, oval_maillon, rigid_anchor_shackle, sewn_loop, permanent_serial, sewn_transverse, accessory }
@Serializable enum class SpecificationOrigin { manufacturer, user_declared }
@Serializable enum class HardwareRole { unspecified, main, backup, shared }
@Serializable enum class HardwareLoad { unknown, loaded, unloaded }
@Serializable data class LoadAssumption(val status:HardwareLoad=HardwareLoad.unknown,val note:String="",val source:String="user assumption")
@Serializable data class ConnectorSpec(val id:String,val revision:String,val manufacturer:String,val name:String,
    val category:ConnectorCategory,val article:String?=null,val material:String="",val diameterM:Double?=null,val size:String="",
    val massKg:Double?=null,val mbsN:Double?=null,val blN:Double?=null,val mbsLowerBoundN:Double?=null,
    val wllN:Double?=null,val certification:String="not specified",val certifiedClaimN:Double?=null,
    val ratedConfiguration:String,val loadingDirection:String="axial",val sourceUrl:String,val verifiedDate:String,
    val testBasis:String="manufacturer published nominal specification; test date/protocol unspecified",
    val restrictions:String,val uncertainty:String="",val originalUnits:String="",val origin:SpecificationOrigin=SpecificationOrigin.manufacturer) {
    fun identity()=materialFingerprint(CatalogueCodec.canonical(ConnectorLibrary.json.encodeToJsonElement(serializer(),this)))
    fun validate() {
        require(id.isNotBlank() && revision.isNotBlank() && name.isNotBlank() && manufacturer.isNotBlank()) { "Connector needs product/revision/name/manufacturer" }
        require(sourceUrl.isNotBlank() && ratedConfiguration.isNotBlank() && restrictions.isNotBlank()) { "Connector needs source, configuration and usage limitations" }
        require(listOf(massKg,mbsN,blN,mbsLowerBoundN,wllN,certifiedClaimN,diameterM).all{it==null || it.isFinite() && it>=0}) { "Connector SI quantities must be finite and nonnegative" }
        require(listOf(mbsN,blN,mbsLowerBoundN,wllN,certifiedClaimN,diameterM).all{it==null || it>0}) { "Strength and diameter must be positive or unknown" }
        require(origin!=SpecificationOrigin.user_declared || id.startsWith("user:connector:")) { "Custom connectors require a user namespace" }
    }
}
@Serializable data class ConnectorReview(val id:String,val reason:String,val sources:List<String>,val candidateJson:String)
@Serializable data class ConnectorCatalogue(val schemaVersion:Int=1,val products:List<ConnectorSpec>,val review:List<ConnectorReview> = emptyList(),val researchHash:String)
object ConnectorLibrary {
    val json=Json{encodeDefaults=true}
    val shipped:ConnectorCatalogue by lazy { json.decodeFromString<ConnectorCatalogue>(shippedConnectorJson).also { doc ->
        require(doc.schemaVersion==1 && doc.products.map{it.id to it.revision}.distinct().size==doc.products.size)
        doc.products.forEach{it.validate()}
    } }
    fun resolve(item:HardwareItem):ConnectorSpec? {
        if(item.productId==null) { require(item.specification==null && item.fingerprint==null) { "Unbound connector snapshot" };return null }
        val spec=item.specification ?: shipped.products.singleOrNull{it.id==item.productId && it.revision==item.revision}
            ?: error("Connector ${item.productId} revision ${item.revision} unavailable; restore its exact connector snapshot")
        spec.validate()
        require(spec.id==item.productId && spec.revision==item.revision) { "Connector reference/snapshot mismatch" }
        require(item.fingerprint==spec.identity()) { "Connector fingerprint missing or changed; restore the exact revision" }
        if(spec.origin==SpecificationOrigin.manufacturer)require(shipped.products.any{it==spec}) { "Manufacturer connector revision is unavailable or has been modified" }
        return spec
    }
    fun item(spec:ConnectorSpec,id:String=UUID.randomUUID().toString())=HardwareItem(id, spec.id,spec.revision,specification=spec,fingerprint=spec.identity())
    fun custom(name:String,brand:String,category:ConnectorCategory,massKg:Double?,strengthN:Double?,term:String,configuration:String,source:String,limitations:String,
        id:String="user:connector:${UUID.randomUUID()}")=ConnectorSpec(id,UUID.randomUUID().toString(),brand,name,category,
        massKg=massKg,mbsN=if(term=="MBS")strengthN else null,blN=if(term=="BL")strengthN else null,
        ratedConfiguration=configuration,sourceUrl=source,verifiedDate="",testBasis="User-declared specifications; not independently verified",
        restrictions=limitations,origin=SpecificationOrigin.user_declared).also{it.validate();require(term in listOf("MBS","BL"))}
    /** Selection policy only. Legacy snapshots remain resolvable for lossless persistence. */
    fun choices(joint:StructuralJoint,custom:List<ConnectorSpec>)=if(joint.kind==JointKind.anchor)emptyList() else
        (shipped.products+custom).filter { !inactive(it) && when(it.category) {
            ConnectorCategory.accessory->false
            ConnectorCategory.rigid_anchor_shackle->it.origin==SpecificationOrigin.user_declared && joint.kind==JointKind.weblock_termination
            else->true
        } }
    fun inactive(spec:ConnectorSpec)=spec.category==ConnectorCategory.sewn_transverse

}
@Serializable data class CustomConnectorDocument(val schemaVersion:Int=1,val products:List<ConnectorSpec> = emptyList())
class CustomConnectorStore(read:()->String?,private val write:(String)->Unit) {
    var value=CustomConnectorDocument();private set
    var readError:String?=null;private set
    init { try { val candidate=read()?.let{ConnectorLibrary.json.decodeFromString<CustomConnectorDocument>(it)} ?: value;validate(candidate);value=candidate } catch(e:Exception){readError="Custom connector library unreadable; stored data retained: ${e.message}"} }
    private fun validate(d:CustomConnectorDocument) {
        require(d.schemaVersion==1 && d.products.map{it.id to it.revision}.distinct().size==d.products.size)
        d.products.forEach{it.validate();require(it.origin==SpecificationOrigin.user_declared)}
    }
    fun add(spec:ConnectorSpec) { check(readError==null){readError!!};val next=value.copy(products=value.products+spec);validate(next);write(ConnectorLibrary.json.encodeToString(next));value=next }
}

/** Compatibility detection does not migrate or reinterpret any stored declaration. */
fun StructuralJoint.hasInactiveConnectionMetadata()=details.typeX!=null || declaredCrossLine ||
    details.hardware.any{it.specification?.let(ConnectorLibrary::inactive)==true}
fun Draft.hasInactiveConnectionMetadata()=connections?.let{(it.joints+it.detached).any(StructuralJoint::hasInactiveConnectionMetadata)}==true
const val CONNECTION_COMPATIBILITY_WARNING="Unsupported connection metadata is retained unchanged and is inactive in this version."
