package app.voidhack.extension.applemusic.lyrics;

import android.app.Activity;
import android.content.Context;
import android.text.Html;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class LyricsLineEditor {
    private static final String TAG = "AppleMusicLrclib";
    private static final Map<Object, Sheet> PARSED = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Rendered> VISIBLE = new WeakHashMap<>();

    private static final class Rendered {
        final Sheet sheet;
        final Object song;

        Rendered(Sheet sheet, Object song) {
            this.sheet = sheet;
            this.song = song;
        }
    }

    static final class Sheet {
        final String trackKey;
        final String documentKey;
        final String original;
        final String displayed;

        Sheet(String trackKey, String documentKey, String original, String displayed) {
            this.trackKey = trackKey;
            this.documentKey = documentKey;
            this.original = original;
            this.displayed = displayed;
        }

        Sheet withDisplay(String ttml) {
            return new Sheet(trackKey, documentKey, original, ttml);
        }
    }

    private LyricsLineEditor() {}

    static boolean hasTextEdits(Map<Integer, LyricsEditTtml.Edit> edits) {
        for (LyricsEditTtml.Edit edit : edits.values()) {
            if (edit.text != null) return true;
        }
        return false;
    }

    static Sheet prepare(
            Context context, String trackKey, String sourceKey, String sourceLyrics, String original,
            String generated, boolean wordTiming, boolean hasOfficialReading) {
        String documentKey = LyricsEdits.documentKey(trackKey, sourceKey, sourceLyrics);
        return prepare(context, trackKey, documentKey, LyricsEdits.load(context, documentKey),
                original, generated, wordTiming, hasOfficialReading);
    }

    static Sheet prepare(
            Context context, String trackKey, String documentKey, Map<Integer, LyricsEditTtml.Edit> edits,
            String original, String generated, boolean wordTiming, boolean hasOfficialReading) {
        if (original == null) return new Sheet(trackKey, documentKey, null, null);
        String ttml = generated;
        try {
            String editedText = LyricsEditTtml.applyText(original, edits);
            if (ttml == null || !editedText.equals(original)) {
                ttml = editedText;
                if (!hasOfficialReading && LrclibPrefs.isRomanizationEnabled(context)) {
                    String guide = OfficialLyricsTtml.addGuide(ttml, wordTiming, LrclibRomanizer::create);
                    if (guide != null) ttml = guide;
                }
            }
            ttml = LyricsEditTtml.applyReadings(ttml, edits);
        } catch (Exception error) {
            Log.w(TAG, "Could not apply lyric edits", error);
            ttml = generated != null ? generated : original;
        }
        return new Sheet(trackKey, documentKey, original, ttml);
    }

    static void remember(Object song, Sheet lyrics) {
        if (song != null) PARSED.put(song, lyrics);
    }

    static void bind(Object fragment, Object song) {
        Sheet lyrics = song == null ? null : PARSED.get(song);
        if (lyrics == null) {
            VISIBLE.remove(fragment);
        } else {
            VISIBLE.put(fragment, new Rendered(lyrics, song));
        }
    }

    static boolean onTimedLineHeld(Object fragment, View anchor, Object nativeLine, Runnable share) {
        Rendered rendered = VISIBLE.get(fragment);
        if (rendered == null) return false;
        try {
            Object line = call(nativeLine, "get");
            long start = ((Number) call(line, "getBegin")).longValue();
            String text = normalize(Html.fromHtml((String) call(line, "getHtmlLineText"),
                    Html.FROM_HTML_MODE_COMPACT).toString());
            LyricsEditTtml.Row match = null;
            for (LyricsEditTtml.Row row : LyricsEditTtml.rows(rendered.sheet.displayed)) {
                if ((row.startMs >= 0 && row.startMs != start) || !normalize(row.text).equals(text)) continue;
                if (match != null) return false;
                match = row;
            }
            return open(fragment, anchor, rendered.sheet, match, share);
        } catch (Exception error) {
            Log.w(TAG, "Could not locate the synchronized lyric line", error);
            return false;
        }
    }

    static boolean onStaticLineHeld(Object fragment, View anchor, int displayedLine) {
        Rendered rendered = VISIBLE.get(fragment);
        if (rendered == null || displayedLine < 0) return false;
        try {
            List<LyricsEditTtml.Row> rows = LyricsEditTtml.rows(rendered.sheet.displayed);
            Object song = call(rendered.song, "get");
            Object legacy = song.getClass().getMethod("generateLegacyLyricsLines", String.class).invoke(song, "");
            long count = ((Number) call(legacy, "size")).longValue();
            java.lang.reflect.Method get = legacy.getClass().getMethod("get", long.class);
            List<String> visibleLines = new ArrayList<>();
            for (long i = 0; i < count; i++) {
                String line = (String) get.invoke(legacy, i);
                for (String part : line.split("\n", -1)) visibleLines.add(normalize(part));
            }
            if (displayedLine >= visibleLines.size() || visibleLines.get(displayedLine).isEmpty()) return false;

            int visible = 0;
            for (LyricsEditTtml.Row row : rows) {
                int first = visible;
                String expected = normalize(row.text);
                String matched = "";
                while (!expected.equals(matched) && visible < visibleLines.size()) {
                    String part = visibleLines.get(visible++);
                    if (part.isEmpty()) continue;
                    matched = matched.isEmpty() ? part : matched + " " + part;
                    if (!expected.startsWith(matched)) return false;
                }
                if (!expected.equals(matched)) return false;
                if (displayedLine >= first && displayedLine < visible) {
                    return open(fragment, anchor, rendered.sheet, row, null);
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "Could not locate the unsynchronized lyric line", error);
        }
        return false;
    }

    private static boolean open(
            Object fragment, View anchor, Sheet sheet, LyricsEditTtml.Row line, Runnable share) {
        Activity activity = LrclibReflection.getActivity(fragment);
        if (activity == null || activity.isFinishing() || sheet.documentKey == null || line == null) return false;
        LrclibModels.TrackQuery query = LrclibController.getCurrentTrackQuery(fragment);
        if (query == null || !sheet.trackKey.equals(query.trackKey)) return false;

        try {
            for (LyricsEditTtml.Row original : LyricsEditTtml.rows(sheet.original)) {
                if (original.index != line.index) continue;

                Runnable edit = () -> showDialog(activity, fragment, sheet, line, original);
                if (share == null) {
                    edit.run();
                } else {
                    LyricsEditSheet.showActions(activity, () -> {
                        if (isVisible(fragment, sheet)) edit.run();
                    }, () -> {
                        if (isVisible(fragment, sheet) && anchor.isAttachedToWindow()) share.run();
                    });
                }
                return true;
            }
            return false;
        } catch (Exception error) {
            Log.w(TAG, "Could not open the lyric editor", error);
            return false;
        }
    }

    private static String normalize(String text) {
        return text.replaceAll("[\\s\\u00a0\\u3000]+", " ").trim();
    }

    private static Object call(Object target, String method) throws ReflectiveOperationException {
        return target.getClass().getMethod(method).invoke(target);
    }

    private static boolean isVisible(Object fragment, Sheet sheet) {
        Rendered rendered = VISIBLE.get(fragment);
        return rendered != null && rendered.sheet == sheet;
    }

    private static void showDialog(
            Activity activity, Object fragment, Sheet sheet, LyricsEditTtml.Row line, LyricsEditTtml.Row original) {
        LyricsEditTtml.Edit previous = LyricsEdits.load(activity, sheet.documentKey).get(line.index);
        LyricsEditSheet.showEditor(activity, line.text, line.reading, (textValue, readingValue) -> {
            String replacement = textValue.equals(line.text) ? textValue : singleLine(textValue);
            String replacementReading = readingValue.equals(line.reading)
                    ? (previous == null ? null : previous.reading) : singleLine(readingValue);
            return save(activity, sheet, line.index,
                    replacement.equals(original.text) ? null : replacement, replacementReading);
        }, previous == null ? null : () -> save(activity, sheet, line.index, null, null), () -> {
            if (isVisible(fragment, sheet)) {
                LrclibController.reloadCurrentTrack(activity, fragment, false);
            }
        });
    }

    private static String singleLine(String text) {
        return text.replaceAll("\\R", " ").trim();
    }

    private static boolean save(Activity activity, Sheet sheet, int index, String text, String reading) {
        if (!LyricsEdits.save(activity, sheet.documentKey, index, text, reading)) {
            Toast.makeText(activity, LrclibStrings.text(activity, "edit_lyric_save_failed"), Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }
}
