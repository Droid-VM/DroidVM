@file:Suppress("UnstableApiUsage")

import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
                arguments += "-DANDROID_STL=c++_static"
                arguments += "-DDROIDVM_VERSION=${versionName}"
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
        release {
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
    buildFeatures {
        aidl = true
        buildConfig = true
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

abstract class CopyNativeBinAssetsTask : DefaultTask() {
    @get:InputDirectory
    abstract val cmakeOutputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val outDir = outputDir.get().asFile
        outDir.deleteRecursively()
        val cmakeDir = cmakeOutputDir.get().asFile
        if (!cmakeDir.exists()) return
        val binaries = setOf("droidvm", "daemon")
        cmakeDir.walkTopDown()
            .filter { it.name in binaries && it.isFile }
            .forEach { src ->
                val abi = src.parentFile.name
                val dest = File(outDir, "bin/$abi/${src.name}")
                dest.parentFile.mkdirs()
                src.copyTo(dest, overwrite = true)
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

    @get:Internal
    abstract val prebuiltRoot: DirectoryProperty

    @get:Internal
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
val regenPrebuilts = tasks.register<RegenPrebuiltsTask>("regenPrebuilts") {
    description = "Regenerate prebuilts from DroidVM-Prebuilt-Root if it exists and has auto-build/ content"
    prebuiltRoot.set(prebuiltRootDir)
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
// Run before anything reads the prebuilts dir (assets merge + jniLibs unpack).
tasks.named("preBuild").configure { dependsOn(regenPrebuilts) }

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
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
                    "intermediates/cmake/${variant.name}/obj"
                )
            )
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
    implementation(libs.constraintlayout)
    implementation(libs.libsu.core)
    implementation(libs.libsu.nio)
    implementation(libs.libsu.service)
    implementation(libs.material)
    implementation(libs.okhttp3)
    implementation(libs.snakeyaml)
    implementation(libs.xz)
    implementation(libs.termux.emulator)
    implementation(libs.termux.view)
    testImplementation(libs.junit)
    annotationProcessor(libs.auto.service)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
