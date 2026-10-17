plugins {
    alias(libs.plugins.android.library)
}

abstract class ContractSourceTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copySource() {
        val source = sourceFile.get().asFile
        val output = outputDirectory.get().asFile
        output.mkdirs()
        source.copyTo(output.resolve(source.name), overwrite = true)
    }
}

val protocolSources = tasks.register<ContractSourceTask>("syncProtocolSources") {
    sourceFile.set(rootProject.file("app/src/main/java/cn/classfun/droidvm/lib/daemon/Protocol.java"))
    outputDirectory.set(layout.buildDirectory.dir("generated/protocol/main"))
}
val vmConfigSources = tasks.register<ContractSourceTask>("syncVmConfigSources") {
    sourceFile.set(rootProject.file("app/src/main/java/cn/classfun/droidvm/lib/daemon/VmConfigClient.java"))
    outputDirectory.set(layout.buildDirectory.dir("generated/vmconfig/main"))
}
val networkConfigSources = tasks.register<ContractSourceTask>("syncNetworkConfigSources") {
    sourceFile.set(rootProject.file("app/src/main/java/cn/classfun/droidvm/lib/daemon/NetworkConfigClient.java"))
    outputDirectory.set(layout.buildDirectory.dir("generated/networkconfig/main"))
}
val vmOwnershipSources = tasks.register<ContractSourceTask>("syncVmOwnershipSources") {
    sourceFile.set(rootProject.file("app/src/main/java/cn/classfun/droidvm/lib/store/base/VmConfigOwnership.java"))
    outputDirectory.set(layout.buildDirectory.dir("generated/vmownership/main"))
}
val networkOwnershipSources = tasks.register<ContractSourceTask>("syncNetworkOwnershipSources") {
    sourceFile.set(rootProject.file("app/src/main/java/cn/classfun/droidvm/lib/store/base/NetworkConfigOwnership.java"))
    outputDirectory.set(layout.buildDirectory.dir("generated/networkownership/main"))
}
val diskOwnershipSources = tasks.register<ContractSourceTask>("syncDiskOwnershipSources") {
    sourceFile.set(rootProject.file("app/src/main/java/cn/classfun/droidvm/lib/store/base/DiskConfigOwnership.java"))
    outputDirectory.set(layout.buildDirectory.dir("generated/diskownership/main"))
}
val diskConfigSources = tasks.register<ContractSourceTask>("syncDiskConfigSources") {
    sourceFile.set(rootProject.file("app/src/main/java/cn/classfun/droidvm/lib/daemon/DiskConfigClient.java"))
    outputDirectory.set(layout.buildDirectory.dir("generated/diskconfig/main"))
}
val configurationSources = tasks.register<ContractSourceTask>("syncConfigurationSources") {
    sourceFile.set(rootProject.file("app/src/main/java/cn/classfun/droidvm/lib/daemon/ConfigurationClient.java"))
    outputDirectory.set(layout.buildDirectory.dir("generated/configuration/main"))
}
val diskOperationSources = tasks.register<ContractSourceTask>("syncDiskOperationSources") {
    sourceFile.set(rootProject.file("app/src/main/java/cn/classfun/droidvm/lib/daemon/DiskOperationClient.java"))
    outputDirectory.set(layout.buildDirectory.dir("generated/diskoperation/main"))
}
val contractSources = tasks.register<ContractSourceTask>("syncContractSources") {
    sourceFile.set(rootProject.file("app/src/androidTest/java/cn/classfun/droidvm/lib/daemon/ProtocolContractTest.java"))
    outputDirectory.set(layout.buildDirectory.dir("generated/protocol/androidTest"))
}
val audioTypeSources = tasks.register<ContractSourceTask>("syncAudioTypeSources") {
    sourceFile.set(rootProject.file("app/src/main/java/cn/classfun/droidvm/lib/data/HostAudioDeviceTypes.java"))
    outputDirectory.set(layout.buildDirectory.dir("generated/audio/main"))
}
val evdevSources = tasks.register<ContractSourceTask>("syncEvdevSources") {
    sourceFile.set(rootProject.file("app/src/main/java/cn/classfun/droidvm/ui/vm/display/nativedisplay/input/EvdevEncoder.java"))
    outputDirectory.set(layout.buildDirectory.dir("generated/evdev/main"))
}

// A library's instrumentation APK targets itself. Device contract checks can run
// without replacing DroidVM or starting its Application, daemon or VM stores.
android {
    namespace = "cn.classfun.droidvm.migration"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    buildFeatures { aidl = true }
    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets {
        getByName("main") {
            aidl.directories.add(rootProject.file("daemon/api/aidl").path)
        }
        getByName("androidTest") {
            assets.directories.add(rootProject.file("app/src/androidTest/assets").path)
            assets.directories.add(rootProject.file("daemon/platform/android/probe/profiles").path)
            assets.directories.add(rootProject.file("daemon/api/contracts").path)
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(protocolSources, ContractSourceTask::outputDirectory)
        variant.sources.java?.addGeneratedSourceDirectory(vmConfigSources, ContractSourceTask::outputDirectory)
        variant.sources.java?.addGeneratedSourceDirectory(networkConfigSources, ContractSourceTask::outputDirectory)
        variant.sources.java?.addGeneratedSourceDirectory(vmOwnershipSources, ContractSourceTask::outputDirectory)
        variant.sources.java?.addGeneratedSourceDirectory(networkOwnershipSources, ContractSourceTask::outputDirectory)
        variant.sources.java?.addGeneratedSourceDirectory(diskOwnershipSources, ContractSourceTask::outputDirectory)
        variant.sources.java?.addGeneratedSourceDirectory(diskConfigSources, ContractSourceTask::outputDirectory)
        variant.sources.java?.addGeneratedSourceDirectory(configurationSources, ContractSourceTask::outputDirectory)
        variant.sources.java?.addGeneratedSourceDirectory(diskOperationSources, ContractSourceTask::outputDirectory)
        variant.sources.java?.addGeneratedSourceDirectory(audioTypeSources, ContractSourceTask::outputDirectory)
        variant.sources.java?.addGeneratedSourceDirectory(evdevSources, ContractSourceTask::outputDirectory)
        variant.androidTest?.sources?.java?.addGeneratedSourceDirectory(contractSources, ContractSourceTask::outputDirectory)
    }
}

dependencies {
    implementation(libs.annotation.jvm)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
