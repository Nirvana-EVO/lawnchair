package com.android.launcher3;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import com.android.launcher3.Launcher.OnResumeCallback;
import com.android.launcher3.compat.LauncherAppsCompat;
import com.android.launcher3.dragndrop.DragOptions;
import com.android.launcher3.userevent.nano.LauncherLogProto.Target;

import java.net.URISyntaxException;

public class UninstallDropTarget extends ButtonDropTarget {

    private static final String TAG = "UninstallDropTarget";
    private static Boolean sUninstallDisabled;

    public UninstallDropTarget(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public UninstallDropTarget(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        setupUi();
    }

    protected void setupUi() {
        // Get the hover color
        mHoverColor = getResources().getColor(R.color.uninstall_target_hover_tint);
        setDrawable(R.drawable.ic_uninstall_shadow);
    }

    @Override
    protected boolean supportsDrop(ItemInfo info) {
        return supportsDrop(getContext(), info);
    }

    public static boolean supportsDrop(Context context, ItemInfo info) {
        if (sUninstallDisabled == null) {
            UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
            Bundle restrictions = userManager.getUserRestrictions();
            sUninstallDisabled = restrictions.getBoolean(UserManager.DISALLOW_APPS_CONTROL, false)
                    || restrictions.getBoolean(UserManager.DISALLOW_UNINSTALL_APPS, false);
        }
        if (sUninstallDisabled) {
            return false;
        }

        if (info instanceof AppInfo) {
            AppInfo appInfo = (AppInfo) info;
            if (appInfo.isSystemApp != AppInfo.FLAG_SYSTEM_UNKNOWN) {
                return (appInfo.isSystemApp & AppInfo.FLAG_SYSTEM_NO) != 0;
            }
        }
        return getUninstallTarget(context, info) != null;
    }

    /**
     * @return the component name that should be uninstalled or null.
     */
    private static ComponentName getUninstallTarget(Context context, ItemInfo item) {
        Intent intent = null;
        UserHandle user = null;
        if (item != null &&
                item.itemType == LauncherSettings.BaseLauncherColumns.ITEM_TYPE_APPLICATION) {
            intent = item.getIntent();
            user = item.user;
        }
        if (intent != null) {
            LauncherActivityInfo info = LauncherAppsCompat.getInstance(context)
                    .resolveActivity(intent, user);
            if (info != null
                    && (info.getApplicationInfo().flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                return info.getComponentName();
            }
        }
        return null;
    }

    @Override
    public void onDrop(DragObject d, DragOptions options) {
        // Defer onComplete
        if (options.deferCompleteForUninstall) {
            d.dragSource = new DeferredOnComplete(d.dragSource, getContext());
        }
        super.onDrop(d, options);
    }

    @Override
    public void completeDrop(final DragObject d) {
        ComponentName target = performDropAction(d);
        if (d.dragSource instanceof DeferredOnComplete) {
            DeferredOnComplete deferred = (DeferredOnComplete) d.dragSource;
            if (target != null) {
                deferred.mPackageName = target.getPackageName();
                mLauncher.setOnResumeCallback(deferred);
            } else {
                deferred.sendFailure();
            }
        }
    }

    /**
     * Performs the drop action and returns the target component for the dragObject or null if
     * the action was not performed.
     */
    protected ComponentName performDropAction(DragObject d) {
        return performDropAction(mLauncher, d.dragInfo);
    }

    /**
     * Performs the drop action and returns the target component for the dragObject or null if
     * the action was not performed.
     */
    private static ComponentName performDropAction(Context context, ItemInfo info) {
        ComponentName cn = getUninstallTarget(context, info);
        if (cn == null) {
            // System applications cannot be installed. For now, show a toast explaining that.
            // We may give them the option of disabling apps this way.
            Toast.makeText(context, R.string.uninstall_system_app_text, Toast.LENGTH_SHORT).show();
            return null;
        }
        try {
            Intent i = Intent.parseUri(context.getString(R.string.delete_package_intent), 0)
                    .setData(Uri.fromParts("package", cn.getPackageName(), cn.getClassName()))
                    .putExtra(Intent.EXTRA_USER, info.user);
            context.startActivity(i);
            return cn;
        } catch (URISyntaxException e) {
            Log.e(TAG, "Failed to parse intent to start uninstall activity for item=" + info);
            return null;
        }
    }

    public static boolean startUninstallActivity(Launcher launcher, ItemInfo info) {
        return performDropAction(launcher, info) != null;
    }

    /**
     * A wrapper around {@link DragSource} which delays the {@link #onDropCompleted} action until
     * {@link #onLauncherResume}
     */
    private class DeferredOnComplete implements DragSource, OnResumeCallback {

        private final DragSource mOriginal;
        private final Context mContext;

        private String mPackageName;
        private DragObject mDragObject;

        public DeferredOnComplete(DragSource original, Context context) {
            mOriginal = original;
            mContext = context;
        }

        @Override
        public void onDropCompleted(View target, DragObject d, boolean isFlingToDelete,
                boolean success) {
            mDragObject = d;
        }

        @Override
        public void fillInLogContainerData(View v, ItemInfo info, Target target,
                Target targetParent) {
            mOriginal.fillInLogContainerData(v, info, target, targetParent);
        }

        @Override
        public void onLauncherResume() {
            // We use MATCH_UNINSTALLED_PACKAGES as the app can be on SD card as well.
            if (LauncherAppsCompat.getInstance(mContext)
                    .getApplicationInfo(mPackageName, PackageManager.MATCH_UNINSTALLED_PACKAGES,
                            mDragObject.dragInfo.user) == null) {
                mDragObject.dragSource = mOriginal;
                mOriginal.onDropCompleted(UninstallDropTarget.this, mDragObject, false, true);
            } else {
                sendFailure();
            }
        }

        public void sendFailure() {
            mDragObject.dragSource = mOriginal;
            mDragObject.cancelled = true;
            mOriginal.onDropCompleted(UninstallDropTarget.this, mDragObject, false, false);
        }
    }
}
