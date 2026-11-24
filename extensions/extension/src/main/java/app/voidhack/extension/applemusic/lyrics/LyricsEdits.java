package app.voidhack.extension.applemusic.lyrics;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

final class LyricsEdits {
    private static final String TAG = "AppleMusicLrclib";
    private static final String PREFS_NAME = "voidhack_lyrics_line_edits";

    private LyricsEdits() {}

    static String documentKey(String trackKey, String sourceKey, String originalLyrics) {
        if (trackKey == null || trackKey.isEmpty() || sourceKey == null || originalLyrics == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, trackKey);
            update(digest, sourceKey);
            update(digest, originalLyrics);
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest.digest()) {
                result.append(Character.forDigit((b >>> 4) & 0xF, 16));
                result.append(Character.forDigit(b & 0xF, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(Integer.toString(value.length()).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    static Map<Integer, LyricsEditTtml.Edit> load(Context context, String documentKey) {
        if (documentKey == null) return Collections.emptyMap();
        String value = prefs(context).getString(documentKey, null);
        if (value == null) return Collections.emptyMap();
        Map<Integer, LyricsEditTtml.Edit> result = new HashMap<>();
        try {
            JSONObject stored = new JSONObject(value);
            for (Iterator<String> keys = stored.keys(); keys.hasNext();) {
                String key = keys.next();
                JSONObject row = stored.optJSONObject(key);
                if (row != null) {
                    result.put(Integer.parseInt(key), new LyricsEditTtml.Edit(row.optString("text", null),
                            row.optString("reading", null)));
                }
            }
        } catch (JSONException | NumberFormatException e) {
            Log.w(TAG, "Could not read lyric line edits", e);
        }
        return result;
    }

    static synchronized boolean save(Context context, String documentKey, int index, String text, String reading) {
        if (documentKey == null || index < 0) return false;
        SharedPreferences preferences = prefs(context);
        LyricsEditTtml.Edit edit = new LyricsEditTtml.Edit(text, reading);
        try {
            JSONObject stored = new JSONObject(preferences.getString(documentKey, "{}"));
            String key = Integer.toString(index);
            if (edit.text == null && edit.reading == null) {
                stored.remove(key);
            } else {
                JSONObject row = new JSONObject();
                row.put("text", edit.text);
                row.put("reading", edit.reading);
                stored.put(key, row);
            }
            SharedPreferences.Editor editor = preferences.edit();
            if (stored.length() == 0) {
                editor.remove(documentKey);
            } else {
                editor.putString(documentKey, stored.toString());
            }
            return editor.commit();
        } catch (JSONException e) {
            Log.w(TAG, "Could not save lyric line edits", e);
            return false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
