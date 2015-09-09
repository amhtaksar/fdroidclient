package org.fdroid.fdroid.net;

import android.annotation.TargetApi;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A downloader that uses Android's DownloadManager to perform a download.
 */
@TargetApi(Build.VERSION_CODES.GINGERBREAD)
public class AsyncDownloaderFromAndroid implements AsyncDownloader {
    private final Context context;
    private final DownloadManager dm;
    private File localFile;
    private String remoteAddress;
    private String downloadTitle;
    private String uniqueDownloadId;
    private Listener listener;

    private long downloadManagerId = -1;

    /**
     * Normally the listener would be provided using a setListener method.
     * However for the purposes of this async downloader, it doesn't make
     * sense to have an async task without any way to notify the outside
     * world about completion. Therefore, we require the listener as a
     * parameter to the constructor.
     */
    public AsyncDownloaderFromAndroid(Context context, Listener listener, String downloadTitle, String downloadId, String remoteAddress, File localFile) {
        this.context = context;
        this.downloadTitle = downloadTitle;
        this.uniqueDownloadId = downloadId;
        this.remoteAddress = remoteAddress;
        this.listener = listener;
        this.localFile = localFile;

        if (downloadTitle == null || downloadTitle.trim().length() == 0) {
            this.downloadTitle = remoteAddress;
        }

        dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
    }

    @Override
    public void download() {
        // Check if the download is complete
        if ((downloadManagerId = isDownloadComplete(context, uniqueDownloadId)) > 0) {
            // clear the notification
            dm.remove(downloadManagerId);

            try {
                // write the downloaded file to the expected location
                ParcelFileDescriptor fd = dm.openDownloadedFile(downloadManagerId);
                copyFile(fd.getFileDescriptor(), localFile);
                listener.onDownloadComplete();
            } catch (IOException e) {
                listener.onErrorDownloading(e.getLocalizedMessage());
            }
            return;
        }

        // Check if the download is still in progress
        if (downloadManagerId < 0) {
            downloadManagerId = isDownloading(context, uniqueDownloadId);
        }

        // Start a new download
        if (downloadManagerId < 0) {
            // set up download request
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(remoteAddress));
            request.setTitle(downloadTitle);
            request.setDescription(uniqueDownloadId); // we will retrieve this later from the description field
            this.downloadManagerId = dm.enqueue(request);
        }

        context.registerReceiver(receiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }

    /**
     * Copy input file to output file
     * @throws IOException
     */
    private void copyFile(FileDescriptor inputFile, File outputFile) throws IOException {
        InputStream is = new FileInputStream(inputFile);
        OutputStream os = new FileOutputStream(outputFile);
        byte[] buffer = new byte[1024];
        int count = 0;

        try {
            while ((count = is.read(buffer, 0, buffer.length)) > 0) {
                os.write(buffer, 0, count);
            }
        } finally {
            os.close();
            is.close();
        }
    }

    @Override
    public int getBytesRead() {
        if (downloadManagerId < 0) return 0;

        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(downloadManagerId);
        Cursor c = dm.query(query);

        try {
            if (c.moveToFirst()) {
                // we use the description column to store the unique id of this download
                int columnIndex = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                return c.getInt(columnIndex);
            }
        } finally {
            c.close();
        }

        return 0;
    }

    @Override
    public int getTotalBytes() {
        if (downloadManagerId < 0) return 0;

        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(downloadManagerId);
        Cursor c = dm.query(query);

        try {
            if (c.moveToFirst()) {
                // we use the description column to store the unique id for this download
                int columnIndex = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                return c.getInt(columnIndex);
            }
        } finally {
            c.close();
        }

        return 0;
    }

    @Override
    public void attemptCancel(boolean userRequested) {
        try {
            context.unregisterReceiver(receiver);
        } catch (Exception e) {
            // ignore if receiver already unregistered
        }

        if (userRequested && downloadManagerId >= 0) {
            dm.remove(downloadManagerId);
        }
    }

    /**
     * Extract the uniqueDownloadId from a given download id.
     * @return - uniqueDownloadId or null if not found
     */
    public static String getDownloadId(Context context, long downloadId) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(downloadId);
        Cursor c = dm.query(query);

        try {
            if (c.moveToFirst()) {
                // we use the description column to store the unique id for this download
                int columnIndex = c.getColumnIndex(DownloadManager.COLUMN_DESCRIPTION);
                return c.getString(columnIndex);
            }
        } finally {
            c.close();
        }

        return null;
    }

    /**
     * Extract the download title from a given download id.
     * @return - title or null if not found
     */
    public static String getDownloadTitle(Context context, long downloadId) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(downloadId);
        Cursor c = dm.query(query);

        try {
            if (c.moveToFirst()) {
                int columnIndex = c.getColumnIndex(DownloadManager.COLUMN_TITLE);
                return c.getString(columnIndex);
            }
        } finally {
            c.close();
        }

        return null;
    }

    /**
     * Get the downloadManagerId from an Intent sent by the DownloadManagerReceiver
     */
    public static long getDownloadId(Intent intent) {
        if (intent != null) {
            if (intent.hasExtra(DownloadManager.EXTRA_DOWNLOAD_ID)) {
                // we have been passed a DownloadManager download id, so get the unique id for that download
                return intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            }

            if (intent.hasExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS)) {
                // we have been passed multiple download id's - just return the first one
                long[] downloadIds = intent.getLongArrayExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS);
                if (downloadIds != null && downloadIds.length > 0) {
                    return downloadIds[0];
                }
            }
        }

        return -1;
    }

    /**
     * Check if a download is running for the specified id
     * @return -1 if not downloading, else the id from the Android download manager
     */
    public static long isDownloading(Context context, String uniqueDownloadId) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query();
        Cursor c = dm.query(query);
        int columnUniqueDownloadId = c.getColumnIndex(DownloadManager.COLUMN_DESCRIPTION);
        int columnId = c.getColumnIndex(DownloadManager.COLUMN_ID);

        try {
            while (c.moveToNext()) {
                if (uniqueDownloadId.equals(c.getString(columnUniqueDownloadId))) {
                    return c.getLong(columnId);
                }
            }
        } finally {
            c.close();
        }

        return -1;
    }

    /**
     * Check if a specific download is complete.
     * @return -1 if download is not complete, otherwise the download id
     */
    public static long isDownloadComplete(Context context, String uniqueDownloadId) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL);
        Cursor c = dm.query(query);
        int columnUniqueDownloadId = c.getColumnIndex(DownloadManager.COLUMN_DESCRIPTION);
        int columnId = c.getColumnIndex(DownloadManager.COLUMN_ID);

        try {
            while (c.moveToNext()) {
                if (uniqueDownloadId.equals(c.getString(columnUniqueDownloadId))) {
                    return c.getLong(columnId);
                }
            }
        } finally {
            c.close();
        }

        return -1;
    }

    /**
     * Broadcast receiver to listen for ACTION_DOWNLOAD_COMPLETE broadcasts
     */
    BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                long dId = getDownloadId(intent);
                String downloadId = getDownloadId(context, dId);
                if (listener != null && dId == AsyncDownloaderFromAndroid.this.downloadManagerId && downloadId != null) {
                    // our current download has just completed, so let's throw up install dialog
                    // immediately
                    try {
                        context.unregisterReceiver(receiver);
                    } catch (Exception e) {
                        // ignore if receiver already unregistered
                    }

                    // call download() to copy the file and start the installer
                    download();
                }
            }
        }
    };
}
