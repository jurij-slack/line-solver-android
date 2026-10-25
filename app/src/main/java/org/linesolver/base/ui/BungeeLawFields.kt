package org.linesolver.base.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.linesolver.base.material.catalogue.*
import org.linesolver.base.model.number

@Composable fun BungeeLawFields(edit:MaterialEdit,change:(MaterialEdit)->Unit) {
    val law=edit.bungee ?: BungeeLaw(basis=BungeeBasis.physical_cord,testedPhysicalLengthM=0.0,source=edit.source)
    fun update(next:BungeeLaw){change(edit.copy(bungee=next))}
    Text("Bungee measurement basis · static loading approximation")
    var open by remember{mutableStateOf(false)}
    OutlinedButton(onClick={open=true}){Text("Basis: ${law.basis}")}
    DropdownMenu(expanded=open,onDismissRequest={open=false}){BungeeBasis.entries.forEach{basis->DropdownMenuItem(text={Text(basis.name)},onClick={update(law.copy(basis=basis,scalablePerLength=false));open=false})}}
    var reference by rememberSaveable(edit.datasetId){mutableStateOf(law.testedPhysicalLengthM.takeIf{it>0}?.toString().orEmpty())}
    var mass by rememberSaveable(edit.datasetId){mutableStateOf(law.totalMassKg?.toString().orEmpty())}
    OutlinedTextField(reference,{reference=it;update(law.copy(testedPhysicalLengthM=it.number() ?: 0.0))},label={Text("Tested physical unfolded reference L0/m")},modifier=Modifier.fillMaxWidth().testTag("bungee-measured-rest"))
    OutlinedTextField(law.testedLegs.toString(),{update(law.copy(testedLegs=it.toIntOrNull() ?: 0))},label={Text("Measured assembly equal-leg count")},modifier=Modifier.fillMaxWidth())
    OutlinedTextField(mass,{mass=it;update(law.copy(totalMassKg=if(it.isBlank())null else it.number() ?: -1.0))},label={Text("Measured total physical mass/kg (blank = unknown)")},modifier=Modifier.fillMaxWidth().testTag("bungee-measured-mass"))
    OutlinedTextField(law.rubberLayers?.toString().orEmpty(),{update(law.copy(rubberLayers=if(it.isBlank())null else it.toIntOrNull() ?: 0))},label={Text("Internal rubber layers (metadata; no force multiplication)")},modifier=Modifier.fillMaxWidth())
    Row{Checkbox(law.idealEqualLegs,{update(law.copy(idealEqualLegs=it))});Text("Allow ideal equal legs; negligible bend/termination length")}
    Row{Checkbox(law.scalablePerLength,{update(law.copy(scalablePerLength=it))});Text("Documented scalable per-length physical-cord law")}
    if(law.scalablePerLength)OutlinedTextField(law.scalingEvidence,{update(law.copy(scalingEvidence=it))},label={Text("Evidence for length scaling (required)")},modifier=Modifier.fillMaxWidth())
    OutlinedTextField(law.source,{update(law.copy(source=it))},label={Text("Source for measurement basis/reference configuration")},modifier=Modifier.fillMaxWidth())
    Text("Whole-cord/folded assembly observations stay restricted to the tested length. Strand/layer curves require a verified assembly conversion and cannot run. Enter strain relative to tested installed separation L0/legs; loading branch/preconditioning belongs in protocol. Finite-stiffness limiters are unavailable without a sourced law.")
}
