package io.github.newcoke.strikers;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores Android app settings and their defaults. */
final class GamePreferences {

    // Touch controls.
    static final String TOUCH_ACTION_LABELS = "touch_action_labels";
    static final String SETTINGS_SHORTCUT = "settings_shortcut";
    static final String TOUCH_LAYOUT = "touch_layout";
    static final String TOUCH_STANDARD = "standard";
    static final String TOUCH_COMPACT = "compact";
    static final String TOUCH_TABLET = "tablet";
    static final String SHOW_DPAD = "show_dpad";
    static final String CSTICK = "cstick";
    static final String TOUCH_OPACITY = "touch_opacity";  // Percent.
    static final String TOUCH_SIZE = "touch_size";  // Percent.
    static final String TOUCH_DEADZONE = "touch_deadzone";  // Percent.
    static final String HUD = "hud";

    // App locale follows the system when its BCP 47 tag is empty.
    static final String APP_LANGUAGE = "app_language";

    // Installed driver folder name. An empty value selects the system driver.
    static final String DRIVER = "driver";
    static final String TEXTURE_PACK = "texture_pack";

    private final SharedPreferences preferences;

    GamePreferences(Context ctx) {
        preferences = ctx.getSharedPreferences("strikers", Context.MODE_PRIVATE);
        migrateLegacyKeys();
    }

    private void migrateLegacyKeys() {
        String[] legacyKeys = {"direcional", "opacidade", "tamanho", "zona_morta", "idioma_app"};
        String[] currentKeys = {SHOW_DPAD, TOUCH_OPACITY, TOUCH_SIZE, TOUCH_DEADZONE, APP_LANGUAGE};
        java.util.Map<String, ?> stored = preferences.getAll();
        SharedPreferences.Editor edit = null;
        for (int i = 0; i < legacyKeys.length; i++) {
            if (!stored.containsKey(legacyKeys[i])) continue;
            if (edit == null) edit = preferences.edit();
            String key = currentKeys[i];
            Object value = stored.get(legacyKeys[i]);
            if (!stored.containsKey(key)) {
                if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
                else if (value instanceof Integer) edit.putInt(key, (Integer) value);
                else if (value instanceof String) edit.putString(key, (String) value);
            }
            edit.remove(legacyKeys[i]);
        }
        if (edit != null) edit.apply();
    }

    boolean getBoolean(String key) {
        return preferences.getBoolean(key, false);
    }

    int getInt(String key) {
        switch (key) {
            case TOUCH_OPACITY: return preferences.getInt(key, 100);
            case TOUCH_SIZE: return preferences.getInt(key, 100);
            case TOUCH_DEADZONE: return preferences.getInt(key, 8);
            default: return preferences.getInt(key, 0);
        }
    }

    void setBoolean(String key, boolean value) {
        preferences.edit().putBoolean(key, value).apply();
    }

    void setInt(String key, int value) {
        preferences.edit().putInt(key, value).apply();
    }

    String getString(String key) {
        return preferences.getString(key, "");
    }

    void setString(String key, String value) {
        preferences.edit().putString(key, value).apply();
    }
}
