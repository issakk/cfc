package com.github.issakk.cfc;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes the last uncaught exception into filesDir.
 *
 * There is no adb on the machine this app is developed on, so the crash has to come to us:
 * a report dropped in filesDir is picked up by the startup sweep in MainActivity, published
 * to Downloads/CameraFileCopy/ and therefore readable (and sendable) from any file manager.
 */
final class CrashReporter {
    private static final String TAG = "cfc::CrashReporter";
    private static final String PREFIX = "cfc-crash-";

    private CrashReporter() {
    }

    static void install(final Context context) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                write(context, thread, error);
                if (previous != null)
                    previous.uncaughtException(thread, error);
            }
        });
    }

    private static void write(Context context, Thread thread, Throwable error) {
        PrintWriter writer = null;
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File report = new File(context.getFilesDir(), PREFIX + stamp + ".txt");
            writer = new PrintWriter(new FileOutputStream(report));
            writer.println("CameraFileCopy crash report");
            writer.println("when   : " + new Date());
            writer.println("thread : " + thread.getName());
            writer.println("device : " + Build.MANUFACTURER + " " + Build.MODEL);
            writer.println("android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            writer.println();
            error.printStackTrace(writer);
            writer.flush();
            Log.e(TAG, "uncaught exception written to " + report, error);
        } catch (Throwable t) {
            // never let the reporter make things worse
            Log.e(TAG, "could not write the crash report: " + t, t);
        } finally {
            if (writer != null)
                writer.close();
        }
    }
}
