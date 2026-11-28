package app.voidhack.patches.applemusic

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.w3c.dom.Element

internal const val QUALITY_FEATURES = "Lapp/voidhack/extension/applemusic/quality/AudioQualityFeatures;"
internal const val QUALITY_CONTROLLER = "Lapp/voidhack/extension/applemusic/quality/AudioQualityController;"
internal const val QUALITY_MONITOR = "Lapp/voidhack/extension/applemusic/quality/AudioQualityMonitor;"
internal const val QUALITY_BIT_PERFECT = "Lapp/voidhack/extension/applemusic/quality/AudioQualityBitPerfect;"
internal const val USB_DIRECT_ENGINE = "Lapp/voidhack/extension/applemusic/quality/UsbDirectAudioEngine;"
internal const val APPLE_MUSIC_APP = "Lcom/apple/android/music/AppleMusicApplication;"
internal const val NOISY_RECEIVER = "Lcom/apple/android/music/playback/controller/NoisyAudioReceiver;"
internal const val EXO_PLAYER = "Lcom/apple/android/music/playback/player/ExoMediaPlayer;"
internal const val EXO_FORMAT = "Lcom/google/android/exoplayer2/Format;"
internal const val EXO_AUDIO_ATTRS = "Lcom/google/android/exoplayer2/audio/AudioAttributes;"
internal const val PLAYBACK_FORMAT = "Lcom/apple/android/music/playback/player/PlaybackFormat;"
internal const val SV_RENDERER = "Lcom/apple/android/music/playback/renderer/SVAudioRendererV2;"
internal const val DEFAULT_AUDIO_SINK = "Lcom/google/android/exoplayer2/audio/DefaultAudioSink;"
internal const val SINK_CONFIG = "Lcom/google/android/exoplayer2/audio/DefaultAudioSink\$Configuration;"
private const val ON_CLICK_LISTENER = "Landroid/view/View\$OnClickListener;"
private const val PERM_BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"

internal fun BytecodePatchContext.enableAudioQualityFeature(methodName: String) {
    val method = mutableClassDefBy(QUALITY_FEATURES).methods.single {
        it.name == methodName && it.parameterTypes.isEmpty() && it.returnType == "Z"
    }
    method.replaceInstruction(0, "const/4 v0, 0x1")
}

internal fun MutableClass.addQualityHelperMethod(
    name: String,
    parameters: List<String>,
    returnType: String,
    registers: Int,
    accessFlags: Int,
    body: String,
) {
    val method = ImmutableMethod(
        type,
        name,
        parameters.map { ImmutableMethodParameter(it, null, null) },
        returnType,
        accessFlags,
        null,
        null,
        MutableMethodImplementation(registers),
    ).toMutable()
    method.addInstructionsWithLabels(0, body)
    methods.add(method)
}

private val audioQualityBaseResources = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement
            val permissions = root.getElementsByTagName("uses-permission")
            var hasBluetoothConnect = false
            for (index in 0 until permissions.length) {
                val elem = permissions.item(index) as? Element ?: continue
                if (elem.getAttribute("android:name") == PERM_BLUETOOTH_CONNECT) {
                    hasBluetoothConnect = true
                    break
                }
            }
            if (!hasBluetoothConnect) {
                val perm = manifest.createElement("uses-permission")
                perm.setAttribute("android:name", PERM_BLUETOOTH_CONNECT)
                val appNode = root.getElementsByTagName("application").item(0)
                if (appNode != null) {
                    root.insertBefore(perm, appNode)
                } else {
                    root.appendChild(perm)
                }
            }
        }

        listOf("values", "values-ja").forEach { directory ->
            val source = "/applemusic/quality/$directory/voidhack_audio_quality.xml"
            checkNotNull(PlayerProgressSeekBarClick::class.java.getResourceAsStream(source)).use { input ->
                document(input).use { translations ->
                    document("res/$directory/strings.xml").use { destination ->
                        val entries = translations.getElementsByTagName("string")
                        for (index in 0 until entries.length) {
                            destination.documentElement.appendChild(
                                destination.importNode(entries.item(index), true),
                            )
                        }
                    }
                }
            }
        }
    }
}

private object AppleMusicAppOnCreate : Fingerprint(
    definingClass = APPLE_MUSIC_APP,
    name = "onCreate",
    parameters = emptyList(),
    returnType = "V",
)

private object PlayerProgressSeekBarClick : Fingerprint(
    parameters = listOf("Landroid/view/View;", "I"),
    returnType = "V",
    strings = listOf("AudioQualityDisclosure"),
)

private object AudioQualitySettingsClick : Fingerprint(
    name = "onClick",
    parameters = listOf("Landroid/content/DialogInterface;", "I"),
    returnType = "V",
    strings = listOf("QualityDisclosureModal", "AudioQualitySettings"),
    filters = listOf(
        methodCall(
            parameters = listOf("Landroid/content/Context;", "I"),
            returnType = "V",
        ),
    ),
)

private object ExoAudioFormatChanged : Fingerprint(
    definingClass = EXO_PLAYER,
    name = "sendAudioFormatChangeEvent",
    parameters = listOf("Ljava/lang/Object;", EXO_FORMAT),
    returnType = "V",
)

private object SvInitializeAudioSink : Fingerprint(
    definingClass = SV_RENDERER,
    name = "initializeAudioSink",
    parameters = listOf(EXO_FORMAT),
    returnType = "V",
)

private object SinkConfigureObserve : Fingerprint(
    definingClass = DEFAULT_AUDIO_SINK,
    name = "configure",
    parameters = listOf("I", "I", "I", "I", "[I", "I", "I"),
    returnType = "V",
)

private object SinkInitialize : Fingerprint(
    definingClass = DEFAULT_AUDIO_SINK,
    name = "initialize",
    parameters = listOf("J"),
    returnType = "V",
)

private object SinkFlushObserve : Fingerprint(
    definingClass = DEFAULT_AUDIO_SINK,
    name = "flush",
    parameters = emptyList(),
    returnType = "V",
)

private object NativeOpenSettingsStub : Fingerprint(
    definingClass = QUALITY_CONTROLLER,
    name = "nativeOpenSettings",
    parameters = listOf("Landroid/content/Context;", "I"),
    returnType = "V",
)

/**
 * Shared internal observation & Now Playing audio quality sheet pipeline used by
 * [audioQualitySheetPatch] and [usbDacOutputPatch] so either patch can be selected
 * independently or together without duplicating hooks.
 */
internal val audioQualityBasePatch = bytecodePatch {
    dependsOn(audioQualityBaseResources)
    extendWith("extensions/extension.mpe")

    execute {
        // 0. Initialize monitor and observers at Application.onCreate.
        val appOnCreate = AppleMusicAppOnCreate.method
        val superOnCreateIdx = appOnCreate.instructions.indexOfFirst { it.opcode == Opcode.INVOKE_SUPER }
        check(superOnCreateIdx >= 0) { "Missing super.onCreate() in AppleMusicApplication.onCreate." }
        appOnCreate.addInstruction(
            superOnCreateIdx + 1,
            "invoke-static/range {p0 .. p0}, $QUALITY_MONITOR->initialize(Landroid/content/Context;)V",
        )

        // 1. Wire native Audio Quality Settings navigation into AudioQualityController.nativeOpenSettings.
        val openSettingsMethod = AudioQualitySettingsClick.method.instructions
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .first { it.parameterTypes == listOf("Landroid/content/Context;", "I") && it.returnType == "V" }
        NativeOpenSettingsStub.method.addInstruction(
            0,
            "invoke-static {p0, p1}, $openSettingsMethod",
        )

        // 2. Intercept Now Playing audio quality badge clicks (Lossless, Hi-Res Lossless, Dolby Atmos).
        val clickClass = PlayerProgressSeekBarClick.classDef
        val clickMethod = PlayerProgressSeekBarClick.method
        val superClassType = checkNotNull(clickClass.superclass) { "Missing superclass on seekbar binding." }
        val superClass = mutableClassDefBy(superClassType)

        val clickInstructions = clickMethod.instructions
        val bindingObjectReads = clickInstructions
            .filter { it.opcode == Opcode.IGET_OBJECT }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
            .filter {
                (it.definingClass == superClassType || it.definingClass == clickClass.type) &&
                    it.type.startsWith("Lcom/apple/android/music/player/")
            }
            .distinctBy { it.name to it.type }
        check(bindingObjectReads.size >= 2) {
            "Expected handler and state fields in seekbar click binding, found $bindingObjectReads"
        }

        val firstStateIntRead = clickInstructions
            .filter { it.opcode == Opcode.IGET }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
            .first { ref -> bindingObjectReads.any { it.type == ref.definingClass } && ref.type == "I" }
        val stateType = firstStateIntRead.definingClass
        val stateField = bindingObjectReads.single { it.type == stateType }
        val handlerField = bindingObjectReads.first { it.type != stateType }

        val getContextMethod = clickInstructions
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .first {
                it.parameterTypes.isEmpty() &&
                    (it.returnType == "Lcom/apple/android/music/common/activity/BaseActivity;" ||
                        it.returnType == "Landroid/content/Context;")
            }

        val audioVariantField = firstStateIntRead

        val sampleRateIndex = clickInstructions.indexOfFirst { ins ->
            ins.opcode == Opcode.IGET &&
                ((ins as? ReferenceInstruction)?.reference as? FieldReference)?.let {
                    it.definingClass == stateType && it.type == "I" && it.name != audioVariantField.name
                } == true &&
                clickInstructions.getOrNull(clickInstructions.indexOf(ins) + 1)?.opcode == Opcode.INT_TO_FLOAT
        }
        check(sampleRateIndex >= 0) { "Could not locate sampleRate field read in seekbar click handler." }
        val sampleRateField = (clickInstructions[sampleRateIndex] as ReferenceInstruction).reference as FieldReference

        val codecField = clickInstructions
            .filter { it.opcode == Opcode.IGET_OBJECT }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
            .first { it.definingClass == stateType && it.type == "Ljava/lang/String;" }

        val bitDepthField = clickInstructions.drop(sampleRateIndex + 1)
            .filter { it.opcode == Opcode.IGET }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
            .first {
                it.definingClass == stateType &&
                    it.type == "I" &&
                    it.name != audioVariantField.name &&
                    it.name != sampleRateField.name
            }

        val capabilitiesField = clickInstructions
            .filter { it.opcode == Opcode.IGET }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
            .first {
                it.definingClass == stateType &&
                    it.type == "I" &&
                    it.name != audioVariantField.name &&
                    it.name != sampleRateField.name &&
                    it.name != bitDepthField.name
            }

        clickClass.addQualityHelperMethod(
            name = "voidhackHandleAudioBadgeClick",
            parameters = listOf("Landroid/view/View;"),
            returnType = "Z",
            registers = 10,
            accessFlags = AccessFlags.PRIVATE.value,
            body = """
                iget-object v0, p0, $handlerField
                if-eqz v0, :fallback
                iget-object v7, p0, $stateField
                if-eqz v7, :fallback
                invoke-virtual {v0}, $getContextMethod
                move-result-object v1
                if-eqz v1, :fallback
                move-object v0, p1
                iget v2, v7, $audioVariantField
                iget v3, v7, $bitDepthField
                iget v4, v7, $sampleRateField
                iget-object v5, v7, $codecField
                iget v6, v7, $capabilitiesField
                invoke-static/range {v0 .. v6}, $QUALITY_CONTROLLER->onBadgeClicked(Landroid/view/View;Landroid/content/Context;IIILjava/lang/String;I)Z
                move-result v0
                return v0
                :fallback
                const/4 v0, 0x0
                return v0
            """,
        )

        clickMethod.addInstructionsWithLabels(
            0,
            """
                invoke-direct {p0, p1}, ${clickClass.type}->voidhackHandleAudioBadgeClick(Landroid/view/View;)Z
                move-result v0
                if-eqz v0, :voidhack_native_click
                return-void
            """,
            ExternalLabel("voidhack_native_click", clickMethod.getInstruction(0)),
        )

        // 3. Also make the Dolby Atmos badge (audio_badge_no_text, the sole ImageView field on superclass) clickable.
        val audioBadgeNoTextField = superClass.instanceFields.single { it.type == "Landroid/widget/ImageView;" }
        val executeBindings = clickClass.methods.single { method ->
            method.parameterTypes.isEmpty() && method.returnType == "V" &&
                method.implementation?.instructions?.any { ins ->
                    val ref = (ins as? ReferenceInstruction)?.reference as? MethodReference
                    ref?.name == "setOnClickListener" &&
                        ref.parameterTypes == listOf("Landroid/view/View\$OnClickListener;")
                } == true
        }
        val setClickIdx = executeBindings.instructions.indexOfFirst { ins ->
            val ref = (ins as? ReferenceInstruction)?.reference as? MethodReference
            ref?.name == "setOnClickListener" &&
                ref.parameterTypes == listOf("Landroid/view/View\$OnClickListener;")
        }
        check(setClickIdx > 0) { "Could not locate setOnClickListener in seekbar executeBindings." }
        val clickListenerField = executeBindings.instructions
            .subList(0, setClickIdx)
            .reversed()
            .filter { it.opcode == Opcode.IGET_OBJECT }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
            .first { it.definingClass == clickClass.type }

        val ctor = clickClass.methods.single { it.name == "<init>" }
        val ctorReturnIdx = ctor.instructions.indexOfLast { it.opcode == Opcode.RETURN_VOID }
        check(ctorReturnIdx > 0) { "Missing return-void in seekbar binding constructor." }
        ctor.addInstructions(
            ctorReturnIdx,
            """
                move-object/from16 v0, p0
                iget-object v1, v0, ${superClass.type}->${audioBadgeNoTextField.name}:${audioBadgeNoTextField.type}
                iget-object v0, v0, $clickListenerField
                invoke-static {v1, v0}, $QUALITY_CONTROLLER->bindSecondaryBadge(Landroid/view/View;$ON_CLICK_LISTENER)V
            """,
        )

        // 4. Observe ExoMediaPlayer source format changes (bitrate, channels, codecs, variant).
        val exoClass = ExoAudioFormatChanged.classDef
        val exoMethod = ExoAudioFormatChanged.method
        exoClass.addQualityHelperMethod(
            name = "voidhackReportExoFormat",
            parameters = listOf(EXO_FORMAT, "I"),
            returnType = "V",
            registers = 12,
            accessFlags = AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
            body = """
                move-object/from16 v8, p0
                if-nez v8, :have_format
                return-void
                :have_format
                move/from16 v0, p1
                iget v1, v8, $EXO_FORMAT->bitDepth:I
                iget v2, v8, $EXO_FORMAT->sampleRate:I
                iget v3, v8, $EXO_FORMAT->channelCount:I
                iget v4, v8, $EXO_FORMAT->bitrate:I
                invoke-static {v8}, $PLAYBACK_FORMAT->fromFormat($EXO_FORMAT)$PLAYBACK_FORMAT
                move-result-object v7
                if-eqz v7, :have_bitrate
                invoke-virtual {v7}, $PLAYBACK_FORMAT->getBitRate()I
                move-result v9
                if-lez v9, :have_bitrate
                move v4, v9
                :have_bitrate
                iget-object v5, v8, $EXO_FORMAT->sampleMimeType:Ljava/lang/String;
                iget-object v6, v8, $EXO_FORMAT->codecs:Ljava/lang/String;
                invoke-static/range {v0 .. v6}, $QUALITY_MONITOR->onExoFormatChanged(IIIIILjava/lang/String;Ljava/lang/String;)V
                return-void
            """,
        )
        val getVariantIdx = exoMethod.instructions.indexOfFirst { ins ->
            val ref = (ins as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == EXO_PLAYER && ref.name == "getTrackVariant"
        }
        check(getVariantIdx >= 0) { "Missing getTrackVariant call in ExoMediaPlayer.sendAudioFormatChangeEvent." }
        val moveVariant = exoMethod.getInstruction(getVariantIdx + 1) as OneRegisterInstruction
        val variantReg = moveVariant.registerA
        exoMethod.addInstruction(
            getVariantIdx + 2,
            "invoke-static {p2, v$variantReg}, $EXO_PLAYER->voidhackReportExoFormat(${EXO_FORMAT}I)V",
        )

        // 5. Observe SVAudioRendererV2 decoded ALAC/AAC parameters & Sound Check state.
        val svClass = SvInitializeAudioSink.classDef
        val svMethod = SvInitializeAudioSink.method
        svClass.addQualityHelperMethod(
            name = "voidhackReportDecoder",
            parameters = emptyList(),
            returnType = "V",
            registers = 9,
            accessFlags = AccessFlags.PRIVATE.value,
            body = """
                iget v0, p0, $SV_RENDERER->decoderOutputBitsPerChannel:I
                iget v1, p0, $SV_RENDERER->decoderOutputSampleRate:I
                iget v2, p0, $SV_RENDERER->decoderOutputNumOfChannels:I
                iget v3, p0, $SV_RENDERER->decoderOutputBitRate:I
                iget-object v4, p0, $SV_RENDERER->decoderOutputMimeType:Ljava/lang/String;
                iget-object v5, p0, $SV_RENDERER->decoderOutputCodec:Ljava/lang/String;
                iget-boolean v6, p0, $SV_RENDERER->isSoundCheckEnabled:Z
                invoke-static/range {v0 .. v6}, $QUALITY_MONITOR->onDecoderConfigured(IIIILjava/lang/String;Ljava/lang/String;Z)V
                return-void
            """,
        )
        svMethod.addInstruction(
            0,
            "invoke-direct/range {p0 .. p0}, $SV_RENDERER->voidhackReportDecoder()V",
        )

        // 6. Observe DefaultAudioSink configure / initialize / flush for read-only AudioTrack & pipeline inspection.
        SinkConfigureObserve.method.addInstruction(
            0,
            "invoke-static/range {p1 .. p3}, $QUALITY_MONITOR->onSinkConfigureInput(III)V",
        )

        val sinkClass = SinkInitialize.classDef
        val sinkInitMethod = SinkInitialize.method
        sinkClass.addQualityHelperMethod(
            name = "voidhackReportAudioTrack",
            parameters = emptyList(),
            returnType = "V",
            registers = 9,
            accessFlags = AccessFlags.PRIVATE.value,
            body = """
                iget-object v0, p0, $DEFAULT_AUDIO_SINK->audioTrack:Landroid/media/AudioTrack;
                iget-object v6, p0, $DEFAULT_AUDIO_SINK->configuration:$SINK_CONFIG
                if-eqz v0, :done
                if-eqz v6, :done
                iget-boolean v1, v6, $SINK_CONFIG->isInputPcm:Z
                iget v2, v6, $SINK_CONFIG->inputSampleRate:I
                iget v3, v6, $SINK_CONFIG->outputSampleRate:I
                iget v4, v6, $SINK_CONFIG->outputEncoding:I
                iget v5, v6, $SINK_CONFIG->outputChannelConfig:I
                invoke-static/range {v0 .. v5}, $QUALITY_MONITOR->onAudioTrackInitialized(Landroid/media/AudioTrack;ZIIII)V
                :done
                return-void
            """,
        )
        val putTrackIdx = sinkInitMethod.instructions.indexOfFirst { ins ->
            ins.opcode == Opcode.IPUT_OBJECT &&
                ((ins as? ReferenceInstruction)?.reference as? FieldReference)?.let {
                    it.definingClass == DEFAULT_AUDIO_SINK && it.name == "audioTrack"
                } == true
        }
        check(putTrackIdx >= 0) { "Missing audioTrack assignment in DefaultAudioSink.initialize." }
        sinkInitMethod.addInstruction(
            putTrackIdx + 1,
            "invoke-direct/range {p0 .. p0}, $DEFAULT_AUDIO_SINK->voidhackReportAudioTrack()V",
        )

        val flushMethod = SinkFlushObserve.method
        val clearTrackIdx = flushMethod.instructions.indexOfFirst { ins ->
            ins.opcode == Opcode.IPUT_OBJECT &&
                ((ins as? ReferenceInstruction)?.reference as? FieldReference)?.let {
                    it.definingClass == DEFAULT_AUDIO_SINK && it.name == "audioTrack"
                } == true
        }
        check(clearTrackIdx > 0) { "Missing audioTrack clear in DefaultAudioSink.flush." }
        val prevRead = flushMethod.getInstruction(clearTrackIdx - 1)
        check(prevRead.opcode == Opcode.IGET_OBJECT) {
            "Expected audioTrack read right before clear in DefaultAudioSink.flush, found ${prevRead.opcode}"
        }
        val flushedTrackReg = (prevRead as TwoRegisterInstruction).registerA
        flushMethod.addInstruction(
            clearTrackIdx,
            "invoke-static/range {v$flushedTrackReg .. v$flushedTrackReg}, $QUALITY_MONITOR->onAudioTrackReleased(Landroid/media/AudioTrack;)V",
        )
    }
}
