package com.github.issakk.cfc;

import android.content.Context;
import android.net.Uri;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
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

    /** true only when a publish actually failed -- a pending one must not be retried */
    boolean isFailed() {
        return state == State.FAILED;
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

class ReceivedFileAdapter extends BaseAdapter {

    private final Context context;
    private final LayoutInflater inflater;
    private final List<ReceivedFile> items;

    ReceivedFileAdapter(Context context, List<ReceivedFile> items) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.items = items;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public ReceivedFile getItem(int position) {
        return (position >= 0 && position < items.size()) ? items.get(position) : null;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public boolean isEnabled(int position) {
        return true;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null)
            view = inflater.inflate(R.layout.inbox_row, parent, false);

        TextView name = view.findViewById(R.id.row_name);
        TextView detail = view.findViewById(R.id.row_detail);
        ReceivedFile item = getItem(position);
        if (item != null) {
            name.setText(item.name);
            detail.setText(item.detailLine(context));
        }
        return view;
    }
}
