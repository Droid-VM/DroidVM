@file:Suppress("UnstableApiUsage")

import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    // Compose is here for one screen: the Markdown notes editor and the cards that render what
    // it wrote, which come from a Compose-only library. Everything else stays Java and Views.
    // Kotlin itself needs no plugin -- AGP 9 compiles it out of the box and already owns the
    // "kotlin" extension -- so only the Compose compiler plugin is applied, pinned to the same
    // Kotlin version AGP carries.
    alias(libs.plugins.kotlin.compose)
}

fun runGit(vararg args: String): String {
    require(rootDir.resolve(".git").exists()) { "Not a git repository: $rootDir" }
    val proc = ProcessBuilder("git", *args)
        .directory(rootDir)
        .redirectErrorStream(false)
        .start()
    val output = proc.inputStream.bufferedReader().readText().trim()
    val exitCode = proc.waitFor()
    require(exitCode == 0) { "git ${args.joinToString(" ")} failed with exit code $exitCode" }
    return output
}

// Tolerant variant: returns null instead of throwing, for git calls that may
// legitimately fail (e.g. describe on a checkout with no tags).
fun runGitOrNull(vararg args: String): String? =
    runCatching { runGit(*args) }.getOrNull()

val gitCommitCount = runGit("rev-list", "--count", "HEAD").toInt()
val gitShortSha = runGit("rev-parse", "--short", "HEAD")

// describe fails on a checkout without tags (shallow clone, or tags not fetched);
// fall back to a 0.0.0 base so the build still works and the version is clearly
// marked as tag-less. Exclude the rolling `dev` tag (dev-release.yml recreates it
// at HEAD each push) so it never shadows real version tags in the version name.
val gitDescribe = (runGitOrNull("describe", "--long", "--tags", "--exclude=dev")
    ?: "0.0.0-$gitCommitCount-g$gitShortSha")
    .removePrefix("v").removePrefix("V")
val generatedVersionName: String = if (gitDescribe.matches(Regex(".*-0-g[0-9a-f]+$"))) {
    gitDescribe.replace(Regex("-0-g[0-9a-f]+$"), "")
} else {
    gitDescribe
        .replace(Regex("([^-]*-g)"), $$"r$1")
        .replace("-", ".")
}

val generatedVersionCode: Int = gitCommitCount * 10

val webUiNativeRoot = layout.buildDirectory.dir("intermediates/webui_native")
val webUiAssetsCpp = layout.buildDirectory.file("generated/webui/webui_assets.cpp")

println("Version name: $generatedVersionName")
println("Version code: $generatedVersionCode")

android {
    namespace = "cn.classfun.droidvm"
    compileSdk {
        version = release(37) {
        }
    }

    defaultConfig {
        applicationId = "cn.classfun.droidvm"
        minSdk = 33
        targetSdk = 37
        versionCode = generatedVersionCode
        versionName = generatedVersionName
        // The App and daemon embed the same creation template; no runtime asset
        // access is needed, so the existing null-Context model tests still work.
        buildConfigField("String", "VM_DEFAULTS_JSON", groovy.json.JsonOutput.toJson(
            providers.fileContents(layout.projectDirectory.file("src/main/assets/data/vm-defaults.json")).asText.get()
        ))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
                arguments += "-DANDROID_STL=c++_static"
                arguments += "-DDROIDVM_WEB_HTTP_TRANSPORT=ON"
                arguments += "-DDROIDVM_WEB_LWS_DIR=${webUiNativeRoot.get().dir("deps/libwebsockets").asFile.invariantSeparatorsPath}"
                arguments += "-DDROIDVM_WEB_ASSETS_CPP=${webUiAssetsCpp.get().asFile.invariantSeparatorsPath}"
                arguments += "-DDROIDVM_VERSION=${versionName}"
                arguments += "-DDROIDVM_VERSION_NAME=${versionName}"
                arguments += "-DDROIDVM_VERSION_CODE=${versionCode}"
                arguments += "-DDROIDVM_PACKAGE_NAME=${applicationId}"
                arguments += "-DDROIDVM_BIN_OUTPUT_ROOT=${layout.buildDirectory.dir("intermediates/native_binaries").get().asFile.invariantSeparatorsPath}"
                // droidvmd's Binder/audio services need the AOSP 14 aidl generator.
                val aidl = androidComponents.sdkComponents.sdkDirectory.get().asFile
                    .resolve("build-tools/34.0.0/" + if (System.getProperty("os.name").startsWith("Windows")) "aidl.exe" else "aidl")
                require(aidl.isFile) {
                    "Install Android SDK build-tools 34.0.0: droidvmd requires its AIDL compiler for Binder/audio services ($aidl)"
                }
                arguments += "-DDROIDVM_BUILD_PLATFORM_PROBE=ON"
                arguments += "-DDROIDVM_AIDL=${aidl.invariantSeparatorsPath}"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            externalNativeBuild.cmake.arguments += "-DDROIDVM_BUILD_TYPE=debug"
        }
        release {
            externalNativeBuild.cmake.arguments += "-DDROIDVM_BUILD_TYPE=release"
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
        }
    }
    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }
    sourceSets {
        getByName("main") {
            // The daemon owns the shared Binder contract; generate both sides from one source.
            aidl.srcDir(rootProject.file("daemon/api/aidl"))
            // One rescue script source for App actions and the native Agent core.
            resources.srcDir(rootProject.file("daemon/agent/scripts"))
            // Third-party source kept in the tree rather than pulled as an artifact, in its own
            // root so that "not ours, and under its own licence" is structural. See its README.
            kotlin.srcDir("src/main/vendor")
        }
    }
    testOptions {
        unitTests {
            // Lets a unit test cover a class that logs. The alternative -- keeping every testable
            // class free of android.util.Log -- stopped being tenable at the H.264 side channel,
            // whose whole subject is a socket and a thread outliving the object that owned them,
            // and which says so out loud when they do. Nothing here asserts on a stub's return
            // value; the stubs are only there so the class under test can be built at all.
            isReturnDefaultValues = true
        }
    }
    packaging {
        jniLibs {
            // Extract native libs to a real on-disk dir so lbx (shipped as
            // liblbx.so) is an executable file the app can run from its own
            // nativeLibraryDir -- no root, no daemon needed for a URL fetch.
            useLegacyPackaging = true
        }
    }
    androidResources {
        // prebuilt-<abi>-comptime.zip is a build-time input only: Gradle unpacks
        // it into jniLibs (lib/<abi>/liblbx.so). Never ship it as an APK asset.
        // Also exclude the legacy *.7z (replaced by *.tar.xz) so a stale archive
        // lingering in the prebuilts submodule can't double the APK size. The
        // runtime prebuilt-<abi>.tar.xz + .json stay packaged as assets.
        ignoreAssetsPatterns += listOf("*-comptime.zip", "*.7z")
    }
}

abstract class CopyWebUiNoticesTask : DefaultTask() {
    @get:Inject
    abstract val fs: FileSystemOperations

    @get:InputDirectory
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        fs.sync { from(sourceDir); into(outputDir) }
    }
}

abstract class CopyNativeBinAssetsTask : DefaultTask() {
    @get:InputDirectory
    abstract val cmakeOutputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val expectedAbis: ListProperty<String>

    // AGP strips only jniLibs; executables shipped as assets keep their debug info otherwise.
    // The unstripped build stays in cmakeOutputDir for symbolization.
    @get:InputDirectory
    abstract val ndkDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        val prebuilt = File(ndkDirectory.get().asFile, "toolchains/llvm/prebuilt")
        val strip = prebuilt.listFiles().orEmpty().flatMap { host ->
            listOf(File(host, "bin/llvm-strip.exe"), File(host, "bin/llvm-strip"))
        }.firstOrNull { it.isFile } ?: error("llvm-strip not found under $prebuilt")
        val outDir = outputDir.get().asFile
        val cmakeDir = cmakeOutputDir.get().asFile
        val binaries = setOf("droidvm", "daemon", "droidvmd")
        val inputs = expectedAbis.get().flatMap { abi -> binaries.map { name -> File(cmakeDir, "$abi/$name") } }
        inputs.forEach { require(it.isFile && it.length() > 0) { "Missing native binary: $it" } }
        outDir.deleteRecursively()
        inputs.forEach { src ->
            val dest = File(outDir, "bin/${src.parentFile.name}/${src.name}")
            dest.parentFile.mkdirs()
            val process = ProcessBuilder(strip.absolutePath, "--strip-unneeded", "-o", dest.absolutePath, src.absolutePath)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            require(process.waitFor() == 0 && dest.length() > 0) { "llvm-strip failed for $src: $output" }
        }
    }
}

// Unpack each prebuilt-<abi>-comptime.zip from the prebuilts submodule into
// jniLibs/<abi>/. These are files that must be executable from the app's
// nativeLibraryDir (currently liblbx.so) -- the data-dir copy extracted from the
// runtime tar.xz isn't executable by the app's untrusted_app SELinux domain.
// Build-time extraction (unlike a first-run extract) is fine because the package
// installer, not the app, populates nativeLibraryDir.
abstract class UnpackComptimeJniLibsTask : DefaultTask() {
    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val fs: FileSystemOperations

    @get:InputDirectory
    abstract val prebuiltsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun unpack() {
        val outDir = outputDir.get().asFile
        outDir.deleteRecursively()
        val prebuilts = prebuiltsDir.get().asFile
        val re = Regex("^prebuilt-(.+)-comptime\\.zip$")
        val zips = prebuilts.listFiles { f -> f.isFile && re.matches(f.name) } ?: return
        for (zip in zips) {
            val abi = re.find(zip.name)!!.groupValues[1]
            fs.copy {
                from(archives.zipTree(zip))
                into(File(outDir, abi))
            }
        }
    }
}

// Native-dev hook: when DroidVM-Prebuilt-Root is checked out at the repo root
// with a non-empty auto-build/ (someone is editing a native util locally),
// regenerate the prebuilt-* artifacts into the prebuilts submodule before they
// are packaged. Pure-Java devs don't have this dir, so the CI-published
// submodule artifacts are used as-is (the task no-ops via onlyIf).
abstract class RegenPrebuiltsTask : DefaultTask() {
    @get:Inject
    abstract val exec: ExecOperations

    // The working directory only; the sources Gradle should hash are declared below. Hashing the
    // whole root would drag in manual-build/, which carries hundreds of megabytes of binaries the
    // script never reads.
    @get:Internal
    abstract val prebuiltRoot: DirectoryProperty

    /** What the script builds from: a change here is the only reason to pack again. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val autoBuildSources: DirectoryProperty

    /** The script itself, so editing it invalidates the last run too. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val autoBuildScript: RegularFileProperty

    @get:OutputDirectory
    abstract val prebuiltsOut: DirectoryProperty

    @TaskAction
    fun regen() {
        val root = prebuiltRoot.get().asFile
        val out = prebuiltsOut.get().asFile
        logger.lifecycle("DroidVM-Prebuilt-Root detected; regenerating prebuilts via auto-build.py")
        exec.exec {
            workingDir = root
            commandLine("python3", "auto-build.py", "--out", out.absolutePath)
        }
    }
}

// Fetch the terminal font (Maple Mono NL NF) at build time so CI (./gradlew) and
// local builds behave identically -- it used to live only in build.sh, which CI
// never runs. Download is best-effort: a failure logs a warning and the app falls
// back to the system monospace, so it never breaks the build.
abstract class FetchTerminalFontTask : DefaultTask() {
    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val ttf = File(outputDir.get().asFile, "fonts/MapleMonoNL-NF-Regular.ttf")
        if (ttf.isFile && sha256Hex(ttf) == sha256.get()) return
        ttf.parentFile.mkdirs()
        val tmpZip = File(temporaryDir, "font.zip")
        try {
            URI(url.get()).toURL().openStream().use { input ->
                tmpZip.outputStream().use { input.copyTo(it) }
            }
        } catch (e: Exception) {
            logger.warn("Could not download terminal font (${e.message}); app uses system monospace")
            return
        }
        val src = archives.zipTree(tmpZip).matching { include("**/*NL-NF-Regular.ttf") }
            .files.firstOrNull()
        if (src == null) {
            logger.warn("Terminal font zip had no NL-NF-Regular.ttf; app uses system monospace")
            return
        }
        src.copyTo(ttf, overwrite = true)
        if (sha256Hex(ttf) != sha256.get())
            logger.warn("Terminal font sha256 mismatch (upstream re-released?); keeping it anyway")
    }

    private fun sha256Hex(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(8192)
            var n = ins.read(buf)
            while (n >= 0) { md.update(buf, 0, n); n = ins.read(buf) }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}

val prebuiltRootDir = rootProject.layout.projectDirectory.dir("DroidVM-Prebuilt-Root")
val prebuiltsSubmoduleDir = rootProject.layout.projectDirectory.dir("app/src/main/assets/prebuilts")

// One production bundle shared by both build types and ABIs. These tasks are
// dependencies of native configuration, not preBuild: Java-only tests need no Node.
// No dependency downloads occur inside CMake or on an Android device.
val webUiDir = rootProject.layout.projectDirectory.dir("webui")
val webUiScripts = rootProject.layout.projectDirectory.dir("scripts/webui")
val windowsHost = System.getProperty("os.name").startsWith("Windows")
val webUiNpm = providers.gradleProperty("webuiNpm").orElse(if (windowsHost) "npm.cmd" else "npm")
val webUiNode = providers.gradleProperty("webuiNode").orElse("node")
val webUiPython = providers.gradleProperty("webuiPython").orElse(if (windowsHost) "python" else "python3")
val webUiOffline = gradle.startParameter.isOffline
fun npmCommand(vararg args: String): List<String> =
    (if (windowsHost) listOf("cmd", "/d", "/c", webUiNpm.get()) else listOf(webUiNpm.get())) + args

val installWebUiDependencies = tasks.register<Exec>("installWebUiDependencies") {
    description = "Install locked WebUI build dependencies (Node 24 required)"
    workingDir(webUiDir)
    inputs.files(webUiDir.file("package.json"), webUiDir.file("package-lock.json"))
    inputs.property("npm", webUiNpm)
    outputs.dir(webUiDir.dir("node_modules"))
    commandLine(npmCommand("ci", "--engine-strict", "--no-audit", "--no-fund") + if (webUiOffline) listOf("--offline") else emptyList())
}
val buildWebUi = tasks.register<Exec>("buildWebUi") {
    description = "Check and build the production WebUI without development fixtures"
    dependsOn(installWebUiDependencies)
    workingDir(webUiDir)
    inputs.files(fileTree(webUiDir) {
        exclude("node_modules/**", "dist/**", "test-results/**", "playwright-report/**", ".gitignore", "README.md")
    })
    inputs.dir(rootProject.file("contracts/webui"))
    inputs.property("npm", webUiNpm)
    outputs.dir(webUiDir.dir("dist"))
    commandLine(npmCommand("run", "build"))
}
val embedWebUiAssets = tasks.register<Exec>("embedWebUiAssets") {
    description = "Embed immutable production assets and deterministic gzip into droidvmd"
    dependsOn(buildWebUi)
    inputs.dir(webUiDir.dir("dist"))
    inputs.file(webUiScripts.file("embed-assets.mjs"))
    inputs.property("node", webUiNode)
    outputs.file(webUiAssetsCpp)
    workingDir(rootDir)
    commandLine(webUiNode.get(), webUiScripts.file("embed-assets.mjs").asFile.absolutePath,
        "--dist", webUiDir.dir("dist").asFile.absolutePath, "--output", webUiAssetsCpp.get().asFile.absolutePath)
}
val prepareWebUiNative = tasks.register<Exec>("prepareWebUiNative") {
    description = "Prepare checksum-verified libwebsockets sources for the APK"
    inputs.files(webUiScripts.file("native-probe/fetch_deps.py"), webUiScripts.file("native-probe/dependencies.json"))
    inputs.property("python", webUiPython)
    outputs.dir(webUiNativeRoot.map { it.dir("deps") })
    workingDir(rootDir)
    commandLine(listOf(webUiPython.get(), webUiScripts.file("native-probe/fetch_deps.py").asFile.absolutePath,
        "--output", webUiNativeRoot.get().asFile.absolutePath) + if (webUiOffline) listOf("--offline") else emptyList())
}
val generateWebUiNotices = tasks.register<Exec>("generateWebUiNotices") {
    description = "Retain WebUI and libwebsockets upstream license notices in APK assets"
    dependsOn(installWebUiDependencies, prepareWebUiNative)
    inputs.files(webUiDir.file("package-lock.json"), webUiScripts.file("third-party-notices.mjs"),
        webUiScripts.file("native-probe/dependencies.json"))
    inputs.dir(webUiScripts.dir("licenses"))
    inputs.property("node", webUiNode)
    outputs.file(layout.buildDirectory.file("generated/webui_notices/licenses/webui-third-party.txt"))
    workingDir(rootDir)
    commandLine(webUiNode.get(), webUiScripts.file("third-party-notices.mjs").asFile.absolutePath,
        "--modules", webUiDir.dir("node_modules").asFile.absolutePath,
        "--lws", webUiNativeRoot.get().dir("deps/libwebsockets").asFile.absolutePath,
        "--output", layout.buildDirectory.file("generated/webui_notices/licenses/webui-third-party.txt").get().asFile.absolutePath)
}
tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") || (it.name.startsWith("externalNativeBuild") && !it.name.startsWith("externalNativeBuildClean")) }
    .configureEach { dependsOn(prepareWebUiNative, embedWebUiAssets) }

val regenPrebuilts = tasks.register<RegenPrebuiltsTask>("regenPrebuilts") {
    description = "Regenerate prebuilts from DroidVM-Prebuilt-Root if it exists and has auto-build/ content"
    prebuiltRoot.set(prebuiltRootDir)
    autoBuildSources.set(prebuiltRootDir.dir("auto-build"))
    autoBuildScript.set(prebuiltRootDir.file("auto-build.py"))
    prebuiltsOut.set(prebuiltsSubmoduleDir)
    // Only when a native dev has checked out DroidVM-Prebuilt-Root with source
    // under auto-build/. Otherwise, the published submodule artifacts are used.
    onlyIf {
        val autoBuild = prebuiltRootDir.dir("auto-build").asFile
        prebuiltRootDir.file("auto-build.py").asFile.isFile &&
            autoBuild.isDirectory &&
            (autoBuild.listFiles()?.any { it.name != ".gitignore" } == true)
    }
}
// Hung on the two tasks that actually read the prebuilts dir, not on preBuild: preBuild is a
// dependency of every JVM unit test run as well, and packing an APK payload is not something a
// test of the rules engine should wait for.

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val copyWebUiNotices = tasks.register<CopyWebUiNoticesTask>("copyWebUiNotices${variantName}") {
            dependsOn(generateWebUiNotices)
            sourceDir.set(layout.buildDirectory.dir("generated/webui_notices"))
            outputDir.set(layout.buildDirectory.dir("generated/webui_notice_assets/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copyWebUiNotices, CopyWebUiNoticesTask::outputDir)
        val unpackComptimeTask = tasks.register<UnpackComptimeJniLibsTask>(
            "unpackComptimeJniLibs${variantName}"
        ) {
            description = "Unpack prebuilt-<abi>-comptime.zip into jniLibs/<abi>/ for ${variant.name}"
            dependsOn(regenPrebuilts)

            prebuiltsDir.set(prebuiltsSubmoduleDir)
            outputDir.set(
                layout.buildDirectory.dir("generated/comptime_jnilibs/${variant.name}")
            )
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            unpackComptimeTask, UnpackComptimeJniLibsTask::outputDir
        )
        // The other reader of the prebuilts dir. Declared here rather than on preBuild so that a
        // unit test run never packs anything.
        tasks.matching { it.name == "merge${variantName}Assets" }
            .configureEach { dependsOn(regenPrebuilts) }
        val fetchFontTask = tasks.register<FetchTerminalFontTask>(
            "fetchTerminalFont${variantName}"
        ) {
            description = "Fetch terminal font for ${variant.name}"
            url.set("https://github.com/subframe7536/maple-font/releases/download/v7.9/MapleMonoNL-NF.zip")
            sha256.set("aa3b096bc92df8503d77482b285a0567bafa6e83230d969700f455e610b1f655")
            outputDir.set(layout.buildDirectory.dir("generated/font_assets/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(
            fetchFontTask, FetchTerminalFontTask::outputDir
        )
        val copyNativeTask = tasks.register<CopyNativeBinAssetsTask>(
            "copyNativeBinAssets${variantName}"
        ) {
            description = "Copy native bin assets for ${variant.name}"
            dependsOn("externalNativeBuild${variantName}")
            cmakeOutputDir.set(
                layout.buildDirectory.dir(
                    "intermediates/native_binaries/${variant.buildType}"
                )
            )
            expectedAbis.set(android.defaultConfig.ndk.abiFilters.sorted())
            ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
            outputDir.set(
                layout.buildDirectory.dir(
                    "generated/droidvm_assets/${variant.name}"
                )
            )
        }
        variant.sources.assets?.addGeneratedSourceDirectory(
            copyNativeTask, CopyNativeBinAssetsTask::outputDir
        )
    }
}

dependencies {
    implementation(libs.activity)
    implementation(libs.annotation.jvm)
    implementation(libs.appcompat)
    implementation(libs.auto.service.annotations)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.animation)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.constraintlayout)
    implementation(libs.libsu.core)
    implementation(libs.libsu.nio)
    implementation(libs.libsu.service)
    implementation(libs.hiddenapibypass)
    // The renderer itself lives in src/main/vendor; these are what it needs.
    implementation(libs.markdown.parser)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.material)
    implementation(libs.okhttp3)
    implementation(libs.snakeyaml)
    implementation(libs.xz)
    implementation(libs.zstd) { artifact { type = "aar" } }
    testImplementation(libs.zstd)
    implementation(libs.termux.emulator)
    implementation(libs.termux.view)
    testImplementation(libs.junit)
    testImplementation(libs.json.jvm) // Real org.json behavior in local IPC contract tests.
    annotationProcessor(libs.auto.service)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
