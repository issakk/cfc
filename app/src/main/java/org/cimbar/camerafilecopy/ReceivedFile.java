package org.cimbar.camerafilecopy;

import android.content.Context;
import android.net.Uri;
import android.text.format.Formatter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * One received file. Created when the decoder reports a completed transfer;
 * published (moved to Downloads) right after, in the background.
 */
class ReceivedFile {
    enum State { PENDING, SAVED, FAILED }

    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm:ss", Locale.US);

    final String name;
    final File tempFile;   // the app-private copy; deleted once published
    final long size;
    final long receivedAt;

    private State state = State.PENDING;
    private Uri uri;       // content uri of the published copy
    private String location;
    private String error;

    ReceivedFile(String name, File tempFile) {
        this.name = name;
        this.tempFile = tempFile;
        this.size = (tempFile != null && tempFile.isFile()) ? tempFile.length() : 0;
        this.receivedAt = System.currentTimeMillis();
    }

    boolean isSaved() {
        return state == State.SAVED;
    }

    Uri uri() {
        return uri;
    }

    void markSaved(Uri uri, String location) {
        this.state = State.SAVED;
        this.uri = uri;
        this.location = location;
        this.error = null;
    }

    void markFailed(String error) {
        this.state = State.FAILED;
        this.error = error;
    }

    void markPending() {
        this.state = State.PENDING;
    }

    String detailLine(Context ctx) {
        String sizeText = Formatter.formatFileSize(ctx, size);
        String timeText = CLOCK.format(new Date(receivedAt));
        switch (state) {
            case SAVED:
                return ctx.getString(R.string.inbox_detail_saved, sizeText, timeText,
                        location == null ? "" : location);
            case FAILED:
                return ctx.getString(R.string.inbox_detail_failed, sizeText, timeText,
                        error == null ? "?" : error);
            default:
                return ctx.getString(R.string.inbox_detail_pending, sizeText, timeText);
        }
    }
}

class ReceivedFileAdapter extends ArrayAdapter<ReceivedFile> {

    ReceivedFileAdapter(Context context, List<ReceivedFile> items) {
        super(context, android.R.layout.simple_list_item_2, items);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = super.getView(position, convertView, parent);
        TextView line1 = view.findViewById(android.R.id.text1);
        TextView line2 = view.findViewById(android.R.id.text2);
        ReceivedFile item = getItem(position);
        if (item != null) {
            line1.setText(item.name);
            line2.setText(item.detailLine(getContext()));
        }
        return view;
    }
}
