package org.linesolver.base.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.linesolver.base.energy.Cable
import org.linesolver.base.model.Draft
import org.linesolver.base.solver.StaticState

@Composable fun InformationDisclosure(title:String,tag:String,content:@Composable ColumnScope.()->Unit) {
    var expanded by rememberSaveable{mutableStateOf(false)}
    OutlinedCard(Modifier.fillMaxWidth()) {
        TextButton(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag(tag)) {
            Text("${if(expanded)"▾" else "▸"} $title")
        }
        if(expanded)Column(Modifier.padding(start=12.dp,end=12.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=content)
    }
}
@Composable private fun Metric(label:String,value:String,modifier:Modifier=Modifier) {
    Column(modifier.padding(10.dp)) {
        Text(label,style=MaterialTheme.typography.labelLarge,modifier=Modifier.fillMaxWidth())
        Text(value,style=MaterialTheme.typography.headlineSmall,modifier=Modifier.fillMaxWidth())
    }
}
@Composable fun CompactResults(state:StaticState,d:Draft) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("compact-results")) {
        Column(Modifier.padding(4.dp)) {
            Row {
                Metric("Mainline",forceKn(state.cableMaximum(Cable.MAIN)),Modifier.weight(1f).testTag("metric-main"))
                Metric("Backup",forceKn(state.cableMaximum(Cable.BACKUP)),Modifier.weight(1f).testTag("metric-backup"))
            }
            Row {
                Metric("Walker sag",state.walkerSag(d)?.let(::lengthM) ?: "Unknown",Modifier.weight(1f).testTag("metric-sag"))
                Metric("Elastic energy",energyKj(state.value.elastic),Modifier.weight(1f).testTag("metric-energy"))
            }
            if(!d.bungees?.banks.isNullOrEmpty())Text("Elastic forces; limiter reactions in Detailed Results.",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(horizontal=10.dp))
            state.walkerElevations?.let{Text("Walker COM height: ${lengthM(it.comM)}",style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(10.dp).testTag("metric-com"))}
        }
    }
}
@Composable fun MaterialWarningInformation(notices:List<MaterialUseNotice>) {
    if(notices.isNotEmpty())InformationDisclosure(materialWarningSummary(notices),"material-warnings") {
        notices.forEach{Text(it.details(),style=MaterialTheme.typography.bodySmall)}
        Text("Supplied data may include fitted curves; extrapolated results are not experimentally validated.",style=MaterialTheme.typography.bodySmall)
    }
}
