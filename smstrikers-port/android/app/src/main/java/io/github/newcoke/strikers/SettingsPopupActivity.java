package io.github.newcoke.strikers;

import android.os.Bundle;

/**
 * Settings opened from the game: a floating window over the paused match. A separate activity
 * because Android decides from the manifest theme, not one set at run time, whether the game stays
 * drawn behind it.
 */
public class SettingsPopupActivity extends SettingsActivity {
    @Override
    protected void onCreate(Bundle state) {
        popup = true;
        super.onCreate(state);
    }
}
