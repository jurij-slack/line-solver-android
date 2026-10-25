plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
abstract class MaterialAssets : DefaultTask() {
    @get:InputFile abstract val database: RegularFileProperty
    @get:InputDirectory abstract val catalogue: DirectoryProperty
    @get:OutputDirectory abstract val output: DirectoryProperty
    @TaskAction fun copyDatabase() {
        val directory=output.get().asFile
        directory.mkdirs()
        database.get().asFile.copyTo(directory.resolve("webbing_material_models_v1.json"), overwrite=true)
        catalogue.get().asFile.resolve("webbing-catalogue-v1.json").takeIf { it.exists() }
            ?.copyTo(directory.resolve("webbing-catalogue-v1.json"), overwrite=true)
    }
}
val materialAssets = tasks.register<MaterialAssets>("syncMaterialDatabase") {
    database.set(rootProject.layout.projectDirectory.file("data/materials/webbing_material_models_v1.json"))
    catalogue.set(rootProject.layout.projectDirectory.dir("catalogue"))
    output.set(layout.buildDirectory.dir("generated/materialAssets"))
}
android {
    namespace = "org.linesolver.base"
    compileSdk = 37
    defaultConfig {
        applicationId = "org.linesolver.base"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["test"].resources.srcDir(rootProject.file("reference"))
    sourceSets["test"].resources.srcDir(layout.buildDirectory.dir("generated/materialAssets").get().asFile)
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(materialAssets, MaterialAssets::output)
}
tasks.named("preBuild") { dependsOn(materialAssets) }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Keep report replay on the same JVM test classpath as the production solver.
tasks.withType<Test>().configureEach {
    maxHeapSize = "384m"
    maxParallelForks = 1
    providers.gradleProperty("debugReport").orNull?.let { systemProperty("debugReport", it) }
    systemProperty("catalogueProjectRoot", rootProject.projectDir.absolutePath)
}

// JVM entry point uses the same Kotlin importer/validator as the application schema.
for (checkOnly in listOf(false, true)) {
    tasks.register<JavaExec>(if (checkOnly) "verifyWebbingCatalogue" else "generateWebbingCatalogue") {
        dependsOn("compileDebugUnitTestKotlin", "processDebugUnitTestJavaRes")
        classpath = files(provider { tasks.named<Test>("testDebugUnitTest").get().classpath })
        mainClass.set("org.linesolver.base.CatalogueImportMain")
        args(rootProject.projectDir.absolutePath, if (checkOnly) "--check" else "--write")
    }
}
