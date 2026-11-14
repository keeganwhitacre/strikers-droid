package io.github.newcoke.strikers;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;

/**
 * Lets the share sheet attach the game's logs, so a player can send them without adb. Serves only
 * strikers-log.txt and the newest strikers-crash-log*.txt from the game folder, read-only, and only
 * to apps the share intent grants.
 */
public final class LogProvider extends ContentProvider {
    private static final String LOG = "strikers-log.txt";

    static String authority(Context context) {
        return context.getPackageName() + ".logs";
    }

    /** A share intent with the logs that exist, or null if there are none. */
    static Intent shareIntent(Context context) {
        ArrayList<Uri> uris = new ArrayList<>();
        File home = StrikersHome.homeDir(context);
        File log = new File(home, LOG);
        if (log.isFile()) uris.add(uriFor(context, log));
        File crash = newestCrashLog(home);
        if (crash != null) uris.add(uriFor(context, crash));
        if (uris.isEmpty()) return null;
        Intent send = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Strikers logs")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (uris.size() == 1) send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        else send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        return send;
    }

    private static Uri uriFor(Context context, File file) {
        return new Uri.Builder().scheme("content").authority(authority(context))
                .appendPath(file.getName()).build();
    }

    private static File newestCrashLog(File home) {
        File[] files = home.listFiles((dir, name) -> isCrashLog(name));
        File newest = null;
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && (newest == null || f.lastModified() > newest.lastModified())) newest = f;
        }
        return newest;
    }

    private static boolean isCrashLog(String name) {
        return name.startsWith("strikers-crash-log") && name.endsWith(".txt") && name.indexOf('/') < 0;
    }

    // Only the two kinds of log, by bare name, in the game folder.
    private File resolve(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || uri.getPathSegments().size() != 1
                || !(name.equals(LOG) || isCrashLog(name))) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        File file = new File(StrikersHome.homeDir(getContext()), name);
        if (!file.isFile()) throw new FileNotFoundException(name);
        return file;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("Logs are read-only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        File file;
        try {
            file = resolve(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] columns = projection != null ? projection
                : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
