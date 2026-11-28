package app.voidhack.extension.applemusic.quality;

import android.content.Context;

import java.util.concurrent.ConcurrentHashMap;

final class AudioQualityStrings {
    private static final String PREFIX = "voidhack_aq_";
    private static final ConcurrentHashMap<String, Integer> CACHE = new ConcurrentHashMap<>();

    private AudioQualityStrings() {}

    static int id(Context context, String type, String name) {
        if (context == null || type == null || name == null) return 0;
        String key = type + ':' + name;
        Integer cached = CACHE.get(key);
        if (cached != null) return cached;
        int resId = context.getResources().getIdentifier(name, type, context.getPackageName());
        if (resId != 0) CACHE.put(key, resId);
        return resId;
    }

    static String text(Context context, String key, Object... args) {
        int resId = id(context, "string", PREFIX + key);
        if (resId == 0) return key;
        try {
            return args.length == 0 ? context.getString(resId) : context.getString(resId, args);
        } catch (RuntimeException ignored) {
            return key;
        }
    }
}
