package app.voidhack.patches.applemusic

import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val audioQualitySheetPatch = bytecodePatch(
    name = "Audio quality signal path",
    description = "Shows the live audio signal path (current output, source format, resampling, playback device, connected hardware, and Bluetooth codec) when you tap a Now Playing quality badge.",
) {
    dependsOn(audioQualityBasePatch)
    compatibleWith(appleMusicCompatibility)
    extendWith("extensions/extension.mpe")
}
