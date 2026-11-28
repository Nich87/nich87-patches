package app.voidhack.extension.applemusic.quality;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Crisp SF-Symbol-inspired geometric vector icons drawn cleanly on Canvas
 * for the Apple Music Audio Quality Signal Path sheet.
 */
final class AudioQualityIcons {
    private AudioQualityIcons() {}

    enum Glyph {
        MUSIC_NOTE,
        PHONE,
        USB,
        BLUETOOTH,
        HEADPHONES_35MM,
        HDMI,
        OPTICAL,
        SPEAKER,
        CAST,
        INFO,
        SETTINGS,
        CLOSE,
        CHEVRON_RIGHT
    }

    static Drawable create(Glyph glyph, int color, int sizePx) {
        return new GlyphDrawable(glyph, color, sizePx);
    }

    private static final class GlyphDrawable extends Drawable {
        private final Glyph glyph;
        private final int sizePx;
        private final Paint stroke;
        private final Paint fill;
        private final Path path = new Path();
        private final RectF rect = new RectF();

        GlyphDrawable(Glyph glyph, int color, int sizePx) {
            this.glyph = glyph;
            this.sizePx = sizePx;

            stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setColor(color);

            fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(color);
        }

        @Override
        public int getIntrinsicWidth() {
            return sizePx;
        }

        @Override
        public int getIntrinsicHeight() {
            return sizePx;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            float s = Math.min(b.width(), b.height());
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float u = s / 24f; // 24x24 design grid

            canvas.save();
            canvas.translate(cx - 12f * u, cy - 12f * u);
            stroke.setStrokeWidth(1.85f * u);

            switch (glyph) {
                case MUSIC_NOTE:
                    drawMusicNote(canvas, u);
                    break;
                case PHONE:
                    drawPhone(canvas, u);
                    break;
                case USB:
                    drawUsb(canvas, u);
                    break;
                case BLUETOOTH:
                    drawBluetooth(canvas, u);
                    break;
                case HEADPHONES_35MM:
                    drawHeadphones(canvas, u);
                    break;
                case HDMI:
                    drawHdmi(canvas, u);
                    break;
                case OPTICAL:
                    drawOptical(canvas, u);
                    break;
                case SPEAKER:
                    drawSpeaker(canvas, u);
                    break;
                case CAST:
                    drawCast(canvas, u);
                    break;
                case INFO:
                    drawInfo(canvas, u);
                    break;
                case SETTINGS:
                    drawSliders(canvas, u);
                    break;
                case CLOSE:
                    drawClose(canvas, u);
                    break;
                case CHEVRON_RIGHT:
                    drawChevronRight(canvas, u);
                    break;
            }
            canvas.restore();
        }

        private void drawMusicNote(Canvas c, float u) {
            // Double beamed eighth notes (Apple Music signature silhouette)
            c.drawCircle(7.4f * u, 17.6f * u, 2.5f * u, fill);
            c.drawCircle(16.6f * u, 15.8f * u, 2.5f * u, fill);
            stroke.setStrokeWidth(1.9f * u);
            c.drawLine(9.5f * u, 17.2f * u, 9.5f * u, 6.2f * u, stroke);
            c.drawLine(18.7f * u, 15.4f * u, 18.7f * u, 4.4f * u, stroke);
            path.reset();
            path.moveTo(9.5f * u, 6.2f * u);
            path.lineTo(18.7f * u, 4.2f * u);
            path.lineTo(18.7f * u, 7.2f * u);
            path.lineTo(9.5f * u, 9.2f * u);
            path.close();
            c.drawPath(path, fill);
        }

        private void drawPhone(Canvas c, float u) {
            stroke.setStrokeWidth(1.75f * u);
            rect.set(6.5f * u, 2.8f * u, 17.5f * u, 21.2f * u);
            c.drawRoundRect(rect, 2.8f * u, 2.8f * u, stroke);
            // Dynamic Island / top speaker pill
            rect.set(10.2f * u, 4.8f * u, 13.8f * u, 5.9f * u);
            c.drawRoundRect(rect, 0.55f * u, 0.55f * u, fill);
            // Bottom home indicator
            rect.set(9.8f * u, 18.6f * u, 14.2f * u, 19.5f * u);
            c.drawRoundRect(rect, 0.45f * u, 0.45f * u, fill);
        }

        private void drawUsb(Canvas c, float u) {
            // Clean USB trident symbol
            stroke.setStrokeWidth(1.75f * u);
            // Main vertical stem
            c.drawLine(12f * u, 19.5f * u, 12f * u, 5.2f * u, stroke);
            // Top arrow head
            path.reset();
            path.moveTo(9.5f * u, 7.2f * u);
            path.lineTo(12f * u, 3.6f * u);
            path.lineTo(14.5f * u, 7.2f * u);
            path.close();
            c.drawPath(path, fill);
            // Bottom circle
            c.drawCircle(12f * u, 19.2f * u, 2.1f * u, fill);
            // Left branch -> circle
            path.reset();
            path.moveTo(12f * u, 15.6f * u);
            path.quadTo(7.4f * u, 14.2f * u, 7.4f * u, 10.8f * u);
            c.drawPath(path, stroke);
            c.drawCircle(7.4f * u, 10.2f * u, 1.55f * u, fill);
            // Right branch -> rounded square
            path.reset();
            path.moveTo(12f * u, 13.6f * u);
            path.quadTo(16.6f * u, 12.2f * u, 16.6f * u, 9.2f * u);
            c.drawPath(path, stroke);
            rect.set(15.2f * u, 7.3f * u, 18.0f * u, 10.1f * u);
            c.drawRoundRect(rect, 0.5f * u, 0.5f * u, fill);
        }

        private void drawBluetooth(Canvas c, float u) {
            stroke.setStrokeWidth(1.85f * u);
            path.reset();
            path.moveTo(7.5f * u, 7.5f * u);
            path.lineTo(16.5f * u, 16.0f * u);
            path.lineTo(12.0f * u, 20.2f * u);
            path.lineTo(12.0f * u, 3.8f * u);
            path.lineTo(16.5f * u, 8.0f * u);
            path.lineTo(7.5f * u, 16.5f * u);
            c.drawPath(path, stroke);
        }

        private void drawHeadphones(Canvas c, float u) {
            stroke.setStrokeWidth(1.85f * u);
            rect.set(4.5f * u, 4.5f * u, 19.5f * u, 19.5f * u);
            c.drawArc(rect, 180f, 180f, false, stroke);
            // Left earcup
            rect.set(4.0f * u, 12.0f * u, 7.6f * u, 19.2f * u);
            c.drawRoundRect(rect, 1.8f * u, 1.8f * u, stroke);
            // Right earcup
            rect.set(16.4f * u, 12.0f * u, 20.0f * u, 19.2f * u);
            c.drawRoundRect(rect, 1.8f * u, 1.8f * u, stroke);
        }

        private void drawHdmi(Canvas c, float u) {
            stroke.setStrokeWidth(1.75f * u);
            // TV / HDMI display frame
            rect.set(3.2f * u, 5.2f * u, 20.8f * u, 16.4f * u);
            c.drawRoundRect(rect, 2.0f * u, 2.0f * u, stroke);
            // Stand
            c.drawLine(8.5f * u, 19.5f * u, 15.5f * u, 19.5f * u, stroke);
            c.drawLine(12.0f * u, 16.4f * u, 12.0f * u, 19.5f * u, stroke);
            // Subtle HDMI connector trapezoid inside
            path.reset();
            path.moveTo(8.0f * u, 9.5f * u);
            path.lineTo(16.0f * u, 9.5f * u);
            path.lineTo(16.0f * u, 11.4f * u);
            path.lineTo(14.7f * u, 12.6f * u);
            path.lineTo(9.3f * u, 12.6f * u);
            path.lineTo(8.0f * u, 11.4f * u);
            path.close();
            c.drawPath(path, fill);
        }

        private void drawOptical(Canvas c, float u) {
            stroke.setStrokeWidth(1.75f * u);
            // Optical S/PDIF square jack with inner light core
            rect.set(5.2f * u, 5.2f * u, 18.8f * u, 18.8f * u);
            c.drawRoundRect(rect, 2.6f * u, 2.6f * u, stroke);
            c.drawCircle(12f * u, 12f * u, 3.0f * u, fill);
            c.drawCircle(12f * u, 12f * u, 5.0f * u, stroke);
        }

        private void drawSpeaker(Canvas c, float u) {
            stroke.setStrokeWidth(1.8f * u);
            // Speaker cone
            path.reset();
            path.moveTo(4.2f * u, 9.6f * u);
            path.lineTo(8.0f * u, 9.6f * u);
            path.lineTo(12.6f * u, 5.6f * u);
            path.lineTo(12.6f * u, 18.4f * u);
            path.lineTo(8.0f * u, 14.4f * u);
            path.lineTo(4.2f * u, 14.4f * u);
            path.close();
            c.drawPath(path, fill);
            // Acoustic waves
            rect.set(11.4f * u, 8.2f * u, 16.8f * u, 15.8f * u);
            c.drawArc(rect, -42f, 84f, false, stroke);
            rect.set(12.4f * u, 5.4f * u, 20.2f * u, 18.6f * u);
            c.drawArc(rect, -45f, 90f, false, stroke);
        }

        private void drawCast(Canvas c, float u) {
            stroke.setStrokeWidth(1.8f * u);
            // Outer screen (open at bottom-left for cast arcs)
            path.reset();
            path.moveTo(3.5f * u, 8.5f * u);
            path.lineTo(3.5f * u, 6.2f * u);
            path.quadTo(3.5f * u, 4.8f * u, 5.0f * u, 4.8f * u);
            path.lineTo(19.0f * u, 4.8f * u);
            path.quadTo(20.5f * u, 4.8f * u, 20.5f * u, 6.2f * u);
            path.lineTo(20.5f * u, 17.8f * u);
            path.quadTo(20.5f * u, 19.2f * u, 19.0f * u, 19.2f * u);
            path.lineTo(14.5f * u, 19.2f * u);
            c.drawPath(path, stroke);
            // Cast wireless arcs
            c.drawCircle(4.4f * u, 18.5f * u, 1.1f * u, fill);
            rect.set(0.4f * u, 14.6f * u, 8.4f * u, 22.6f * u);
            c.drawArc(rect, -90f, 90f, false, stroke);
            rect.set(-3.0f * u, 11.2f * u, 11.8f * u, 26.0f * u);
            c.drawArc(rect, -90f, 90f, false, stroke);
        }

        private void drawInfo(Canvas c, float u) {
            stroke.setStrokeWidth(1.75f * u);
            c.drawCircle(12f * u, 12f * u, 8.6f * u, stroke);
            c.drawCircle(12f * u, 8.2f * u, 1.05f * u, fill);
            c.drawLine(12f * u, 11.2f * u, 12f * u, 16.0f * u, stroke);
        }

        private void drawSliders(Canvas c, float u) {
            stroke.setStrokeWidth(1.75f * u);
            c.drawLine(4.5f * u, 7.5f * u, 19.5f * u, 7.5f * u, stroke);
            c.drawLine(4.5f * u, 12.0f * u, 19.5f * u, 12.0f * u, stroke);
            c.drawLine(4.5f * u, 16.5f * u, 19.5f * u, 16.5f * u, stroke);
            c.drawCircle(9.0f * u, 7.5f * u, 1.9f * u, fill);
            c.drawCircle(15.2f * u, 12.0f * u, 1.9f * u, fill);
            c.drawCircle(10.8f * u, 16.5f * u, 1.9f * u, fill);
        }

        private void drawClose(Canvas c, float u) {
            stroke.setStrokeWidth(2.1f * u);
            c.drawLine(6.8f * u, 6.8f * u, 17.2f * u, 17.2f * u, stroke);
            c.drawLine(17.2f * u, 6.8f * u, 6.8f * u, 17.2f * u, stroke);
        }

        private void drawChevronRight(Canvas c, float u) {
            stroke.setStrokeWidth(2.1f * u);
            path.reset();
            path.moveTo(9.2f * u, 6.2f * u);
            path.lineTo(15.0f * u, 12.0f * u);
            path.lineTo(9.2f * u, 17.8f * u);
            c.drawPath(path, stroke);
        }

        @Override
        public void setAlpha(int alpha) {
            stroke.setAlpha(alpha);
            fill.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            stroke.setColorFilter(colorFilter);
            fill.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
