package app.voidhack.patches.applemusic

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch

private const val RECEIVER = "app.voidhack.extension.applemusic.routines.MediaButtonReceiver"
private const val MEDIA_BUTTON = "android.intent.action.MEDIA_BUTTON"

private val mediaButtonManifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val application = checkNotNull(
                manifest.documentElement.getElementsByTagName("application").item(0),
            )
            val receiver = manifest.createElement("receiver").apply {
                setAttribute("android:name", RECEIVER)
                setAttribute("android:exported", "true")
                appendChild(manifest.createElement("intent-filter").apply {
                    appendChild(manifest.createElement("action").apply {
                        setAttribute("android:name", MEDIA_BUTTON)
                    })
                })
            }
            application.appendChild(receiver)
        }
    }
}

@Suppress("unused")
val samsungRoutinesPatch = bytecodePatch(
    name = "Samsung Modes and Routines support",
    description = "Adds Apple Music to Samsung Modes and Routines' Play music action so a routine can resume the current playback queue.",
    default = false,
) {
    compatibleWith(appleMusicCompatibility)
    dependsOn(mediaButtonManifestPatch)
    extendWith("extensions/extension.mpe")
}
