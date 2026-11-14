package io.github.newcoke.strikers;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells the player when a newer release is on GitHub. Without a store there is no other way to
 * learn of one, and online play needs both players on the same upstream version.
 *
 * At most once a day, only for release builds (CI builds have no release to compare with), and
 * only by opening the release page: the player installs the APK themselves.
 */
final class UpdateCheck {
    private static final String TAG = "strikers";
    private static final String LATEST =
            "https://api.github.com/repos/keeganwhitacre/strikers-droid/releases/latest";
    private static final long INTERVAL_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    // 2.0.0-android.1, as android-release.yml names a release build, with or without its tag prefix.
    private static final Pattern VERSION =
            Pattern.compile("^(?:android-v)?(\\d+)\\.(\\d+)\\.(\\d+)-android\\.(\\d+)$");

    private static final String PREFS = "update-check";
    private static final String CHECKED_AT = "checked_at";
    private static final String SKIPPED = "skipped";

    private UpdateCheck() {}

    static void maybeCheck(Activity activity) {
        if (!new GamePreferences(activity).getBoolean(GamePreferences.UPDATE_CHECK)) return;
        final String installed = installedVersion(activity);
        if (parse(installed) == null) return;
        final SharedPreferences state = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final long now = System.currentTimeMillis();
        final long last = state.getLong(CHECKED_AT, 0);
        if (now >= last && now - last < INTERVAL_MS) return;
        // Recorded before the request, so a phone without a connection does not retry every launch.
        state.edit().putLong(CHECKED_AT, now).apply();

        new Thread(() -> {
            try {
                JSONObject release = new JSONObject(fetch(LATEST));
                String tag = release.optString("tag_name", "");
                String page = release.optString("html_url", "");
                if (!isNewer(tag, installed) || page.isEmpty()) return;
                String latest = tag.replaceFirst("^android-v", "");
                if (latest.equals(state.getString(SKIPPED, null))) return;
                activity.runOnUiThread(() -> offer(activity, state, installed, latest, page));
            } catch (Exception e) {
                Log.i(TAG, "Update check failed: " + e);
            }
        }, "update-check").start();
    }

    private static void offer(Activity activity, SharedPreferences state, String installed,
                              String latest, String page) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new AlertDialog.Builder(activity)
                .setTitle(R.string.update_available)
                .setMessage(activity.getString(R.string.update_message, latest, installed))
                .setPositiveButton(R.string.update_download, (dialog, which) -> {
                    try {
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(page)));
                    } catch (ActivityNotFoundException e) {
                        Log.w(TAG, "No browser for " + page);
                    }
                })
                .setNeutralButton(R.string.update_skip,
                        (dialog, which) -> state.edit().putString(SKIPPED, latest).apply())
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    private static String installedVersion(Context context) {
        try {
            String name = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            return name != null ? name : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static int[] parse(String version) {
        Matcher m = VERSION.matcher(version);
        if (!m.matches()) return null;
        int[] parts = new int[4];
        try {
            for (int i = 0; i < 4; i++) parts[i] = Integer.parseInt(m.group(i + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        return parts;
    }

    static boolean isNewer(String candidate, String installed) {
        int[] a = parse(candidate), b = parse(installed);
        if (a == null || b == null) return false;
        for (int i = 0; i < 4; i++) {
            if (a[i] != b[i]) return a[i] > b[i];
        }
        return false;
    }

    private static String fetch(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "strikers-droid");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + connection.getResponseCode());
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) != -1; ) {
                    if (out.size() + n > MAX_RESPONSE_BYTES) throw new IOException("Response too large");
                    out.write(buffer, 0, n);
                }
                return out.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }
}
