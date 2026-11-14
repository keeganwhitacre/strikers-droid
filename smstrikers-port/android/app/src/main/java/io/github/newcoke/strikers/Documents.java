package io.github.newcoke.strikers;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

/** Helpers for documents chosen through the system file picker. */
final class Documents {
    private Documents() {}

    /**
     * The file name the provider shows the player. A picker URI's last path segment is a provider
     * document ID, such as "primary:Download/driver.zip", not a name.
     */
    static String displayName(Context context, Uri uri, String fallback) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (RuntimeException ignored) {
            // Some providers reject queries; the fallback still names the import.
        }
        return fallback;
    }
}
