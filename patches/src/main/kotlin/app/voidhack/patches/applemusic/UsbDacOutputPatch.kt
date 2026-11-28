package app.voidhack.patches.applemusic

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags

private val usbDacResources = resourcePatch {
    execute {
        // Bundle userspace USB Audio Class native driver (libvoidhack_usb_audio.so) for every ABI in the APK.
        listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").forEach { abi ->
            if (listApkEntries("lib/$abi/").isNotEmpty()) {
                val resPath = "/applemusic/usb-audio/jni/$abi/libvoidhack_usb_audio.so"
                checkNotNull(UsbSinkConfigure::class.java.getResourceAsStream(resPath)) {
                    "Missing bundled USB audio native library $resPath."
                }.use { input ->
                    get("lib/$abi/libvoidhack_usb_audio.so", false).outputStream().use { input.copyTo(it) }
                }
            }
        }

        // Bundle MIT attribution notice for adapted decent-usb-audio-driver components.
        val noticePath = "/applemusic/quality/notices/LICENSE-DecentUsbAudio.txt"
        checkNotNull(UsbSinkConfigure::class.java.getResourceAsStream(noticePath)) {
            "Missing bundled notice $noticePath."
        }.use { input ->
            get("assets/voidhack/quality/LICENSE-DecentUsbAudio.txt", false).outputStream().use { input.copyTo(it) }
        }
    }
}

private object UsbSinkConfigure : Fingerprint(
    definingClass = DEFAULT_AUDIO_SINK,
    name = "configure",
    parameters = listOf("I", "I", "I", "I", "[I", "I", "I"),
    returnType = "V",
)

private object UsbSinkFlush : Fingerprint(
    definingClass = DEFAULT_AUDIO_SINK,
    name = "flush",
    parameters = emptyList(),
    returnType = "V",
)

private object UsbSinkHandleBuffer : Fingerprint(
    definingClass = DEFAULT_AUDIO_SINK,
    name = "handleBuffer",
    parameters = listOf("Ljava/nio/ByteBuffer;", "J"),
    returnType = "Z",
)

private object UsbSinkGetCurrentPositionUs : Fingerprint(
    definingClass = DEFAULT_AUDIO_SINK,
    name = "getCurrentPositionUs",
    parameters = listOf("Z"),
    returnType = "J",
)

private object UsbSinkHasPendingData : Fingerprint(
    definingClass = DEFAULT_AUDIO_SINK,
    name = "hasPendingData",
    parameters = emptyList(),
    returnType = "Z",
)

private object UsbSinkIsEnded : Fingerprint(
    definingClass = DEFAULT_AUDIO_SINK,
    name = "isEnded",
    parameters = emptyList(),
    returnType = "Z",
)

private object UsbSinkPlayToEndOfStream : Fingerprint(
    definingClass = DEFAULT_AUDIO_SINK,
    name = "playToEndOfStream",
    parameters = emptyList(),
    returnType = "V",
)

private object UsbSinkPlay : Fingerprint(
    definingClass = DEFAULT_AUDIO_SINK,
    name = "play",
    parameters = emptyList(),
    returnType = "V",
)

private object UsbSinkPause : Fingerprint(
    definingClass = DEFAULT_AUDIO_SINK,
    name = "pause",
    parameters = emptyList(),
    returnType = "V",
)

private object UsbSinkReset : Fingerprint(
    definingClass = DEFAULT_AUDIO_SINK,
    name = "reset",
    parameters = emptyList(),
    returnType = "V",
)

private object UsbNoisyReceiverOnReceive : Fingerprint(
    definingClass = NOISY_RECEIVER,
    name = "onReceive",
    parameters = listOf("Landroid/content/Context;", "Landroid/content/Intent;"),
    returnType = "V",
)

private object UsbSinkWriteNonBlockingV21 : Fingerprint(
    definingClass = DEFAULT_AUDIO_SINK,
    name = "writeNonBlockingV21",
    parameters = listOf("Landroid/media/AudioTrack;", "Ljava/nio/ByteBuffer;", "I"),
    returnType = "I",
)

private object UsbSinkConfigCreateAudioTrackV21 : Fingerprint(
    definingClass = SINK_CONFIG,
    name = "createAudioTrackV21",
    parameters = listOf("Z", EXO_AUDIO_ATTRS, "I"),
    returnType = "Landroid/media/AudioTrack;",
)

@Suppress("unused")
val usbDacOutputPatch = bytecodePatch(
    name = "USB DAC bit-perfect output",
    description = "Adds bit-perfect USB DAC output to bypass Android resampling and mixing via Android 14+ USB exclusive/native-rate mixer modes or an experimental userspace USB Direct driver with DAC hardware volume. Configured from the Now Playing audio quality sheet.",
    default = false,
) {
    dependsOn(audioQualityBasePatch, usbDacResources)
    compatibleWith(appleMusicCompatibility)
    extendWith("extensions/extension.mpe")

    execute {
        enableAudioQualityFeature("usbDacOutput")

        // 1. Track DefaultAudioSink format and flushes for USB Direct playback.
        UsbSinkConfigure.method.addInstruction(
            0,
            "invoke-static/range {p0 .. p3}, $USB_DIRECT_ENGINE->onSinkConfigure(Ljava/lang/Object;III)V",
        )
        UsbSinkFlush.method.addInstruction(
            0,
            "invoke-static/range {p0 .. p0}, $USB_DIRECT_ENGINE->onSinkFlush(Ljava/lang/Object;)V",
        )

        // 2. Android 14+ USB Exclusive Bit-Perfect AudioTrack creation, lossless integer PCM write,
        //    and thread-safe live reconfiguration inside DefaultAudioSink.
        val configClass = UsbSinkConfigCreateAudioTrackV21.classDef
        val createTrackMethod = UsbSinkConfigCreateAudioTrackV21.method
        configClass.addQualityHelperMethod(
            name = "voidhackTryCreateBitPerfectTrack",
            parameters = listOf("Z", EXO_AUDIO_ATTRS, "I"),
            returnType = "Landroid/media/AudioTrack;",
            registers = 12,
            accessFlags = AccessFlags.PRIVATE.value,
            body = """
                move/from16 v6, p1
                if-nez v6, :fallback
                move-object/from16 v7, p0
                iget-boolean v6, v7, $SINK_CONFIG->isInputPcm:Z
                if-eqz v6, :fallback
                move-object/from16 v6, p2
                if-eqz v6, :fallback
                invoke-virtual {v6}, $EXO_AUDIO_ATTRS->getAudioAttributesV21()Landroid/media/AudioAttributes;
                move-result-object v0
                iget v1, v7, $SINK_CONFIG->outputSampleRate:I
                iget v2, v7, $SINK_CONFIG->outputChannelConfig:I
                iget v3, v7, $SINK_CONFIG->outputEncoding:I
                iget v4, v7, $SINK_CONFIG->bufferSize:I
                move/from16 v5, p3
                invoke-static/range {v0 .. v5}, $QUALITY_BIT_PERFECT->tryCreateBitPerfectAudioTrack(Landroid/media/AudioAttributes;IIIII)Landroid/media/AudioTrack;
                move-result-object v0
                return-object v0
                :fallback
                const/4 v0, 0x0
                return-object v0
            """,
        )
        createTrackMethod.addInstructionsWithLabels(
            0,
            """
                invoke-direct/range {p0 .. p3}, $SINK_CONFIG->voidhackTryCreateBitPerfectTrack(Z${EXO_AUDIO_ATTRS}I)Landroid/media/AudioTrack;
                move-result-object v0
                if-nez v0, :voidhack_return_bp
                goto :voidhack_native_track
                :voidhack_return_bp
                return-object v0
            """,
            ExternalLabel("voidhack_native_track", createTrackMethod.getInstruction(0)),
        )

        UsbSinkWriteNonBlockingV21.method.addInstructions(
            0,
            """
                invoke-static {p0, p1, p2}, $QUALITY_BIT_PERFECT->writeNonBlocking(Landroid/media/AudioTrack;Ljava/nio/ByteBuffer;I)I
                move-result v0
                return v0
            """,
        )

        val sinkClass = UsbSinkHandleBuffer.classDef
        sinkClass.addQualityHelperMethod(
            name = "voidhackHandleDirectOrReconfigure",
            parameters = listOf("Ljava/nio/ByteBuffer;", "J"),
            returnType = "I",
            registers = 9,
            accessFlags = AccessFlags.PRIVATE.value,
            body = """
                iget-object v0, p0, $DEFAULT_AUDIO_SINK->inputBuffer:Ljava/nio/ByteBuffer;
                if-nez v0, :check_usb_direct
                iget-object v0, p0, $DEFAULT_AUDIO_SINK->pendingConfiguration:$SINK_CONFIG
                if-nez v0, :check_usb_direct
                iget-object v0, p0, $DEFAULT_AUDIO_SINK->configuration:$SINK_CONFIG
                if-eqz v0, :check_usb_direct
                iget-boolean v1, v0, $SINK_CONFIG->isInputPcm:Z
                if-eqz v1, :check_usb_direct
                invoke-static {}, $QUALITY_BIT_PERFECT->consumeReconfigureRequest()Z
                move-result v0
                if-eqz v0, :check_usb_direct
                const/4 v0, 0x1
                iput-boolean v0, p0, $DEFAULT_AUDIO_SINK->audioTrackReconfiguring:Z
                invoke-virtual {p0}, $DEFAULT_AUDIO_SINK->flush()V
                :check_usb_direct
                move-object/from16 v0, p0
                move-object/from16 v1, p1
                move-wide/from16 v2, p2
                invoke-static/range {v0 .. v3}, $USB_DIRECT_ENGINE->tryHandleBuffer(Ljava/lang/Object;Ljava/nio/ByteBuffer;J)I
                move-result v0
                return v0
            """,
        )
        val handleBufferMethod = UsbSinkHandleBuffer.method
        handleBufferMethod.addInstructionsWithLabels(
            0,
            """
                invoke-direct/range {p0 .. p3}, $DEFAULT_AUDIO_SINK->voidhackHandleDirectOrReconfigure(Ljava/nio/ByteBuffer;J)I
                move-result v0
                if-ltz v0, :voidhack_native_handle_buffer
                if-eqz v0, :voidhack_hb_false
                const/4 v0, 0x1
                return v0
                :voidhack_hb_false
                const/4 v0, 0x0
                return v0
            """,
            ExternalLabel("voidhack_native_handle_buffer", handleBufferMethod.getInstruction(0)),
        )

        // 3. Userspace USB Direct lifecycle, playback clock, EOS, and AUDIO_BECOMING_NOISY suppression.
        val getPosMethod = UsbSinkGetCurrentPositionUs.method
        getPosMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static {p0}, $USB_DIRECT_ENGINE->isActiveForSink(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :voidhack_native_pos
                invoke-static {p0, p1}, $USB_DIRECT_ENGINE->getCurrentPositionUs(Ljava/lang/Object;Z)J
                move-result-wide v0
                return-wide v0
            """,
            ExternalLabel("voidhack_native_pos", getPosMethod.getInstruction(0)),
        )

        val hasPendingMethod = UsbSinkHasPendingData.method
        hasPendingMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static {p0}, $USB_DIRECT_ENGINE->tryHasPendingData(Ljava/lang/Object;)I
                move-result v0
                if-ltz v0, :voidhack_native_pending
                if-eqz v0, :voidhack_pending_false
                const/4 v0, 0x1
                return v0
                :voidhack_pending_false
                const/4 v0, 0x0
                return v0
            """,
            ExternalLabel("voidhack_native_pending", hasPendingMethod.getInstruction(0)),
        )

        val isEndedMethod = UsbSinkIsEnded.method
        isEndedMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static {p0}, $USB_DIRECT_ENGINE->tryIsEnded(Ljava/lang/Object;)I
                move-result v0
                if-ltz v0, :voidhack_native_ended
                if-eqz v0, :voidhack_ended_false
                const/4 v0, 0x1
                return v0
                :voidhack_ended_false
                const/4 v0, 0x0
                return v0
            """,
            ExternalLabel("voidhack_native_ended", isEndedMethod.getInstruction(0)),
        )

        val playToEosMethod = UsbSinkPlayToEndOfStream.method
        playToEosMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static {p0}, $USB_DIRECT_ENGINE->tryPlayToEndOfStream(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :voidhack_native_eos
                return-void
            """,
            ExternalLabel("voidhack_native_eos", playToEosMethod.getInstruction(0)),
        )

        UsbSinkPlay.method.addInstruction(
            0,
            "invoke-static/range {p0 .. p0}, $USB_DIRECT_ENGINE->onSinkPlay(Ljava/lang/Object;)V",
        )

        UsbSinkPause.method.addInstruction(
            0,
            "invoke-static/range {p0 .. p0}, $USB_DIRECT_ENGINE->onSinkPause(Ljava/lang/Object;)V",
        )

        UsbSinkReset.method.addInstruction(
            0,
            "invoke-static/range {p0 .. p0}, $USB_DIRECT_ENGINE->onSinkReset(Ljava/lang/Object;)V",
        )

        val noisyClass = UsbNoisyReceiverOnReceive.classDef
        val noisyReceiveMethod = UsbNoisyReceiverOnReceive.method
        val nativeOnReceiveName = "voidhackNativeOnReceive"
        noisyReceiveMethod.name = nativeOnReceiveName
        noisyClass.addQualityHelperMethod(
            name = "onReceive",
            parameters = listOf("Landroid/content/Context;", "Landroid/content/Intent;"),
            returnType = "V",
            registers = 4,
            accessFlags = AccessFlags.PUBLIC.value,
            body = """
                invoke-static {}, $USB_DIRECT_ENGINE->shouldSuppressBecomingNoisy()Z
                move-result v0
                if-eqz v0, :native
                return-void
                :native
                invoke-virtual {p0, p1, p2}, $NOISY_RECEIVER->$nativeOnReceiveName(Landroid/content/Context;Landroid/content/Intent;)V
                return-void
            """,
        )
    }
}
