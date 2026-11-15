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
    static final String TOUCH_HAPTICS = "touch_haptics";

    // Opening the app from its icon starts the game straight away; Back in the game reaches Settings.
    static final String AUTO_LAUNCH = "auto_launch";

    // Checks GitHub for a newer release once a day.
    static final String UPDATE_CHECK = "update_check";

    // App locale follows the system when its BCP 47 tag is empty.
    static final String APP_LANGUAGE = "app_language";

    // Installed driver folder name. An empty value selects the system driver.
    static final String DRIVER = "driver";
    static final String TEXTURE_PACK = "texture_pack";

    private final SharedPreferences preferences;

    GamePreferences(Context ctx) {
        preferences = ctx.getSharedPreferences("strikers", Context.MODE_PRIVATE);
    }

    boolean getBoolean(String key) {
        // Match action labels are on by default: the captions are how the touch buttons say what they do.
        final boolean on = TOUCH_HAPTICS.equals(key) || UPDATE_CHECK.equals(key) || TOUCH_ACTION_LABELS.equals(key);
        return preferences.getBoolean(key, on);
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
        // The layout names the file its control positions are saved in, so it needs a real default.
        return preferences.getString(key, TOUCH_LAYOUT.equals(key) ? TOUCH_STANDARD : "");
    }

    void setString(String key, String value) {
        preferences.edit().putString(key, value).apply();
    }
}
