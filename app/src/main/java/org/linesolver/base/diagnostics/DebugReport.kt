package org.linesolver.base.diagnostics

import kotlinx.serialization.json.*
import org.linesolver.base.model.*
import org.linesolver.base.material.*
import org.linesolver.base.material.catalogue.*
import org.linesolver.base.energy.*
import org.linesolver.base.solver.*
import org.linesolver.base.ui.walkerDrawing
import org.linesolver.base.ui.sectionForces
import java.time.Instant
import java.util.UUID
import java.io.OutputStream
import java.util.concurrent.CancellationException
import kotlin.math.abs

const val SOLVER_VERSION="simple-threaded-ring-3"
private val codec=Json { allowSpecialFloatingPointValues=true;prettyPrint=true;encodeDefaults=true;ignoreUnknownKeys=true }
/** Strict JSON: invalid numbers retain their diagnostic meaning as strings. */
fun diagnosticJson(value:Any?):JsonElement=when(value) {
    null->JsonNull
    is JsonObject->JsonObject(value.mapValues{diagnosticJson(it.value)})
    is JsonArray->JsonArray(value.map(::diagnosticJson))
    is JsonPrimitive->if(!value.isString && value.doubleOrNull?.isFinite()==false)JsonPrimitive(value.content) else value
    is JsonElement->value
    is Double->if(value.isFinite())JsonPrimitive(value) else JsonPrimitive(value.toString())
    is Float->diagnosticJson(value.toDouble())
    is Number->JsonPrimitive(value)
    is Boolean->JsonPrimitive(value)
    is String->JsonPrimitive(value)
    is Enum<*>->JsonPrimitive(value.name)
    is Map<*,*>->JsonObject(value.entries.associate{it.key.toString() to diagnosticJson(it.value)})
    is Iterable<*>->JsonArray(value.map(::diagnosticJson))
    is DoubleArray->diagnosticJson(value.toList())
    else->error("Unsupported diagnostic value ${value.javaClass.simpleName}")
}
private fun obj(vararg values:Pair<String,Any?>)=diagnosticJson(linkedMapOf(*values)).jsonObject
private fun point(p:Point?)=p?.let{obj("x_m" to it.x,"y_m" to it.y)}
private fun clean(message:String)=message.replace(Regex("/(?:home|tmp|storage|data/user)/[^\\s:]+"),"<private-path>").take(3000)
fun failureCategory(e:Exception):FailureCategory=when(e) {
    is MaterialEvaluationFailure->FailureCategory.MATERIAL_EVALUATION
    is LocatedMaterialLimit,is MaterialLimit->FailureCategory.MATERIAL_LIMIT
    is SolverWarning->e.category
    is IllegalArgumentException->FailureCategory.INVALID_CONFIGURATION
    else->FailureCategory.UNEXPECTED_EXCEPTION
}
private fun energy(s:StaticState):JsonObject {
    fun elastic(c:Cable)=s.network.elements.indices.filter{s.network.elements[it].cable==c}.sumOf{i->
        val e=s.network.elements[i]
        // Trial diagnostics may be beyond the material domain; don't re-evaluate its law.
        runCatching { e.rest*e.material.energy(s.value.strains[i]) }.getOrDefault(Double.NaN)
    }
    return obj("main_elastic_J" to elastic(Cable.MAIN),"backup_elastic_J" to elastic(Cable.BACKUP),
        "elastic_J" to s.value.elastic,"webbing_gravity_J" to s.value.webbingGravitational,
        "walker_gravity_J" to s.value.walkerGravitational,"total_J" to s.value.total).let{data->if(s.network.connectorMasses.isEmpty())data else JsonObject(data+ ("connector_gravity_J" to JsonPrimitive(s.value.connectorGravitational)))}
}
fun stateDiagnostics(s:StaticState):JsonObject {
    val n=s.network;val a=n.attachment;val drawing=s.walkerDrawing()
    fun label(main:Boolean)=a?.let{val c=if(main)it.contacts.main else it.contacts.backup
        obj("node" to if(main)it.mainNode else it.backupNode,"section_zero_based" to c.section,
            "reference_coordinate_m" to c.materialCoordinateM,"threaded" to c.threaded,"mass_coordinate" to (it.support==if(main)Cable.MAIN else Cable.BACKUP),
            "ring_reaction_N" to s.ringReaction(if(main)Cable.MAIN else Cable.BACKUP)?.let{v->listOf(v.x,v.y)})}
    return obj("posture" to n.posture,"support" to a?.support,"main_failed" to (n.broken!=null),
        "main_contact" to label(true),"backup_contact" to label(false),
        "structural_connection_nodes" to n.structuralConnections,"anchor_nodes" to listOf(n.splits.first(),n.splits.last()),
        "cut_endpoint_nodes" to n.broken?.let{listOf(it.first,it.second)},
        "coordinates_xy_m" to s.r.toList().chunked(2),
        "elements" to n.elements.map{e->obj("a" to e.a,"b" to e.b,"cable" to e.cable,"section_zero_based" to e.section,
            "material_id" to ((e.material as? Webbing)?.id ?: ((e.material as? FoldedBungeeMaterial)?.rubber as? Webbing)?.id),"reference_length_m" to e.rest,"mass_kg" to e.mass,
            "reference_fraction_start" to e.from,"reference_fraction_end" to e.to)},
        "attachment" to point(s.attachmentPosition),"harness" to point(drawing?.harness),"walker_COM" to point(drawing?.com),
        "walker_feet" to point(drawing?.feet),"walker_head" to point(drawing?.head),
        "leash_endpoints_xy_m" to drawing?.leash?.let{listOf(point(it.first),point(it.second))},
        "energy" to energy(s),"section_forces" to s.sectionForces().map{obj("section_zero_based" to it.section,"cable" to it.cable,"spatial_maximum_N" to it.maximumN)},
        "force_interpretation" to if(n.bungeePaths.isNotEmpty())"rubber/webbing elastic spatial maxima exclude ideal limiter reactions; see per-bungee reactions and total nodal gradient" else if(n.broken==null)"pre-failure spatial maxima, not historical peaks" else "post-failure spatial maxima, not transient peaks",
        "element_tensions_N" to s.value.tensions,"element_strains" to s.value.strains,
        "free_force_residual_N" to s.residualN,"nodal_gradient_N" to s.value.gradient,
        "iterations" to s.iterations).let { original -> val data=if(n.connectorMasses.isEmpty())original else JsonObject(original+ ("connector_masses" to diagnosticJson(n.connectorMasses.map{obj("item_id" to it.itemId,"node" to it.node,"mass_kg" to it.massKg)})))
            if(n.bungeePaths.isEmpty())data else JsonObject(data+mapOf(
            "bungees" to diagnosticJson(s.bungeeResults().map{b->obj("instance_id" to b.id,"path_length_m" to b.pathLengthM,"strain" to b.strain,
                "rubber_min_N" to b.rubberMinN,"rubber_max_N" to b.rubberMaxN,"limiter_active" to b.limiterActive,"limiter_reaction_N" to b.limiterReactionN,
                "mass_kg" to b.massKg,"rubber_energy_J" to b.elasticJ)}),
            "total_nodal_gradient_with_limiters_N" to diagnosticJson(s.totalGradient()),
            "limiter_violation_m" to diagnosticJson(s.constraintViolationM))) }
}
data class DiagnosticReport(val document:JsonObject) {
    fun text()=codec.encodeToString(JsonObject.serializer(),document)
    fun withDisplayedState(name:String)=copy(document=JsonObject(document+ ("visualization_state" to JsonPrimitive(if(name in document.getValue("states").jsonObject)name else "unavailable"))))
    val status get()=document.getValue("calculation_status").jsonPrimitive.content
}
data class DiagnosticRun(val result:StaticResult?,val failure:Exception?,val report:DiagnosticReport)

/** One path for UI, tests and replay: the original Draft parser and the actual solver. */
object DiagnosticRunner {
    fun run(draft:Draft,materials:MaterialRepository,settings:SolverSettings=SolverSettings(),
        appVersion:String="development",checkCancelled:()->Unit={},standing:StaticResult?=null):DiagnosticRun {
        val trace=SolverTrace();var result:StaticResult?=null;var failure:Exception?=null
        try {
            val validation=draft.validate(materials)
            require(validation.setup!=null){validation.errors.joinToString("; ")}
            if(draft.mode!=Mode.SIMPLE)throw SolverWarning("Advanced sliding is not implemented",FailureCategory.UNSUPPORTED_MODEL)
            result=SimpleSolver(materials,checkCancelled=checkCancelled,settings=settings,trace=trace).solveFrom(validation.setup,standing)
        } catch(e:CancellationException){throw e}
        catch(e:Exception){failure=e}
        return DiagnosticRun(result,failure,report(draft,materials,settings,trace,result,failure,appVersion))
    }
    fun report(draft:Draft,materials:MaterialRepository,settings:SolverSettings,trace:SolverTrace,
        result:StaticResult?,failure:Exception?,appVersion:String="development"):DiagnosticReport {
        val bungeeIds=draft.bungees?.banks.orEmpty().flatMap{it.assemblies.map{a->a.model.modelId}}
        val ids=(draft.materialSections.flatMap{listOf(it.main.materialId,it.backup.materialId)}+bungeeIds).filter{it.isNotEmpty()}.distinct()
        val snapshots=JsonObject(ids.mapNotNull{id->runCatching{id to materials.get(id).metadata}.getOrNull()}.toMap())
        val states=result?.let{linkedMapOf("standing" to it.standing).apply{it.catch?.let{put("catch",it)};it.rest?.let{put("rest",it)}}}?:trace.states
        val warnings=(result?.warnings.orEmpty()+states.flatMap{(name,s)->buildList {
            if(s.residualN>settings.forceToleranceN)add("$name free-force residual exceeds the requested tolerance")
            if(!s.value.total.isFinite() || s.r.any{!it.isFinite()})add("$name has non-finite energy/geometry")
            if(s.network.elements.indices.any{s.value.strains[it]>s.network.elements[it].material.measuredMax})add("$name: unmeasured material continuation used")
            val a=s.network.attachment
            if(a!=null && a.mainNode==a.backupNode && a.node !in s.network.splits)add("$name: non-structural main/backup coupling")
            if(s.network.posture==WalkerPosture.LEASH_SUPPORTED && a?.support!=Cable.BACKUP)add("$name: failed-state support is not backup")
            s.walkerDrawing()?.let{w->if(w.leash!=null && w.head.y+w.headRadiusM>=w.attachment.y-1e-12)add("$name: upright head reaches backup height; no clearance is modeled")}
        }}).distinct()
        val ledger=result?.fallEnergy
        val failureJson=failure?.let{obj("category" to failureCategory(it),"stage" to trace.stage,"message" to clean(it.message?:it.javaClass.simpleName),
            "exception" to it.javaClass.name,"physical_material_limit" to ((it as? LocatedMaterialLimit)?.limit?.rupture?:(it as? MaterialLimit)?.rupture?:false),
            "stack" to it.stackTrace.filter{f->f.className.startsWith("org.linesolver")}.take(12).map{f->"${f.className}.${f.methodName}:${f.lineNumber}"})}
        return DiagnosticReport(obj("schema_version" to if(bungeeIds.isEmpty())1 else 2,"report_id" to UUID.randomUUID().toString(),"timestamp" to Instant.now().toString(),
            "app_version" to appVersion,"solver_version" to SOLVER_VERSION,"calculation_status" to if(failure==null)"SUCCESS" else "FAILED",
            "failure" to failureJson,"units" to "SI: m, kg, N, J, s; y upward; section/node indices zero-based",
            "rig_configuration" to obj("draft" to codec.encodeToJsonElement(Draft.serializer(),draft),
                "installed_main_lengths_m" to (result?.installedLengths?:trace.installedLengths),"achieved_pretension_N" to (result?.achievedPretensionN?:trace.achievedPretensionN),
                "failure_model" to "prescribed main cut; fixed-label ideal ring retains intact main and backup contacts; same-section cut releases interior main contact"),
            "walker_configuration" to obj("mass_kg_input" to draft.mass,"main_reference_position_m_input" to draft.position,
                "body_height_m_input" to draft.walkerHeight,"physical_leash_m_input" to draft.leash,
                "standing_COM_fraction" to .55,"harness_fraction" to .5,"hanging_COM_above_harness_fraction" to .05),
            "solver_settings" to obj("gravity_m_s2_input" to draft.gravity,"elements_per_section_input" to draft.elements,
                "contact_resistance_N_input" to draft.resistance,"model_connections" to draft.modelConnections,
                "connection_effects" to "Structural joints always active; optional known hardware mass when enabled; individual load sharing and rupture unsupported",
                "show_bungee_options" to draft.showBungeeOptions,"forceToleranceN" to settings.forceToleranceN,"maxIterations" to settings.maxIterations,
                "catchRootToleranceJ" to settings.catchRootToleranceJ,"energyAuditToleranceJ" to settings.energyAuditToleranceJ,
                "catchStepM" to settings.catchStepM,"maxCatchSteps" to settings.maxCatchSteps,"maxRootIterations" to settings.maxRootIterations),
            "material_data" to obj("format" to "webbing_material_models_v1","law" to
                if(snapshots.values.any{it.jsonObject["model_origin"]?.jsonPrimitive?.contentOrNull=="imported"})
                    "tension-only PCHIP or provisional linear with analytic integral; imported catalogue_max_strain is a hard bound without upper continuation; legacy continuation remains as recorded"
                else "tension-only PCHIP or explicit provisional one-point linear with analytic integral; bounded linear MBS continuation reconstructed from snapshot metadata","models" to snapshots),
            "connection_topology" to obj("rule" to "Only anchors and physical section splits share node IDs; engaged intact ring contacts impose removable position constraints on independent fixed labels",
                "connection_policies_active" to false,"contact_sliding_active" to false),
            "connector_diagnostics" to if(draft.modelConnections)states.mapValues{it.value.connectorDiagnostics(draft).map{h->obj("joint_id" to h.jointId,"item_id" to h.itemId,"role" to h.role,"status" to h.assumedLoad.status,"status_source" to h.assumedLoad.source,"assumption_note" to h.assumedLoad.note,"associated_port_tension_N" to h.associatedPortTensionN,"individual_load_N" to h.individualLoadN,"comparison" to h.comparison,"warning" to h.warning)}} else emptyMap<String,String>(),
            "states" to states.mapValues{stateDiagnostics(it.value)},"visualization_state" to "standing",
            "energy_ledger" to obj("initial_J" to result?.initialEnergyJ,"turning_J" to result?.catch?.value?.total,"residual_J" to result?.catchResidualJ,
                "walker_gravity_released_J" to ledger?.walkerGravityReleasedJ,"webbing_gravity_released_J" to ledger?.webbingGravityReleasedJ,
                "elastic_increase_J" to ledger?.elasticIncreaseJ,"ordinary_friction_J" to 0.0,"engagement_dissipation_J" to 0.0,"relaxation_loss_J" to result?.relaxationLossJ).let{data->if(result?.standing?.network?.connectorMasses.isNullOrEmpty())data else JsonObject(data+("connector_gravity_released_J" to diagnosticJson(ledger?.connectorGravityReleasedJ)))},
            "numerical_diagnostics" to obj("stage" to trace.stage,"termination_reason" to (failure?.message?.let(::clean)?:"Converged within force and energy criteria"),
                "iteration_evaluations" to trace.evaluations,"catch_drop_bracket_m" to trace.bracket?.let{listOf(it.first,it.second)},
                "history_limit" to 64,"history" to trace.history.map{obj("stage" to it.stage,"iteration" to it.iteration,"residual_N" to it.residualN,"total_J" to it.energyJ)},
                "last_valid_equilibrium" to trace.lastValid?.let(::stateDiagnostics),
                "last_iterate_not_necessarily_converged" to if(failure!=null)trace.lastTrial?.let(::stateDiagnostics) else null),
            "warnings" to warnings).let { data -> if(bungeeIds.isEmpty())data else {
                val catalogue=(materials as? CatalogueMaterialRepository)?.document
                val models=catalogue?.models.orEmpty().filter{it.id in bungeeIds}
                JsonObject(data+mapOf("bungee_model_records" to JsonArray(models.map{CatalogueCodec.json.encodeToJsonElement(it)}),
                    "bungee_measurement_records" to JsonArray(catalogue?.datasets.orEmpty().filter{d->models.any{d.id in it.datasetIds}}.map{CatalogueCodec.json.encodeToJsonElement(it)}),
                    "replay_limitation" to JsonPrimitive("Bungee report replay is unsupported; use saved rig schema 3 and material-package import for exact library restoration.")))
            } })
    }
    fun replay(text:String):DiagnosticRun {
        require(text.length<=2_000_000){"Report exceeds 2 MB reader bound"}
        val root=codec.parseToJsonElement(text).jsonObject
        require(root.getValue("schema_version").jsonPrimitive.int==1){"Unsupported report schema; bungee diagnostic replay is not implemented"}
        require(root.getValue("solver_version").jsonPrimitive.content==SOLVER_VERSION){"Solver version differs; use the original source revision before comparing"}
        val draft=codec.decodeFromJsonElement(Draft.serializer(),root.getValue("rig_configuration").jsonObject.getValue("draft"))
        val settings=codec.decodeFromJsonElement(SolverSettings.serializer(),root.getValue("solver_settings"))
        val materials=JsonMaterialRepository(root.getValue("material_data").toString())
        return run(draft,materials,settings)
    }
}
/** UI supplies the SAF stream. A null/throwing provider is an export error, never success. */
fun writeDebugReport(text:String,open:()->OutputStream?):Result<Unit> = runCatching {
    val stream=open()?:throw java.io.IOException("Document provider did not open a stream")
    stream.use{it.write(text.toByteArray(Charsets.UTF_8));it.flush()}
}

fun failureMessage(category:FailureCategory):String=when(category) {
    FailureCategory.INVALID_CONFIGURATION->"Check the rig, walker and numerical inputs."
    FailureCategory.NON_CONVERGENCE->"The solver did not converge. Save a debug report for investigation."
    FailureCategory.ROOT_FINDING->"The solver could not locate a consistent solution."
    FailureCategory.NON_FINITE->"The calculation produced an invalid numerical value."
    FailureCategory.MATERIAL_EVALUATION->"A material curve could not be evaluated."
    FailureCategory.MATERIAL_LIMIT->"The calculation reached a material model limit."
    FailureCategory.ENERGY_CONSISTENCY->"The calculation did not satisfy the energy checks."
    FailureCategory.UNSUPPORTED_MODEL->"This configuration is outside the supported model."
    FailureCategory.UNEXPECTED_EXCEPTION->"An unexpected calculation error occurred."
}
