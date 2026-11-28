import java.util.Properties

group = "app.voidhack"

patches {
    about {
        name = "Nich87 Patches"
        description = "Custom patches by Nich87 for use with Morphe."
        source = "https://github.com/Nich87/nich87-patches"
        author = "Nich87"
        contact = "https://github.com/Nich87/nich87-patches/issues"
        website = "https://github.com/Nich87/nich87-patches"
        license = "GPLv3"
    }
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the APK.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")
val singRuntimeAar = configurations.create("singRuntimeAar") {
    isCanBeConsumed = false
    isTransitive = false
}
val kuromojiNotices = configurations.create("kuromojiNotices") {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    add(kuromojiNotices.name, libs.kuromoji.ipadic)

    val onnxRuntime = libs.onnxruntime.android.get()
    add(singRuntimeAar.name, "${onnxRuntime.group}:${onnxRuntime.name}:${onnxRuntime.versionConstraint.requiredVersion}@aar")
}

val singRuntimeLibraries by tasks.registering(Sync::class) {
    from(singRuntimeAar.elements.map { zipTree(it.single().asFile) }) {
        include("jni/arm64-v8a/libonnxruntime.so", "jni/arm64-v8a/libonnxruntime4j_jni.so")
        into("applemusic/sing-runtime")
    }
    into(layout.buildDirectory.dir("generated/singRuntimeResources"))
}
sourceSets["main"].resources.srcDir(singRuntimeLibraries)

val kuromojiNoticeResources = tasks.register<Sync>("kuromojiNoticeResources") {
    from(kuromojiNotices.elements.map { zipTree(it.single().asFile) }) {
        include("META-INF/CONTRIBUTORS.md", "META-INF/LICENSE.md", "META-INF/NOTICE.md")
        into("applemusic/lyrics/kuromoji")
    }
    into(layout.buildDirectory.dir("generated/kuromojiNoticeResources"))
}
sourceSets["main"].resources.srcDir(kuromojiNoticeResources)

val compileUsbAudioNative = tasks.register("compileUsbAudioNative") {
    val sourceFile = layout.projectDirectory.file("src/main/native/usb-audio/voidhack_usb_audio.c")
    val outputRoot = layout.buildDirectory.dir("generated/usbAudioNative")
    inputs.file(sourceFile)
    outputs.dir(outputRoot)

    doLast {
        fun resolveNdkDir(): File {
            val directEnv = listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK_LATEST_HOME")
                .mapNotNull { System.getenv(it)?.takeIf(String::isNotBlank) }
                .map(::File)
                .firstOrNull { it.isDirectory && File(it, "toolchains/llvm/prebuilt").isDirectory }
            if (directEnv != null) return directEnv

            val localProps = rootProject.file("local.properties")
            val propValues = Properties()
            if (localProps.isFile) {
                localProps.inputStream().use { propValues.load(it) }
            }
            val propNdk = propValues.getProperty("ndk.dir")?.takeIf(String::isNotBlank)?.let(::File)
            if (propNdk != null && propNdk.isDirectory && File(propNdk, "toolchains/llvm/prebuilt").isDirectory) {
                return propNdk
            }

            val sdkDir = listOfNotNull(
                System.getenv("ANDROID_HOME")?.takeIf(String::isNotBlank),
                System.getenv("ANDROID_SDK_ROOT")?.takeIf(String::isNotBlank),
                propValues.getProperty("sdk.dir")?.takeIf(String::isNotBlank),
            ).map(::File).firstOrNull { it.isDirectory }
                ?: error("Android SDK not found; set ANDROID_HOME or sdk.dir in local.properties.")

            val ndkRoot = File(sdkDir, "ndk")
            val candidates = ndkRoot.listFiles()
                ?.filter { it.isDirectory && File(it, "toolchains/llvm/prebuilt").isDirectory }
                ?.sortedByDescending { it.name }
                ?: emptyList()
            return candidates.firstOrNull()
                ?: error("Android NDK not found under ${ndkRoot.absolutePath}; set ANDROID_NDK_HOME or install NDK.")
        }

        val ndkDir = resolveNdkDir()
        val prebuiltRoot = File(ndkDir, "toolchains/llvm/prebuilt")
        val hostTag = prebuiltRoot.listFiles()
            ?.firstOrNull { it.isDirectory && File(it, "bin").isDirectory }
            ?.name
            ?: error("No LLVM prebuilt toolchain found under ${prebuiltRoot.absolutePath}")
        val binDir = File(prebuiltRoot, "$hostTag/bin")
        val exeSuffix = if (System.getProperty("os.name").lowercase().contains("win")) ".cmd" else ""
        val stripExe = File(binDir, "llvm-strip" + if (exeSuffix.isNotEmpty()) ".exe" else "")

        val abis = listOf(
            "arm64-v8a" to "aarch64-linux-android21-clang",
            "armeabi-v7a" to "armv7a-linux-androideabi21-clang",
            "x86_64" to "x86_64-linux-android21-clang",
            "x86" to "i686-linux-android21-clang",
        )

        val srcPath = sourceFile.asFile.absolutePath
        for ((abi, compilerBase) in abis) {
            val compiler = File(binDir, compilerBase + exeSuffix)
            check(compiler.isFile) { "Missing NDK compiler ${compiler.absolutePath}" }
            val outDir = outputRoot.get().file("applemusic/usb-audio/jni/$abi").asFile
            outDir.mkdirs()
            val outSo = File(outDir, "libvoidhack_usb_audio.so")

            val cmd = listOf(
                compiler.absolutePath,
                "-std=c11",
                "-O2",
                "-Wall",
                "-Wextra",
                "-shared",
                "-fPIC",
                "-fvisibility=hidden",
                "-ffunction-sections",
                "-fdata-sections",
                "-Wl,--gc-sections",
                "-Wl,-z,max-page-size=16384",
                srcPath,
                "-llog",
                "-lm",
                "-o",
                outSo.absolutePath,
            )
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val log = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()
            if (exit != 0) {
                error("Failed to compile libvoidhack_usb_audio.so for $abi (exit $exit):\n$log")
            }
            if (stripExe.isFile) {
                val stripProc = ProcessBuilder(stripExe.absolutePath, "--strip-unneeded", outSo.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                stripProc.inputStream.readBytes()
                stripProc.waitFor()
            }
        }
    }
}
sourceSets["main"].resources.srcDir(compileUsbAudioNative)

sourceSets["test"].java.srcDir(project(":extensions:extension").file("src/test/java"))
tasks.named<JavaCompile>("compileTestJava") {
    options.release.set(11)
    source(project(":extensions:extension").fileTree("src/main/java/app/voidhack/extension/applemusic/glass") {
        include("GlassSpring.java", "GlassPlayerGesture.java", "GlassPlayerMorph.java")
    })
    source(project(":extensions:extension").fileTree("src/main/java/app/voidhack/extension/applemusic/library") {
        include("LibraryBackup.java", "LibraryImportPlan.java", "LibraryFailure.java", "DaapCodec.java",
            "LibraryDaap.java", "MusicReflection.java", "MusicLibrary.java", "MetadataField.java", "MetadataCatalog.java", "LibraryMetadata.java")
    })
    source(project(":extensions:extension").fileTree("src/main/java/app/voidhack/extension/applemusic/lyrics") {
        include("LrclibModels.java", "LrclibMatcher.java", "LrclibParser.java", "LrclibApiClient.java",
            "LocalLrcParser.java", "LocalLrcMatcher.java", "LyricsLrcExporter.java",
            "OfficialLyricsTtml.java", "LyricsEditTtml.java", "LyricsEditorModel.java", "LyricsInterludes.java",
            "LyricsFeatures.java", "TimedPronunciation.java", "LyricsScrollHost.java", "LyricsScrollSyncPolicy.java",
            "LyricsPillVisibilityPolicy.java")
    })
    source(project(":extensions:extension").fileTree("src/main/java/app/voidhack/extension/applemusic/font") {
        include("FontInspector.java")
    })
    source(project(":extensions:extension").fileTree("src/main/java/app/voidhack/extension/applemusic/quality") {
        include("AudioQualityModels.java", "UsbAudioDescriptorParser.java", "Uac2Clock.java")
    })
    source(project(":extensions:extension").fileTree("src/main/java/app/voidhack/extension/twitter/spaces/mute") {
        include("SpaceLocalMuteLogic.java")
    })
    source(project(":extensions:sing-runtime").fileTree("src/main/java/app/voidhack/extension/applemusic/sing") {
        include("SingBackendPolicy.java", "StereoResampler.java", "MdxSpectrum.java", "MultichannelMixer.java")
    })
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}
