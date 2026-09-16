package com.github.issakk.cfc;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Moves a completed cimbar transfer out of the app's private temp dir and into the
 * public Downloads folder, so the file is visible in any file manager.
 *
 * API 29+: MediaStore.Downloads (no permission needed).
 * API 21-28: the public Downloads dir directly (needs WRITE_EXTERNAL_STORAGE).
 *
 * The source file is only deleted once the copy succeeded -- a failed publish leaves
 * the data alone so the app can retry it from the inbox.
 */
final class FilePublisher {
    private static final String TAG = "cfc::FilePublisher";
    static final String DIR_NAME = "CameraFileCopy";
    private static final int BUFFER_SIZE = 8192;

    private FilePublisher() {}

    static class Result {
        final boolean ok;
        final Uri uri;          // content uri of the saved file, usable for open/share
        final String location;  // human readable, e.g. "Download/CameraFileCopy/photo.jpg"
        final String error;     // reason for failure, for logging/UI

        private Result(boolean ok, Uri uri, String location, String error) {
            this.ok = ok;
            this.uri = uri;
            this.location = location;
            this.error = error;
        }

        static Result saved(Uri uri, String location) {
            return new Result(true, uri, location, null);
        }

        static Result failed(String error) {
            return new Result(false, null, null, error);
        }
    }

    static Result publish(Context ctx, File src, String displayName) {
        if (src == null || !src.isFile())
            return Result.failed("source file is gone");
        if (displayName == null || displayName.isEmpty())
            displayName = src.getName();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            return publishToMediaStore(ctx, src, displayName);
        return publishToLegacyDownloads(ctx, src, displayName);
    }

    private static Result publishToMediaStore(Context ctx, File src, String name) {
        ContentResolver resolver = ctx.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeOf(name));
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME);

        Uri uri = null;
        try {
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null)
                return Result.failed("could not create a Downloads entry");

            OutputStream out = resolver.openOutputStream(uri);
            if (out == null) {
                resolver.delete(uri, null, null);
                return Result.failed("could not open the destination");
            }
            try (InputStream in = new FileInputStream(src)) {
                copy(in, out);
            } finally {
                out.close();
            }

            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
        } catch (Exception e) {
            Log.e(TAG, "MediaStore publish failed: " + e);
            if (uri != null) {
                try {
                    resolver.delete(uri, null, null);
                } catch (Exception ignored) {
                }
            }
            return Result.failed(String.valueOf(e.getMessage() != null ? e.getMessage() : e));
        }

        deleteSource(src);
        return Result.saved(uri, displayLocation(queryDisplayName(resolver, uri, name)));
    }

    /** MediaStore renames on collision ("name (1).ext"), so report what actually landed */
    private static String queryDisplayName(ContentResolver resolver, Uri uri, String fallback) {
        try (Cursor cursor = resolver.query(uri, new String[] { MediaStore.MediaColumns.DISPLAY_NAME },
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty())
                    return name;
            }
        } catch (Exception e) {
            Log.w(TAG, "could not read back the display name: " + e);
        }
        return fallback;
    }

    private static Result publishToLegacyDownloads(Context ctx, File src, String name) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED)
            return Result.failed("storage permission not granted");

        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR_NAME);
        if (!dir.isDirectory() && !dir.mkdirs())
            return Result.failed("could not create " + dir.getAbsolutePath());

        File dest = uniqueFile(dir, name);
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dest)) {
            copy(in, out);
        } catch (Exception e) {
            Log.e(TAG, "legacy publish failed: " + e);
            if (dest.isFile() && !dest.delete())
                Log.w(TAG, "could not clean up partial file " + dest);
            return Result.failed(String.valueOf(e.getMessage() != null ? e.getMessage() : e));
        }

        Uri uri;
        try {
            uri = FileProvider.getUriForFile(ctx, authority(ctx), dest);
        } catch (Exception e) {
            // no shareable uri for it -- drop the half-usable copy, keep the source so the
            // inbox can retry (otherwise every retry adds another stray copy)
            Log.e(TAG, "no content uri for " + dest, e);
            if (dest.isFile() && !dest.delete())
                Log.w(TAG, "could not clean up " + dest);
            return Result.failed(String.valueOf(e.getMessage() != null ? e.getMessage() : e));
        }

        deleteSource(src);
        MediaScannerConnection.scanFile(ctx, new String[]{dest.getAbsolutePath()}, null, null);
        return Result.saved(uri, displayLocation(dest.getName()));
    }

    /** Downloads/CameraFileCopy/name, but never overwrite an existing file */
    static File uniqueFile(File dir, String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists())
            return candidate;

        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 2; i < 1000; ++i) {
            candidate = new File(dir, base + " (" + i + ")" + ext);
            if (!candidate.exists())
                return candidate;
        }
        return new File(dir, base + " (" + System.currentTimeMillis() + ")" + ext);
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        if (in == null || out == null)
            throw new IOException("no stream to copy");
        byte[] buf = new byte[BUFFER_SIZE];
        int length;
        while ((length = in.read(buf)) > 0)
            out.write(buf, 0, length);
        out.flush();
    }

    static String mimeOf(String filename) {
        if (filename != null) {
            int extIdx = filename.lastIndexOf('.');
            if (extIdx >= 0 && extIdx + 1 < filename.length()) {
                String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        filename.substring(extIdx + 1).toLowerCase());
                if (mime != null)
                    return mime;
            }
        }
        return "*/*";
    }

    static String authority(Context ctx) {
        return ctx.getPackageName() + ".fileprovider";
    }

    private static String displayLocation(String name) {
        return Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME + "/" + name;
    }

    private static void deleteSource(File src) {
        if (!src.delete())
            Log.w(TAG, "published " + src.getName() + " but could not delete the temp copy");
    }
}
