package app.voidhack.extension.applemusic;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.view.View;
import android.view.ViewGroup;

final class GlassBackdrop implements LiquidGlass.Backdrop {
    private static final float SAMPLE_PADDING_DP = 64f;
    private static final float OPTICAL_DEPTH_DP = 28f;
    private static final float BEVEL_DP = 25f;
    private static final float RIM_DISPERSION = 0.12f;
    private static final float RIM_DEFOCUS_DP = 0.85f;

    private final RenderNode source;
    private final View target;
    private final View navigation;
    private final RenderNode surface = new RenderNode("Apple Music glass backdrop");
    private final RenderNode indicator = new RenderNode("Apple Music optical lens");
    private final RuntimeShader optics = new RuntimeShader(LENS_OPTICS);
    private final RuntimeShader surfaceOptics = new RuntimeShader(GlassShaders.REGULAR);
    private final GlassPyramid pyramid;
    private float surfaceWidth;
    private float surfaceHeight;
    private float surfaceParallax;
    private float surfaceEngagement;
    private boolean surfaceDark;
    private final Paint tabInk = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private final PorterDuffColorFilter activeDark = new PorterDuffColorFilter(
            LiquidGlass.ACTIVE_DARK, PorterDuff.Mode.SRC_IN);
    private final PorterDuffColorFilter activeLight = new PorterDuffColorFilter(
            LiquidGlass.ACTIVE_LIGHT, PorterDuff.Mode.SRC_IN);
    private final int[] targetLocation = new int[2];
    private final int[] navigationLocation = new int[2];
    private final float density;

    GlassBackdrop(RenderNode source, View target, View navigation) {
        this.source = source;
        pyramid = new GlassPyramid(source);
        this.target = target;
        this.navigation = navigation;
        density = navigation.getResources().getDisplayMetrics().density;
    }

    @Override public boolean draw(Canvas canvas, RectF bounds, boolean dark, float parallax, float engagement,
                                  int opacity) {
        if (!canvas.isHardwareAccelerated() || !source.hasDisplayList()) return false;
        int padding = (int) Math.ceil(SAMPLE_PADDING_DP * density);
        recordBackdrop(bounds, dark, padding, parallax, engagement);
        surface.setAlpha(opacity / 255f);
        drawSurface(canvas, bounds, padding);
        return true;
    }

    private void drawSurface(Canvas canvas, RectF bounds, int padding) {
        int save = canvas.save();
        canvas.translate(bounds.left - padding, bounds.top - padding);
        canvas.drawRenderNode(surface);
        canvas.restoreToCount(save);
    }

    private void recordBackdrop(RectF bounds, boolean dark, int padding, float parallax, float engagement) {
        float width = bounds.width();
        float height = bounds.height();
        float size = Math.min(width, height) / density;
        float materialProgress = Math.max(0f, Math.min(1f, (size - 48f) / 112f));
        float maxRadius = (1.5f + 4.5f * materialProgress) * density * 0.4f;
        if (materialProgress > 0f) {
            // Either ambient bleed or the backdrop-colored shadow can need a coarser level.
            maxRadius = Math.max(maxRadius, Math.max(Math.min(0.7f * size, 160f) * 0.2f, 8f) * density);
        }
        target.getLocationOnScreen(targetLocation);
        navigation.getLocationOnScreen(navigationLocation);
        pyramid.record(surface, (int) Math.ceil(width) + padding * 2, (int) Math.ceil(height) + padding * 2,
                padding - bounds.left - navigationLocation[0] + targetLocation[0],
                padding - bounds.top - navigationLocation[1] + targetLocation[1],
                dark ? Color.BLACK : Color.WHITE, maxRadius);
        if (surfaceWidth == width && surfaceHeight == height && surfaceParallax == parallax
                && surfaceEngagement == engagement && surfaceDark == dark) return;

        surfaceWidth = width;
        surfaceHeight = height;
        surfaceParallax = parallax;
        surfaceEngagement = engagement;
        surfaceDark = dark;
        pyramid.setUniforms(surfaceOptics);
        surfaceOptics.setFloatUniform("extent", width, height);
        surfaceOptics.setFloatUniform("center", padding + width / 2f, padding + height / 2f);
        surfaceOptics.setFloatUniform("density", density);
        surfaceOptics.setFloatUniform("dark", dark ? 1f : 0f);
        surfaceOptics.setFloatUniform("light", -0.35f + parallax * 0.45f, -0.85f);
        surfaceOptics.setFloatUniform("interaction", engagement);
        surfaceOptics.setFloatUniform("parallax", parallax * 8f * density);
        surface.setRenderEffect(RenderEffect.createRuntimeShaderEffect(surfaceOptics, "atlas"));
    }

    @Override public void drawLens(Canvas canvas, RectF bounds, boolean dark, float press, ViewGroup menu) {
        if (!canvas.isHardwareAccelerated() || !source.hasDisplayList()) return;
        int padding = (int) Math.ceil(SAMPLE_PADDING_DP * density);
        int width = (int) Math.ceil(bounds.width()) + padding * 2;
        int height = (int) Math.ceil(bounds.height()) + padding * 2;
        bar.set(0f, 0f, navigation.getWidth(), navigation.getHeight());
        indicator.setPosition(0, 0, width, height);
        RecordingCanvas recording = indicator.beginRecording();
        recording.drawColor(dark ? Color.BLACK : Color.WHITE);
        recording.translate(padding - bounds.left, padding - bounds.top);
        int background = recording.save();
        recording.translate(targetLocation[0] - navigationLocation[0], targetLocation[1] - navigationLocation[1]);
        recording.drawRenderNode(source);
        recording.restoreToCount(background);
        if (surface.hasDisplayList()) drawSurface(recording, bar, padding);
        tabInk.setColorFilter(dark ? activeDark : activeLight);
        int tabs = recording.saveLayer(0f, 0f, navigation.getWidth(), navigation.getHeight(), tabInk);
        recording.translate(menu.getLeft(), menu.getTop());
        menu.draw(recording);
        recording.restoreToCount(tabs);
        indicator.endRecording();
        optics.setFloatUniform("extent", bounds.width(), bounds.height());
        optics.setFloatUniform("margin", padding);
        optics.setFloatUniform("depthPx", OPTICAL_DEPTH_DP * density * press);
        optics.setFloatUniform("bevelPx", BEVEL_DP * density);
        optics.setFloatUniform("zoom", 1f + 0.16f * press);
        optics.setFloatUniform("dispersion", RIM_DISPERSION * press);
        optics.setFloatUniform("defocusPx", RIM_DEFOCUS_DP * density * press);
        indicator.setRenderEffect(RenderEffect.createRuntimeShaderEffect(optics, "scene"));
        int save = canvas.save();
        canvas.translate(bounds.left - padding, bounds.top - padding);
        canvas.drawRenderNode(indicator);
        canvas.restoreToCount(save);
    }

    @Override public void release() {
        surface.discardDisplayList();
        pyramid.release();
        indicator.discardDisplayList();
    }

    private static final String LENS_OPTICS =
            "uniform shader scene;\n"
            + "uniform float2 extent; uniform float margin; uniform float depthPx;\n"
            + "uniform float bevelPx; uniform float zoom; uniform float dispersion; uniform float defocusPx;\n"
            + "float2 rimOffset(float3 normal, float index, float thickness) {\n"
            + "  float3 ray = refract(float3(0.0, 0.0, -1.0), normal, 1.0 / index);\n"
            + "  return -ray.xy * depthPx * thickness / max(-ray.z, 0.15);\n"
            + "}\n"
            + "half4 rimSample(float2 at, float2 defocus) {\n"
            + "  return scene.eval(at) * 0.5 + (scene.eval(at - defocus) + scene.eval(at + defocus)) * 0.25;\n"
            + "}\n"
            + "half4 main(float2 pixel) {\n"
            + "  float2 center = extent * 0.5;\n"
            + "  float2 point = pixel - float2(margin) - center;\n"
            + "  float radius = center.y;\n"
            + "  float spine = max(center.x - radius, 0.0);\n"
            + "  float2 radial = float2(point.x - clamp(point.x, -spine, spine), point.y);\n"
            + "  float distance = length(radial);\n"
            + "  float2 direction = radial / max(distance, 0.001);\n"
            + "  float lateral = abs(direction.x);\n"
            + "  float bevelWidth = bevelPx * mix(0.72, 1.0, lateral);\n"
            + "  float bevel = clamp(1.0 - (radius - distance) / bevelWidth, 0.0, 1.0);\n"
            + "  float angle = bevel * 1.5707963;\n"
            + "  float3 normal = float3(direction * sin(angle), cos(angle));\n"
            + "  float edgeThickness = mix(0.80, 0.04, lateral);\n"
            + "  float rolloff = smoothstep(mix(0.35, 0.15, lateral), mix(1.0, 0.90, lateral), bevel);\n"
            + "  float thickness = mix(1.0, edgeThickness, rolloff) * mix(1.0, 2.3, lateral);\n"
            + "  float2 defocus = direction * defocusPx * sqrt(bevel);\n"
            + "  float rimZoom = zoom + (zoom - 1.0) * 0.875 * bevel;\n"
            + "  float2 sampleAt = float2(margin) + center + point / rimZoom;\n"
            + "  half red = rimSample(sampleAt + rimOffset(normal, 1.50 - dispersion, thickness), defocus).r;\n"
            + "  half green = rimSample(sampleAt + rimOffset(normal, 1.50, thickness), defocus).g;\n"
            + "  half blue = rimSample(sampleAt + rimOffset(normal, 1.50 + dispersion, thickness), defocus).b;\n"
            + "  float edge = pow(bevel, 6.0);\n"
            + "  float light = pow(max(dot(normal, normalize(float3(-0.4, -0.65, 0.6))), 0.0), 18.0);\n"
            + "  half3 transmitted = half3(red, green, blue) * half(1.0 - 0.16 * edge);\n"
            + "  return half4(transmitted + half3(light * bevel * 0.025), 1.0);\n"
            + "}\n";
}
