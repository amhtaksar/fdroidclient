package org.fdroid.fdroid;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.support.graphics.drawable.VectorDrawableCompat;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.View;

import com.nostra13.universalimageloader.core.DisplayImageOptions;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.assist.FailReason;
import com.nostra13.universalimageloader.core.assist.ImageScaleType;
import com.nostra13.universalimageloader.core.assist.ImageSize;
import com.nostra13.universalimageloader.core.listener.ImageLoadingListener;
import com.nostra13.universalimageloader.utils.DiskCacheUtils;

import org.fdroid.fdroid.data.App;
import org.fdroid.fdroid.views.main.MainActivity;

import java.util.ArrayList;

class NotificationHelper {

    private static final String BROADCAST_NOTIFICATIONS_ALL_UPDATES_CLEARED = "org.fdroid.fdroid.installer.notifications.allupdates.cleared";
    private static final String BROADCAST_NOTIFICATIONS_ALL_INSTALLED_CLEARED = "org.fdroid.fdroid.installer.notifications.allinstalled.cleared";
    private static final String BROADCAST_NOTIFICATIONS_UPDATE_CLEARED = "org.fdroid.fdroid.installer.notifications.update.cleared";
    private static final String BROADCAST_NOTIFICATIONS_INSTALLED_CLEARED = "org.fdroid.fdroid.installer.notifications.installed.cleared";

    private static final int NOTIFY_ID_UPDATES = 1;
    private static final int NOTIFY_ID_INSTALLED = 2;

    private static final int MAX_UPDATES_TO_SHOW = 5;
    private static final int MAX_INSTALLED_TO_SHOW = 10;

    private static final String EXTRA_NOTIFICATION_KEY = "key";
    private static final String GROUP_UPDATES = "updates";
    private static final String GROUP_INSTALLED = "installed";

    private final Context context;
    private final NotificationManagerCompat notificationManager;
    private final AppUpdateStatusManager appUpdateStatusManager;
    private final DisplayImageOptions displayImageOptions;
    private final ArrayList<AppUpdateStatusManager.AppUpdateStatus> updates = new ArrayList<>();
    private final ArrayList<AppUpdateStatusManager.AppUpdateStatus> installed = new ArrayList<>();

    NotificationHelper(Context context) {
        this.context = context;
        appUpdateStatusManager = AppUpdateStatusManager.getInstance(context);
        notificationManager = NotificationManagerCompat.from(context);
        displayImageOptions = new DisplayImageOptions.Builder()
                .cacheInMemory(true)
                .cacheOnDisk(true)
                .imageScaleType(ImageScaleType.NONE)
                .bitmapConfig(Bitmap.Config.RGB_565)
                .build();

        // We need to listen to when notifications are cleared, so that we "forget" all that we currently know about updates
        // and installs.
        IntentFilter filter = new IntentFilter();
        filter.addAction(BROADCAST_NOTIFICATIONS_ALL_UPDATES_CLEARED);
        filter.addAction(BROADCAST_NOTIFICATIONS_ALL_INSTALLED_CLEARED);
        filter.addAction(BROADCAST_NOTIFICATIONS_UPDATE_CLEARED);
        filter.addAction(BROADCAST_NOTIFICATIONS_INSTALLED_CLEARED);
        BroadcastReceiver receiverNotificationsCleared = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                switch (intent.getAction()) {
                    case BROADCAST_NOTIFICATIONS_ALL_UPDATES_CLEARED:
                        appUpdateStatusManager.clearAllUpdates();
                        break;
                    case BROADCAST_NOTIFICATIONS_ALL_INSTALLED_CLEARED:
                        appUpdateStatusManager.clearAllInstalled();
                        break;
                    case BROADCAST_NOTIFICATIONS_UPDATE_CLEARED:
                        // If clearing apps in state "InstallError" (like when auto-cancelling) we
                        // remove them from the status manager entirely.
                        AppUpdateStatusManager.AppUpdateStatus appUpdateStatus = appUpdateStatusManager.get(intent.getStringExtra(EXTRA_NOTIFICATION_KEY));
                        if (appUpdateStatus != null && appUpdateStatus.status == AppUpdateStatusManager.Status.InstallError) {
                            appUpdateStatusManager.removeApk(intent.getStringExtra(EXTRA_NOTIFICATION_KEY));
                        }
                        break;
                    case BROADCAST_NOTIFICATIONS_INSTALLED_CLEARED:
                        appUpdateStatusManager.removeApk(intent.getStringExtra(EXTRA_NOTIFICATION_KEY));
                        break;
                }
            }
        };
        context.registerReceiver(receiverNotificationsCleared, filter);
        filter = new IntentFilter();
        filter.addAction(AppUpdateStatusManager.BROADCAST_APPSTATUS_LIST_CHANGED);
        filter.addAction(AppUpdateStatusManager.BROADCAST_APPSTATUS_ADDED);
        filter.addAction(AppUpdateStatusManager.BROADCAST_APPSTATUS_CHANGED);
        filter.addAction(AppUpdateStatusManager.BROADCAST_APPSTATUS_REMOVED);
        BroadcastReceiver receiverAppStatusChanges = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                AppUpdateStatusManager.AppUpdateStatus entry;
                String url;

                switch (intent.getAction()) {
                    case AppUpdateStatusManager.BROADCAST_APPSTATUS_LIST_CHANGED:
                        notificationManager.cancelAll();
                        updateStatusLists();
                        createSummaryNotifications();
                        for (AppUpdateStatusManager.AppUpdateStatus appUpdateStatus : appUpdateStatusManager.getAll()) {
                            createNotification(appUpdateStatus);
                        }
                        break;
                    case AppUpdateStatusManager.BROADCAST_APPSTATUS_ADDED:
                        updateStatusLists();
                        createSummaryNotifications();
                        url = intent.getStringExtra(AppUpdateStatusManager.EXTRA_APK_URL);
                        entry = appUpdateStatusManager.get(url);
                        if (entry != null) {
                            createNotification(entry);
                        }
                        break;
                    case AppUpdateStatusManager.BROADCAST_APPSTATUS_CHANGED:
                        url = intent.getStringExtra(AppUpdateStatusManager.EXTRA_APK_URL);
                        entry = appUpdateStatusManager.get(url);
                        updateStatusLists();
                        if (entry != null) {
                            createNotification(entry);
                        }
                        if (intent.getBooleanExtra(AppUpdateStatusManager.EXTRA_IS_STATUS_UPDATE, false)) {
                            createSummaryNotifications();
                        }
                        break;
                    case AppUpdateStatusManager.BROADCAST_APPSTATUS_REMOVED:
                        url = intent.getStringExtra(AppUpdateStatusManager.EXTRA_APK_URL);
                        notificationManager.cancel(url, NOTIFY_ID_INSTALLED);
                        notificationManager.cancel(url, NOTIFY_ID_UPDATES);
                        updateStatusLists();
                        createSummaryNotifications();
                        break;
                }
            }
        };
        LocalBroadcastManager.getInstance(context).registerReceiver(receiverAppStatusChanges, filter);
    }

    private boolean useStackedNotifications() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
    }

    /**
     * Populate {@link NotificationHelper#updates} and {@link NotificationHelper#installed} with
     * the relevant status entries from the {@link AppUpdateStatusManager}.
     */
    private void updateStatusLists() {
        if (!notificationManager.areNotificationsEnabled()) {
            return;
        }

        updates.clear();
        installed.clear();

        for (AppUpdateStatusManager.AppUpdateStatus entry : appUpdateStatusManager.getAll()) {
            if (entry.status == AppUpdateStatusManager.Status.Installed) {
                installed.add(entry);
            } else if (!shouldIgnoreEntry(entry)) {
                updates.add(entry);
            }
        }
    }

    private boolean shouldIgnoreEntry(AppUpdateStatusManager.AppUpdateStatus entry) {
        // Ignore unknown status
        if (entry.status == AppUpdateStatusManager.Status.Unknown) {
            return true;
        } else if ((entry.status == AppUpdateStatusManager.Status.Downloading || entry.status == AppUpdateStatusManager.Status.ReadyToInstall || entry.status == AppUpdateStatusManager.Status.InstallError) &&
                (AppDetails.isAppVisible(entry.app.packageName) || AppDetails2.isAppVisible(entry.app.packageName))) {
            // Ignore downloading, readyToInstall and installError if we are showing the details screen for this app
            return true;
        }
        return false;
    }

    private void createNotification(AppUpdateStatusManager.AppUpdateStatus entry) {
        if (shouldIgnoreEntry(entry)) {
            notificationManager.cancel(entry.getUniqueKey(), NOTIFY_ID_UPDATES);
            notificationManager.cancel(entry.getUniqueKey(), NOTIFY_ID_INSTALLED);
            return;
        }

        if (!notificationManager.areNotificationsEnabled()) {
            return;
        }

        Notification notification;
        if (entry.status == AppUpdateStatusManager.Status.Installed) {
            if (useStackedNotifications()) {
                notification = createInstalledNotification(entry);
                notificationManager.cancel(entry.getUniqueKey(), NOTIFY_ID_UPDATES);
                notificationManager.notify(entry.getUniqueKey(), NOTIFY_ID_INSTALLED, notification);
            } else if (installed.size() == 1) {
                notification = createInstalledNotification(entry);
                notificationManager.cancel(entry.getUniqueKey(), NOTIFY_ID_UPDATES);
                notificationManager.cancel(entry.getUniqueKey(), NOTIFY_ID_INSTALLED);
                notificationManager.notify(GROUP_INSTALLED, NOTIFY_ID_INSTALLED, notification);
            }
        } else {
            if (useStackedNotifications()) {
                notification = createUpdateNotification(entry);
                notificationManager.cancel(entry.getUniqueKey(), NOTIFY_ID_INSTALLED);
                notificationManager.notify(entry.getUniqueKey(), NOTIFY_ID_UPDATES, notification);
            } else if (updates.size() == 1) {
                notification = createUpdateNotification(entry);
                notificationManager.cancel(entry.getUniqueKey(), NOTIFY_ID_UPDATES);
                notificationManager.cancel(entry.getUniqueKey(), NOTIFY_ID_INSTALLED);
                notificationManager.notify(GROUP_UPDATES, NOTIFY_ID_UPDATES, notification);
            }
        }
    }

    private void createSummaryNotifications() {
        if (!notificationManager.areNotificationsEnabled()) {
            return;
        }

        Notification notification;
        if (updates.size() != 1 || useStackedNotifications()) {
            if (updates.size() == 0) {
                // No updates, remove summary
                notificationManager.cancel(GROUP_UPDATES, NOTIFY_ID_UPDATES);
            } else {
                notification = createUpdateSummaryNotification(updates);
                notificationManager.notify(GROUP_UPDATES, NOTIFY_ID_UPDATES, notification);
            }
        }
        if (installed.size() != 1 || useStackedNotifications()) {
            if (installed.size() == 0) {
                // No installed, remove summary
                notificationManager.cancel(GROUP_INSTALLED, NOTIFY_ID_INSTALLED);
            } else {
                notification = createInstalledSummaryNotification(installed);
                notificationManager.notify(GROUP_INSTALLED, NOTIFY_ID_INSTALLED, notification);
            }
        }
    }

    private NotificationCompat.Action getAction(AppUpdateStatusManager.AppUpdateStatus entry) {
        if (entry.intent != null) {
            switch (entry.status) {
                case UpdateAvailable:
                    return new NotificationCompat.Action(R.drawable.ic_file_download, context.getString(R.string.notification_action_update), entry.intent);

                case Downloading:
                case Installing:
                    return new NotificationCompat.Action(R.drawable.ic_cancel, context.getString(R.string.notification_action_cancel), entry.intent);

                case ReadyToInstall:
                    return new NotificationCompat.Action(R.drawable.ic_file_install, context.getString(R.string.notification_action_install), entry.intent);
            }
        }
        return null;
    }

    private String getSingleItemTitleString(App app, AppUpdateStatusManager.Status status) {
        switch (status) {
            case UpdateAvailable:
                return context.getString(R.string.notification_title_single_update_available);
            case Downloading:
                return app.name;
            case ReadyToInstall:
                return context.getString(app.isInstalled() ? R.string.notification_title_single_ready_to_install_update : R.string.notification_title_single_ready_to_install);
            case Installing:
                return app.name;
            case Installed:
                return app.name;
            case InstallError:
                return context.getString(R.string.notification_title_single_install_error);
        }
        return "";
    }

    private String getSingleItemContentString(App app, AppUpdateStatusManager.Status status) {
        switch (status) {
            case UpdateAvailable:
                return app.name;
            case Downloading:
                return context.getString(app.isInstalled() ? R.string.notification_content_single_downloading_update : R.string.notification_content_single_downloading, app.name);
            case ReadyToInstall:
                return app.name;
            case Installing:
                return context.getString(R.string.notification_content_single_installing, app.name);
            case Installed:
                return context.getString(R.string.notification_content_single_installed);
            case InstallError:
                return app.name;
        }
        return "";
    }

    private String getMultiItemContentString(App app, AppUpdateStatusManager.Status status) {
        switch (status) {
            case UpdateAvailable:
                return context.getString(R.string.notification_title_summary_update_available);
            case Downloading:
                return context.getString(app.isInstalled() ? R.string.notification_title_summary_downloading_update : R.string.notification_title_summary_downloading);
            case ReadyToInstall:
                return context.getString(app.isInstalled() ? R.string.notification_title_summary_ready_to_install_update : R.string.notification_title_summary_ready_to_install);
            case Installing:
                return context.getString(R.string.notification_title_summary_installing);
            case Installed:
                return context.getString(R.string.notification_title_summary_installed);
            case InstallError:
                return context.getString(R.string.notification_title_summary_install_error);
        }
        return "";
    }

    private Notification createUpdateNotification(AppUpdateStatusManager.AppUpdateStatus entry) {
        App app = entry.app;
        AppUpdateStatusManager.Status status = entry.status;

        int iconSmall = R.drawable.ic_launcher;
        Bitmap iconLarge = getLargeIconForEntry(entry);
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context)
                        .setAutoCancel(true)
                        .setContentTitle(getSingleItemTitleString(app, status))
                        .setContentText(getSingleItemContentString(app, status))
                        .setSmallIcon(iconSmall)
                        .setColor(ContextCompat.getColor(context, R.color.fdroid_blue))
                        .setLargeIcon(iconLarge)
                        .setLocalOnly(true)
                        .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                        .setContentIntent(entry.intent);

        /* If using stacked notifications, use groups. Note that this would not work prior to Lollipop,
           because of http://stackoverflow.com/a/34953411, but currently not an issue since stacked
           notifications are used only on >= Nougat.
        */
        if (useStackedNotifications()) {
            builder.setGroup(GROUP_UPDATES);
        }

        // Handle actions
        //
        NotificationCompat.Action action = getAction(entry);
        if (action != null) {
            builder.addAction(action);
        }

        // Handle progress bar (for some states)
        //
        if (status == AppUpdateStatusManager.Status.Downloading) {
            if (entry.progressMax == 0) {
                builder.setProgress(100, 0, true);
            } else {
                builder.setProgress(entry.progressMax, entry.progressCurrent, false);
            }
        } else if (status == AppUpdateStatusManager.Status.Installing) {
            builder.setProgress(100, 0, true); // indeterminate bar
        }

        Intent intentDeleted = new Intent(BROADCAST_NOTIFICATIONS_UPDATE_CLEARED);
        intentDeleted.putExtra(EXTRA_NOTIFICATION_KEY, entry.getUniqueKey());
        PendingIntent piDeleted = PendingIntent.getBroadcast(context, 0, intentDeleted, 0);
        builder.setDeleteIntent(piDeleted);
        return builder.build();
    }

    private Notification createUpdateSummaryNotification(ArrayList<AppUpdateStatusManager.AppUpdateStatus> updates) {
        String title = context.getString(R.string.notification_summary_updates, updates.size());
        StringBuilder text = new StringBuilder();

        NotificationCompat.InboxStyle inboxStyle = new NotificationCompat.InboxStyle();
        inboxStyle.setBigContentTitle(title);

        for (int i = 0; i < MAX_UPDATES_TO_SHOW && i < updates.size(); i++) {
            AppUpdateStatusManager.AppUpdateStatus entry = updates.get(i);
            App app = entry.app;
            AppUpdateStatusManager.Status status = entry.status;

            String content = getMultiItemContentString(app, status);
            SpannableStringBuilder sb = new SpannableStringBuilder(app.name);
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            sb.append(" ");
            sb.append(content);
            inboxStyle.addLine(sb);

            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(app.name);
        }

        if (updates.size() > MAX_UPDATES_TO_SHOW) {
            int diff = updates.size() - MAX_UPDATES_TO_SHOW;
            inboxStyle.setSummaryText(context.getString(R.string.notification_summary_more, diff));
        }

        // Intent to open main app list
        Intent intentObject = new Intent(context, MainActivity.class);
        intentObject.putExtra(MainActivity.EXTRA_VIEW_UPDATES, true);
        PendingIntent piAction = PendingIntent.getActivity(context, 0, intentObject, PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context)
                        .setAutoCancel(!useStackedNotifications())
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setColor(ContextCompat.getColor(context, R.color.fdroid_blue))
                        .setContentTitle(title)
                        .setContentText(text)
                        .setContentIntent(piAction)
                        .setLocalOnly(true)
                        .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                        .setStyle(inboxStyle);

        if (useStackedNotifications()) {
            builder.setGroup(GROUP_UPDATES)
                    .setGroupSummary(true);
        }

        Intent intentDeleted = new Intent(BROADCAST_NOTIFICATIONS_ALL_UPDATES_CLEARED);
        PendingIntent piDeleted = PendingIntent.getBroadcast(context, 0, intentDeleted, 0);
        builder.setDeleteIntent(piDeleted);
        return builder.build();
    }

    private Notification createInstalledNotification(AppUpdateStatusManager.AppUpdateStatus entry) {
        App app = entry.app;

        Bitmap iconLarge = getLargeIconForEntry(entry);
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context)
                        .setAutoCancel(true)
                        .setLargeIcon(iconLarge)
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setColor(ContextCompat.getColor(context, R.color.fdroid_blue))
                        .setContentTitle(app.name)
                        .setContentText(context.getString(R.string.notification_content_single_installed))
                        .setLocalOnly(true)
                        .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                        .setContentIntent(entry.intent);

        if (useStackedNotifications()) {
            builder.setGroup(GROUP_INSTALLED);
        }

        Intent intentDeleted = new Intent(BROADCAST_NOTIFICATIONS_INSTALLED_CLEARED);
        intentDeleted.putExtra(EXTRA_NOTIFICATION_KEY, entry.getUniqueKey());
        PendingIntent piDeleted = PendingIntent.getBroadcast(context, 0, intentDeleted, 0);
        builder.setDeleteIntent(piDeleted);
        return builder.build();
    }

    private Notification createInstalledSummaryNotification(ArrayList<AppUpdateStatusManager.AppUpdateStatus> installed) {
        String title = context.getString(R.string.notification_summary_installed, installed.size());
        StringBuilder text = new StringBuilder();

        NotificationCompat.BigTextStyle bigTextStyle = new NotificationCompat.BigTextStyle();
        bigTextStyle.setBigContentTitle(title);

        for (int i = 0; i < MAX_INSTALLED_TO_SHOW && i < installed.size(); i++) {
            AppUpdateStatusManager.AppUpdateStatus entry = installed.get(i);
            App app = entry.app;
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(app.name);
        }
        bigTextStyle.bigText(text);
        if (installed.size() > MAX_INSTALLED_TO_SHOW) {
            int diff = installed.size() - MAX_INSTALLED_TO_SHOW;
            bigTextStyle.setSummaryText(context.getString(R.string.notification_summary_more, diff));
        }

        // Intent to open main app list
        Intent intentObject = new Intent(context, MainActivity.class);
        PendingIntent piAction = PendingIntent.getActivity(context, 0, intentObject, 0);

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context)
                        .setAutoCancel(!useStackedNotifications())
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setColor(ContextCompat.getColor(context, R.color.fdroid_blue))
                        .setContentTitle(title)
                        .setContentText(text)
                        .setContentIntent(piAction)
                        .setLocalOnly(true)
                        .setVisibility(NotificationCompat.VISIBILITY_SECRET);
        if (useStackedNotifications()) {
            builder.setGroup(GROUP_INSTALLED)
                    .setGroupSummary(true);
        }
        Intent intentDeleted = new Intent(BROADCAST_NOTIFICATIONS_ALL_INSTALLED_CLEARED);
        PendingIntent piDeleted = PendingIntent.getBroadcast(context, 0, intentDeleted, 0);
        builder.setDeleteIntent(piDeleted);
        return builder.build();
    }

    private Point getLargeIconSize() {
        int w;
        int h;
        if (Build.VERSION.SDK_INT >= 11) {
            w = context.getResources().getDimensionPixelSize(android.R.dimen.notification_large_icon_width);
            h = context.getResources().getDimensionPixelSize(android.R.dimen.notification_large_icon_height);
        } else {
            w = context.getResources().getDimensionPixelSize(android.R.dimen.app_icon_size);
            h = w;
        }
        return new Point(w, h);
    }

    private Bitmap getLargeIconForEntry(AppUpdateStatusManager.AppUpdateStatus entry) {
        final Point largeIconSize = getLargeIconSize();
        Bitmap iconLarge = null;
        if (entry.status == AppUpdateStatusManager.Status.Downloading || entry.status == AppUpdateStatusManager.Status.Installing) {
            Bitmap bitmap = Bitmap.createBitmap(largeIconSize.x, largeIconSize.y, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Drawable downloadIcon = VectorDrawableCompat.create(context.getResources(), R.drawable.ic_notification_download, context.getTheme());
            if (downloadIcon != null) {
                downloadIcon.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                downloadIcon.draw(canvas);
            }
            return bitmap;
        } else if (DiskCacheUtils.findInCache(entry.app.iconUrl, ImageLoader.getInstance().getDiskCache()) != null) {
            iconLarge = ImageLoader.getInstance().loadImageSync(entry.app.iconUrl, new ImageSize(largeIconSize.x, largeIconSize.y), displayImageOptions);
        } else {
            // Load it for later!
            ImageLoader.getInstance().loadImage(entry.app.iconUrl, new ImageSize(largeIconSize.x, largeIconSize.y), displayImageOptions, new ImageLoadingListener() {

                AppUpdateStatusManager.AppUpdateStatus entry;

                ImageLoadingListener init(AppUpdateStatusManager.AppUpdateStatus entry) {
                    this.entry = entry;
                    return this;
                }

                @Override
                public void onLoadingStarted(String imageUri, View view) {

                }

                @Override
                public void onLoadingFailed(String imageUri, View view, FailReason failReason) {

                }

                @Override
                public void onLoadingComplete(String imageUri, View view, Bitmap loadedImage) {
                    // Need to check that the notification is still valid, and also that the image
                    // is indeed cached now, so we won't get stuck in an endless loop.
                    AppUpdateStatusManager.AppUpdateStatus oldEntry = appUpdateStatusManager.get(entry.getUniqueKey());
                    if (oldEntry != null && DiskCacheUtils.findInCache(oldEntry.app.iconUrl, ImageLoader.getInstance().getDiskCache()) != null) {
                        createNotification(oldEntry); // Update with new image!
                    }
                }

                @Override
                public void onLoadingCancelled(String imageUri, View view) {

                }
            }.init(entry));
        }
        return iconLarge;
    }
}
