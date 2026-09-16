package org.cimbar.camerafilecopy;

import org.opencv.android.CameraActivity;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.GestureDetectorCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends CameraActivity implements CvCameraViewListener2 {
    private static final String TAG = "cfc::MainActivity";

    private static final String PREFS_NAME = "cfc";
    private static final String PREF_MODE = "mode";

    private static final int EXPORT_FILE = 11;
    private static final int REQUEST_STORAGE = 12;

    // decoder modes, as understood by libcimbar
    private static final int MODE_AUTO = 0;
    private static final int MODE_4C = 4;
    private static final int MODE_BU = 66;
    private static final int MODE_BM = 67;
    private static final int MODE_B = 68;
    private static final int[] MODES = { MODE_AUTO, MODE_B, MODE_BM, MODE_BU, MODE_4C };

    // inbox actions
    private static final int ACTION_OPEN = 1;
    private static final int ACTION_RETRY = 2;
    private static final int ACTION_SHARE = 3;
    private static final int ACTION_EXPORT = 4;

    private static final long STATUS_UPDATE_INTERVAL_MS = 200;
    private static final long TOAST_THROTTLE_MS = 1500;

    private GestureDetectorCompat mDetector;
    private Toast introToast;

    private CameraBridgeViewBase mOpenCvCameraView;
    private TextView mStatusText;
    private Button mModeButton;
    private Button mInboxButton;

    private int modeVal = MODE_AUTO;
    private int mDetectedMode = 0;
    private String dataPath;

    private final ArrayList<ReceivedFile> mInbox = new ArrayList<>();
    private ReceivedFileAdapter mInboxAdapter;
    private AlertDialog mInboxDialog;
    private ReceivedFile mExportItem;

    private HandlerThread mPublishThread;
    private Handler mPublishHandler;

    private ToneGenerator mTone;

    private long mLastStatusPost = 0;
    private int mLastTransferStatus = 0;
    private long mLastToastAt = 0;
    private boolean mNativeReady = false;
    private long mLastCompletionAt = 0;

    public MainActivity() {
        Log.i(TAG, "Instantiated new " + this.getClass());
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "called onCreate");
        super.onCreate(savedInstanceState);

        //! [ocv_loader_init]
        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully");
            System.loadLibrary("cfc-cpp");
            mNativeReady = true;
        } else {
            Log.e(TAG, "OpenCV initialization failed!");
            Toast.makeText(this, R.string.opencv_failed, Toast.LENGTH_LONG).show();
            return;
        }
        //! [ocv_loader_init]

        //! [keep_screen]
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        //! [keep_screen]

        this.dataPath = this.getFilesDir().getPath();

        setContentView(R.layout.activity_main);

        mOpenCvCameraView = findViewById(R.id.main_surface);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);

        mStatusText = findViewById(R.id.status_text);
        mModeButton = findViewById(R.id.btn_mode);
        mInboxButton = findViewById(R.id.btn_inbox);

        findViewById(R.id.btn_send).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSender();
            }
        });
        mModeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showModeDialog();
            }
        });
        mInboxButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showInbox();
            }
        });

        // the mode the user picked is remembered across launches
        modeVal = prefs().getInt(PREF_MODE, MODE_AUTO);

        mInboxAdapter = new ReceivedFileAdapter(this, mInbox);

        // publishing happens off the camera thread, one file at a time
        mPublishThread = new HandlerThread("cfc-publish");
        mPublishThread.start();
        mPublishHandler = new Handler(mPublishThread.getLooper());

        mTone = makeTone();

        // Set up the swipe gestures (shortcut for the send button)
        mDetector = new GestureDetectorCompat(this, new FlingGestureListener());
        introToast = Toast.makeText(this, R.string.intro_hint, Toast.LENGTH_LONG);

        requestLegacyStorageIfNeeded();
        updateModeButton();
        refreshInbox();
        updateStatusText(0, 0);
        recoverLeftovers();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (introToast != null)
            introToast.show();
        // note: the mode is *not* reset here -- the user's choice sticks
    }

    @Override
    public void onPause() {
        super.onPause();
        // the camera is stopped, but the decoder (and the transfer in progress) survives.
        // killing it here used to throw away every partially received file.
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.enableView();
    }

    @Override
    protected List<? extends CameraBridgeViewBase> getCameraViewList() {
        return Collections.singletonList(mOpenCvCameraView);
    }

    @Override
    public void onDestroy() {
        if (mInboxDialog != null)
            mInboxDialog.dismiss();
        if (mPublishHandler != null)
            mPublishHandler.removeCallbacksAndMessages(null);
        if (mPublishThread != null)
            mPublishThread.quitSafely();
        if (mTone != null) {
            mTone.release();
            mTone = null;
        }
        if (mNativeReady) {
            shutdownJNI();
            mNativeReady = false;
        }
        super.onDestroy();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
    }

    @Override
    public void onCameraViewStopped() {
    }

    @Override
    public Mat onCameraFrame(CvCameraViewFrame frame) {
        // get current camera frame as OpenCV Mat object
        Mat mat = frame.rgba();

        // native call to process current camera frame
        String[] newFiles = processImageJNI(mat.getNativeObjAddr(), this.dataPath, this.modeVal);

        // every file that finished since the last frame: none of them may be dropped
        if (newFiles != null) {
            for (final String name : newFiles) {
                if (name == null)
                    continue;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        onFileReceived(name);
                    }
                });
            }
        }

        double progress = 0;
        int transferStatus = 0;
        double[] stats = getStatusJNI();
        if (stats != null && stats.length >= 2) {
            progress = stats[0];
            transferStatus = (int) stats[1];
        }

        long now = System.currentTimeMillis();
        if (transferStatus != mLastTransferStatus || (now - mLastStatusPost) >= STATUS_UPDATE_INTERVAL_MS) {
            mLastStatusPost = now;
            mLastTransferStatus = transferStatus;
            final double progressNow = progress;
            final int statusNow = transferStatus;
            final int detectedNow = detectedModeJNI();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (detectedNow != mDetectedMode) {
                        mDetectedMode = detectedNow;
                        updateModeButton();
                    }
                    updateStatusText(statusNow, progressNow);
                }
            });
        }

        // return processed frame for live preview
        return mat;
    }

    // ---------------------------------------------------------------- decoder

    private native String[] processImageJNI(long matAddr, String dataPath, int modeVal);

    private native double[] getStatusJNI();

    private native int detectedModeJNI();

    private native void shutdownJNI();

    // ---------------------------------------------------------------- receiving

    /** main thread: the decoder just finished this file */
    private void onFileReceived(String name) {
        File temp = new File(dataPath, name);
        if (!temp.isFile()) {
            Log.w(TAG, "decoder reported " + name + " but it is not on disk");
            return;
        }
        ReceivedFile item = new ReceivedFile(name, temp);
        mInbox.add(0, item);
        refreshInbox();
        publishLater(item);
    }

    /** main thread: files left behind by a previous session get published too */
    private void recoverLeftovers() {
        File[] files = new File(dataPath).listFiles();
        if (files == null || files.length == 0)
            return;

        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });

        int recovered = 0;
        for (File file : files) {
            if (!file.isFile())
                continue;
            Log.i(TAG, "recovering " + file.getName() + " from a previous session");
            ReceivedFile item = new ReceivedFile(file.getName(), file);
            mInbox.add(0, item);
            publishLater(item);
            ++recovered;
        }
        refreshInbox();
        if (recovered > 0)
            toast(getString(R.string.toast_leftovers, recovered), true);
    }

    private void publishLater(final ReceivedFile item) {
        if (mPublishHandler == null) {
            applyPublishResult(item, FilePublisher.publish(this, item.tempFile, item.name));
            return;
        }
        mPublishHandler.post(new Runnable() {
            @Override
            public void run() {
                FilePublisher.Result result;
                try {
                    result = FilePublisher.publish(MainActivity.this, item.tempFile, item.name);
                } catch (Exception e) {
                    // an uncaught throw on this thread would take the whole process down
                    Log.e(TAG, "publish threw for " + item.name, e);
                    result = FilePublisher.Result.failed(String.valueOf(e));
                }
                final FilePublisher.Result publishResult = result;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        applyPublishResult(item, publishResult);
                    }
                });
            }
        });
    }

    private void applyPublishResult(ReceivedFile item, FilePublisher.Result result) {
        if (result.ok) {
            item.markSaved(result.uri, result.location);
            announceCompletion();
            toast(getString(R.string.toast_saved, result.location), false);
        } else {
            item.markFailed(result.error);
            // the file is *not* deleted: it stays in the inbox so nothing is lost
            toast(getString(R.string.toast_save_failed, result.error), true);
        }
        refreshInbox();
    }

    private void retryPublish(ReceivedFile item) {
        if (!item.isFailed())
            return; // already queued: a second publish would race the first one
        item.markPending();
        refreshInbox();
        publishLater(item);
    }

    private void refreshInbox() {
        if (mInboxAdapter != null)
            mInboxAdapter.notifyDataSetChanged();
        if (mInboxButton != null)
            mInboxButton.setText(getString(R.string.btn_inbox_fmt, mInbox.size()));
    }

    // ---------------------------------------------------------------- inbox ui

    private void showInbox() {
        if (mInboxDialog != null && mInboxDialog.isShowing()) {
            mInboxDialog.dismiss();
            return;
        }
        if (mInbox.isEmpty()) {
            mInboxDialog = new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.inbox_title_fmt, 0))
                    .setMessage(R.string.inbox_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .create();
            mInboxDialog.show();
            return;
        }

        ListView list = new ListView(this);
        list.setAdapter(mInboxAdapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                ReceivedFile item = mInbox.get(position);
                if (item.isSaved())
                    openItem(item);
                else if (item.isFailed())
                    retryPublish(item);
                else
                    toast(getString(R.string.toast_still_saving, item.name), true);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showItemMenu(mInbox.get(position));
                return true;
            }
        });

        mInboxDialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.inbox_title_fmt, mInbox.size()))
                .setView(list)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        mInboxDialog.show();
    }

    private void showItemMenu(final ReceivedFile item) {
        final List<String> labels = new ArrayList<String>();
        final List<Integer> actions = new ArrayList<Integer>();
        if (item.isSaved()) {
            labels.add(getString(R.string.action_open));
            actions.add(ACTION_OPEN);
        } else if (item.isFailed()) {
            labels.add(getString(R.string.action_retry));
            actions.add(ACTION_RETRY);
        }
        labels.add(getString(R.string.action_share));
        actions.add(ACTION_SHARE);
        labels.add(getString(R.string.action_export));
        actions.add(ACTION_EXPORT);

        new AlertDialog.Builder(this)
                .setTitle(item.name)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (actions.get(which)) {
                            case ACTION_OPEN:
                                openItem(item);
                                break;
                            case ACTION_RETRY:
                                retryPublish(item);
                                break;
                            case ACTION_SHARE:
                                shareItem(item);
                                break;
                            default:
                                startExport(item);
                                break;
                        }
                    }
                })
                .show();
    }

    private void openItem(ReceivedFile item) {
        Uri uri = contentUri(item);
        if (uri == null) {
            toast(getString(R.string.toast_file_gone), true);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, FilePublisher.mimeOf(item.name));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "nothing can open " + item.name, e);
            toast(getString(R.string.toast_open_failed), true);
        }
    }

    private void shareItem(ReceivedFile item) {
        Uri uri = contentUri(item);
        if (uri == null) {
            toast(getString(R.string.toast_file_gone), true);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(FilePublisher.mimeOf(item.name));
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share_title)));
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "no share target", e);
            toast(getString(R.string.toast_open_failed), true);
        }
    }

    private Uri contentUri(ReceivedFile item) {
        if (item.uri() != null)
            return item.uri();
        File temp = item.tempFile;
        if (temp != null && temp.isFile())
            return FileProvider.getUriForFile(this, FilePublisher.authority(this), temp);
        return null;
    }

    /** "save a copy..." -- a user initiated SAF export, never on the receiving path */
    private void startExport(ReceivedFile item) {
        mExportItem = item;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(FilePublisher.mimeOf(item.name));
        intent.putExtra(Intent.EXTRA_TITLE, item.name);
        try {
            startActivityForResult(intent, EXPORT_FILE);
        } catch (ActivityNotFoundException e) {
            mExportItem = null;
            Log.w(TAG, "no file manager for ACTION_CREATE_DOCUMENT", e);
            toast(getString(R.string.toast_open_failed), true);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != EXPORT_FILE) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        ReceivedFile item = mExportItem;
        mExportItem = null;
        if (item == null || resultCode != RESULT_OK || data == null || data.getData() == null)
            return; // cancelled: nothing to clean up, the received copy stays where it is

        InputStream in = null;
        OutputStream out = null;
        try {
            Uri source = contentUri(item);
            in = (source != null)
                    ? getContentResolver().openInputStream(source)
                    : new FileInputStream(item.tempFile);
            out = getContentResolver().openOutputStream(data.getData());
            FilePublisher.copy(in, out);
            toast(getString(R.string.toast_exported, item.name), true);
        } catch (Exception e) {
            Log.e(TAG, "export failed: " + e);
            toast(getString(R.string.toast_export_failed), true);
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null)
            return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- mode

    private void showModeDialog() {
        final String[] entries = new String[MODES.length];
        for (int i = 0; i < MODES.length; ++i)
            entries[i] = modeEntry(MODES[i]);

        int checked = 0;
        for (int i = 0; i < MODES.length; ++i)
            if (MODES[i] == modeVal)
                checked = i;

        new AlertDialog.Builder(this)
                .setTitle(R.string.mode_dialog_title)
                .setSingleChoiceItems(entries, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        setMode(MODES[which]);
                        dialog.dismiss();
                    }
                })
                .setMessage(R.string.mode_dialog_note)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void setMode(int mode) {
        if (mode == modeVal)
            return;
        modeVal = mode;
        mDetectedMode = 0;
        prefs().edit().putInt(PREF_MODE, mode).apply();
        updateModeButton();
        updateStatusText(0, 0);
        toast(getString(R.string.toast_mode, modeName(mode)), true);
    }

    private void updateModeButton() {
        mModeButton.setText(getString(R.string.btn_mode_fmt, modeName(modeVal)));
    }

    private void updateStatusText(int transferStatus, double progress) {
        String line;
        // keep showing "done" for a moment after a file landed -- the decoder goes back
        // to idle within ~a second once the sender stops moving
        boolean recentlyDone = (System.currentTimeMillis() - mLastCompletionAt) < 3000;
        if (transferStatus >= 2 || recentlyDone)
            line = getString(R.string.status_done, statusModeLabel());
        else if (transferStatus == 1)
            line = getString(R.string.status_receiving, statusModeLabel(),
                    (int) Math.round(progress * 100));
        else
            line = getString(R.string.status_idle, statusModeLabel());
        mStatusText.setText(line);
    }

    /** "Auto -> Bm" while auto-detecting, otherwise just the chosen mode */
    private String statusModeLabel() {
        if (modeVal != MODE_AUTO)
            return modeName(modeVal);
        if (mDetectedMode != MODE_AUTO && mDetectedMode != 0)
            return getString(R.string.status_auto_locked, modeName(mDetectedMode));
        return getString(R.string.mode_name_auto);
    }

    private String modeName(int mode) {
        switch (mode) {
            case MODE_4C:
                return getString(R.string.mode_name_4c);
            case MODE_BU:
                return getString(R.string.mode_name_bu);
            case MODE_BM:
                return getString(R.string.mode_name_bm);
            case MODE_B:
                return getString(R.string.mode_name_b);
            default:
                return getString(R.string.mode_name_auto);
        }
    }

    private String modeEntry(int mode) {
        switch (mode) {
            case MODE_4C:
                return getString(R.string.mode_entry_4c);
            case MODE_BU:
                return getString(R.string.mode_entry_bu);
            case MODE_BM:
                return getString(R.string.mode_entry_bm);
            case MODE_B:
                return getString(R.string.mode_entry_b);
            default:
                return getString(R.string.mode_entry_auto);
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- feedback

    private void announceCompletion() {
        mLastCompletionAt = System.currentTimeMillis();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager manager = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (manager != null)
                    vibrate(manager.getDefaultVibrator());
            } else {
                vibrate((Vibrator) getSystemService(Context.VIBRATOR_SERVICE));
            }
        } catch (Exception e) {
            Log.w(TAG, "vibration failed: " + e);
        }

        try {
            if (mTone != null)
                mTone.startTone(ToneGenerator.TONE_PROP_ACK, 150);
        } catch (Exception e) {
            Log.w(TAG, "tone failed: " + e);
        }
    }

    @SuppressWarnings("deprecation")
    private void vibrate(Vibrator vibrator) {
        if (vibrator == null)
            return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE));
        else
            vibrator.vibrate(120);
    }

    private ToneGenerator makeTone() {
        try {
            return new ToneGenerator(AudioManager.STREAM_NOTIFICATION, ToneGenerator.MAX_VOLUME / 2);
        } catch (Exception e) {
            Log.w(TAG, "no ToneGenerator: " + e);
            return null;
        }
    }

    private void toast(String message, boolean important) {
        long now = System.currentTimeMillis();
        if (!important && (now - mLastToastAt) < TOAST_THROTTLE_MS)
            return;
        mLastToastAt = now;
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void requestLegacyStorageIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED)
            return;
        ActivityCompat.requestPermissions(this,
                new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE }, REQUEST_STORAGE);
    }

    // ---------------------------------------------------------------- navigation

    private void openSender() {
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
        if (introToast != null)
            introToast.cancel();
        startActivity(new Intent(this, WebViewActivity.class));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (this.mDetector.onTouchEvent(event)) {
            return true;
        }
        return super.onTouchEvent(event);
    }

    class FlingGestureListener extends GestureDetector.SimpleOnGestureListener {

        // We only want fling gestures to trigger the view transitions, not scrolling.
        @Override
        public boolean onFling(MotionEvent event1, MotionEvent event2,
                               float velocityX, float velocityY) {
            final int THRESHOLD = 100;
            final int VEL_THRESHOLD = 100;

            if (Math.abs(velocityY) < VEL_THRESHOLD)
                return false;
            if (Math.abs(event1.getY() - event2.getY()) < THRESHOLD)
                return false;

            openSender();
            return true;
        }
    }
}
