package org.linesolver.base.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.linesolver.base.R

/** Process memory only. Never serialize acceptance into saved state or preferences. */
object SafetySession {
    var accepted by mutableStateOf(false);private set
    fun freshLaunch(){accepted=false}
    fun accept(){accepted=true}
}

@Composable private fun SafetyText() {
    val paragraphs=listOf(R.string.safety_intro to false,R.string.safety_estimates to true,
        R.string.safety_real_world to false,R.string.safety_energy to false,R.string.safety_decisions to true,
        R.string.safety_risk to false,R.string.safety_own_risk to true,R.string.safety_acknowledgment to false)
    Text(stringResource(R.string.safety_title),style=MaterialTheme.typography.headlineSmall,modifier=Modifier.semantics{heading()})
    paragraphs.forEach{(id,bold)->
        Text(stringResource(id),fontWeight=if(bold)FontWeight.Bold else FontWeight.Normal,
            modifier=Modifier.testTag(if(id==R.string.safety_intro)"safety-intro" else if(id==R.string.safety_estimates)"safety-estimates" else "safety-paragraph-$id"))
        if(id==R.string.safety_intro)WebbingDataAcknowledgement()
    }
}
@Composable private fun WebbingDataAcknowledgement() {
    Column(verticalArrangement=Arrangement.spacedBy(4.dp),modifier=Modifier.testTag("webbing-data-acknowledgement")) {
        Text(stringResource(R.string.webbing_data_acknowledgement_title),style=MaterialTheme.typography.titleSmall,modifier=Modifier.fillMaxWidth().semantics{heading()})
        Text(stringResource(R.string.webbing_data_acknowledgement),style=MaterialTheme.typography.bodySmall,modifier=Modifier.fillMaxWidth())
        Text(stringResource(R.string.webbing_data_no_endorsement),style=MaterialTheme.typography.bodySmall,modifier=Modifier.fillMaxWidth())
    }
}

@Composable fun DataSourcesAndLicensing() {
    val uri=LocalUriHandler.current
    Text(stringResource(R.string.data_sources_licensing_title),style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics{heading()})
    Text(stringResource(R.string.isa_slackdata_title),style=MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.isa_slackdata_description))
    Text(stringResource(R.string.isa_slackdata_license),fontWeight=FontWeight.SemiBold,modifier=Modifier.fillMaxWidth())
    val links=listOf(Triple(R.string.isa_repository_label,R.string.isa_repository_url,"isa-repository"),
        Triple(R.string.isa_data_license_label,R.string.isa_data_license_url,"isa-data-license"),
        Triple(R.string.isa_cc_license_label,R.string.isa_cc_license_url,"isa-cc-license"))
    Column {
        links.forEach{(label,target,tag)->
            val url=stringResource(target)
            TextButton(onClick={uri.openUri(url)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag(tag)) {
                Text(stringResource(label),modifier=Modifier.fillMaxWidth())
            }
        }
    }
    Text(stringResource(R.string.isa_other_sources))
    Text(stringResource(R.string.isa_license_scope))
    Text(stringResource(R.string.isa_modifications),style=MaterialTheme.typography.bodySmall)
}

@Composable fun SafetyScreen(required:Boolean,onContinue:()->Unit) {
    // A required notice consumes Back; there is no solver UI behind it.
    BackHandler {if(!required)onContinue()}
    Scaffold(bottomBar={Surface(shadowElevation=6.dp){Box(Modifier.navigationBarsPadding().padding(16.dp)){
        Button(onClick=onContinue,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag(if(required)"accept-safety" else "close-safety")){
            Text(if(required)stringResource(R.string.safety_accept) else stringResource(R.string.back_to_about))
        }
    }}}){padding->
        Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp).testTag("safety-notice"),verticalArrangement=Arrangement.spacedBy(16.dp)) {SafetyText()}
    }
}
@Composable fun ApplicationInformation(version:String,contact:String,developer:String) {
    Text(stringResource(R.string.application_information),style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics{heading()})
    Text(stringResource(R.string.app_name))
    Text(stringResource(R.string.application_version,version),modifier=Modifier.testTag("app-version"))
    if(developer.isNotBlank())Text(developer,modifier=Modifier.testTag("developer-name"))
    SelectionContainer {Text(if(contact.isBlank())stringResource(R.string.developer_contact_unconfigured) else contact,modifier=Modifier.testTag("developer-contact"))}
    Text(stringResource(R.string.application_libraries))
}
@Composable fun AboutScreen(onClose:()->Unit) {
    var safety by rememberSaveable{mutableStateOf(false)}
    if(safety){SafetyScreen(false){safety=false};return}
    val context=LocalContext.current
    val installedVersion=remember(context){context.packageManager.getPackageInfo(context.packageName,0).versionName}
    val version=installedVersion?:stringResource(R.string.version_unknown)
    BackHandler(onBack=onClose)
    Scaffold(topBar={Surface(tonalElevation=2.dp){Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween){
        Text(stringResource(R.string.about_title),style=MaterialTheme.typography.headlineSmall)
        TextButton(onClick=onClose,modifier=Modifier.testTag("close-about")){Text(stringResource(R.string.close_about))}
    }}}){padding->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp).testTag("about-page"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.about_solver_title),style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics{heading()})
            Text(stringResource(R.string.about_solver_description))
            Text(stringResource(R.string.about_limitations_title),style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics{heading()})
            Text(stringResource(R.string.about_limitations_validation))
            Text(stringResource(R.string.about_limitations_simple))
            Text(stringResource(R.string.about_limitations_bounds))
            OutlinedButton(onClick={safety=true},modifier=Modifier.testTag("about-safety")){Text(stringResource(R.string.about_full_safety))}
            Text(stringResource(R.string.about_material_collaboration_title),style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics{heading()})
            Text(stringResource(R.string.about_material_collaboration))
            DataSourcesAndLicensing()
            ApplicationInformation(version,stringResource(R.string.developer_contact),stringResource(R.string.developer_name))
        }
    }
}
