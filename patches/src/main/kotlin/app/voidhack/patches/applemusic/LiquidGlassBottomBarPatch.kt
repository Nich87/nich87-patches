package app.voidhack.patches.applemusic

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION = "Lapp/voidhack/extension/applemusic/LiquidGlass;"
private const val TOUCH_EVENT = "Landroid/view/MotionEvent;"

private object MusicContentViewCreated : Fingerprint(
    definingClass = "Lcom/apple/android/music/common/fragment/MusicContentFragment;",
    name = "onViewCreated",
    parameters = listOf("Landroid/view/View;", "Landroid/os/Bundle;"),
    returnType = "V",
    strings = listOf("miniPlayerBlur", "getDecorView(...)"),
)

private object PlayerSheetSlide : Fingerprint(
    definingClass = "Lcom/apple/android/music/player/fragment/PlayerMainFragment\$i;",
    name = "b",
    parameters = listOf("Landroid/view/View;", "F"),
    returnType = "V",
    strings = listOf("View.NO_ID"),
)

private object MainActivityCreated : Fingerprint(
    definingClass = "Lcom/apple/android/music/common/MainActivity;",
    name = "onCreate",
    parameters = listOf("Landroid/os/Bundle;"),
    returnType = "V",
)

@Suppress("unused")
val liquidGlassBottomBarPatch = bytecodePatch(
    name = "Liquid Glass bottom bar",
    description = "Adds live refractive glass to Apple Music's mini-player and bottom tabs, an animated selection lens, and a glass transition between the mini-player and Now Playing. Refraction requires Android 13 or newer.",
) {
    compatibleWith(appleMusicCompatibility)
    extendWith("extensions/extension.mpe")

    execute {
        MusicContentViewCreated.method.addInstruction(
            0,
            "invoke-static/range {p1 .. p1}, $EXTENSION->install(Landroid/view/View;)V",
        )
        PlayerSheetSlide.method.addInstruction(
            0,
            "invoke-static/range {p1 .. p2}, $EXTENSION->onPlayerSlide(Landroid/view/View;F)V",
        )

        val activity = MainActivityCreated.classDef
        check(activity.methods.none { it.name == "dispatchTouchEvent" && it.parameterTypes == listOf(TOUCH_EVENT) })
        val dispatchTouch = MutableMethod(
            ImmutableMethod(
                activity.type,
                "dispatchTouchEvent",
                listOf(ImmutableMethodParameter(TOUCH_EVENT, emptySet(), null)),
                "Z",
                AccessFlags.PUBLIC.value,
                emptySet(),
                emptySet(),
                ImmutableMethodImplementation(3, emptyList(), emptyList(), emptyList()),
            ),
        )
        dispatchTouch.addInstructions(
            0,
            """
                invoke-static {p0, p1}, $EXTENSION->onPlayerControlTouch(Landroid/app/Activity;${TOUCH_EVENT})Z
                move-result v0
                if-nez v0, :handled
                invoke-super {p0, p1}, ${activity.superclass}->dispatchTouchEvent(${TOUCH_EVENT})Z
                move-result v0
                :handled
                return v0
            """,
        )
        activity.methods.add(dispatchTouch)
    }
}
