/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.clipboard;

import android.app.ActivityManagerNative;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.KeyguardManager;
import android.os.PowerManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ContentProvider;
import android.content.IClipboard;
import android.content.IOnPrimaryClipChangedListener;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.IUserManager;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;
import android.util.SparseArray;

import java.util.HashSet;
import java.util.List;

/**
 * Implementation of the clipboard for copy and paste.
 */
public class ClipboardService extends IClipboard.Stub {

    private static final String TAG = "ClipboardService";

    private final Context mContext;
    private final IActivityManager mAm;
    private final IUserManager mUm;
    private final PackageManager mPm;
    private final AppOpsManager mAppOps;
    private final IBinder mPermissionOwner;

    private class ListenerInfo {
        final int mUid;
        final String mPackageName;
        ListenerInfo(int uid, String packageName) {
            mUid = uid;
            mPackageName = packageName;
        }
    }

    private class PerUserClipboard {
        final int userId;

        final RemoteCallbackList<IOnPrimaryClipChangedListener> primaryClipListeners
                = new RemoteCallbackList<IOnPrimaryClipChangedListener>();

        ClipData primaryClip;

        final HashSet<String> activePermissionOwners
                = new HashSet<String>();

        PerUserClipboard(int userId) {
            this.userId = userId;
        }
    }

    private SparseArray<PerUserClipboard> mClipboards = new SparseArray<PerUserClipboard>();

    /**
     * Instantiates the clipboard.
     */
    public ClipboardService(Context context) {
        mContext = context;
        mAm = ActivityManagerNative.getDefault();
        mPm = context.getPackageManager();
        mUm = (IUserManager) ServiceManager.getService(Context.USER_SERVICE);
        mAppOps = (AppOpsManager)context.getSystemService(Context.APP_OPS_SERVICE);
        IBinder permOwner = null;
        try {
            permOwner = mAm.newUriPermissionOwner("clipboard");
        } catch (RemoteException e) {
            Slog.w("clipboard", "AM dead", e);
        }
        mPermissionOwner = permOwner;

        // Remove the clipboard if a user is removed
        IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_USER_REMOVED.equals(action)) {
                    removeClipboard(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                }
            }
        }, userFilter);
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            if (!(e instanceof SecurityException)) {
                Slog.wtf("clipboard", "Exception: ", e);
            }
            throw e;
        }
        
    }

    private PerUserClipboard getClipboard() {
        return getClipboard(UserHandle.getCallingUserId());
    }

    private PerUserClipboard getClipboard(int userId) {
        synchronized (mClipboards) {
            PerUserClipboard puc = mClipboards.get(userId);
            if (puc == null) {
                puc = new PerUserClipboard(userId);
                mClipboards.put(userId, puc);
            }
            return puc;
        }
    }

    private void removeClipboard(int userId) {
        synchronized (mClipboards) {
            mClipboards.remove(userId);
        }
    }

    public void setPrimaryClip(ClipData clip, String callingPackage) {
        synchronized (this) {
            if (clip != null && clip.getItemCount() <= 0) {
                throw new IllegalArgumentException("No items");
            }
            final int callingUid = Binder.getCallingUid();
            if (mAppOps.noteOp(AppOpsManager.OP_WRITE_CLIPBOARD, callingUid,
                    callingPackage) != AppOpsManager.MODE_ALLOWED) {
                return;
            }
            checkDataOwnerLocked(clip, callingUid);
            final int userId = UserHandle.getUserId(callingUid);
            PerUserClipboard clipboard = getClipboard(userId);
            revokeUris(clipboard);
            setPrimaryClipInternal(clipboard, clip);
            List<UserInfo> related = getRelatedProfiles(userId);
            if (related != null) {
                int size = related.size();
                if (size > 1) { // Related profiles list include the current profile.
                    boolean canCopy = false;
                    try {
                        canCopy = !mUm.getUserRestrictions(userId).getBoolean(
                                UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Remote Exception calling UserManager: " + e);
                    }
                    // Copy clip data to related users if allowed. If disallowed, then remove
                    // primary clip in related users to prevent pasting stale content.
                    if (!canCopy) {
                        clip = null;
                    } else {
                        clip.fixUrisLight(userId);
                    }
                    for (int i = 0; i < size; i++) {
                        int id = related.get(i).id;
                        if (id != userId) {
                            setPrimaryClipInternal(getClipboard(id), clip);
                        }
                    }
                }
            }
        }
    }

    List<UserInfo> getRelatedProfiles(int userId) {
        final List<UserInfo> related;
        final long origId = Binder.clearCallingIdentity();
        try {
            related = mUm.getProfiles(userId, true);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote Exception calling UserManager: " + e);
            return null;
        } finally{
            Binder.restoreCallingIdentity(origId);
        }
        return related;
    }

    void setPrimaryClipInternal(PerUserClipboard clipboard, ClipData clip) {
        clipboard.activePermissionOwners.clear();
        if (clip == null && clipboard.primaryClip == null) {
            return;
        }
        clipboard.primaryClip = clip;
        final long ident = Binder.clearCallingIdentity();
        final int n = clipboard.primaryClipListeners.beginBroadcast();
        try {
            for (int i = 0; i < n; i++) {
                try {
                    ListenerInfo li = (ListenerInfo)
                            clipboard.primaryClipListeners.getBroadcastCookie(i);
                    if (mAppOps.checkOpNoThrow(AppOpsManager.OP_READ_CLIPBOARD, li.mUid,
                            li.mPackageName) == AppOpsManager.MODE_ALLOWED) {
                        clipboard.primaryClipListeners.getBroadcastItem(i)
                                .dispatchPrimaryClipChanged();
                    }
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing
                    // the dead object for us.
                }
            }
        } finally {
            clipboard.primaryClipListeners.finishBroadcast();
            Binder.restoreCallingIdentity(ident);
        }
    }
    
    public ClipData getPrimaryClip(String pkg) {
        synchronized (this) {
            if ((mAppOps.noteOp(AppOpsManager.OP_READ_CLIPBOARD, Binder.getCallingUid(),
                    pkg) != AppOpsManager.MODE_ALLOWED) || isDeviceLocked()) {
                return null;
            }
            addActiveOwnerLocked(Binder.getCallingUid(), pkg);
            return getClipboard().primaryClip;
        }
    }

    public ClipDescription getPrimaryClipDescription(String callingPackage) {
        synchronized (this) {
            if ((mAppOps.checkOp(AppOpsManager.OP_READ_CLIPBOARD, Binder.getCallingUid(),
                    callingPackage) != AppOpsManager.MODE_ALLOWED) || isDeviceLocked()) {
                return null;
            }
            PerUserClipboard clipboard = getClipboard();
            return clipboard.primaryClip != null ? clipboard.primaryClip.getDescription() : null;
        }
    }

    public boolean hasPrimaryClip(String callingPackage) {
        synchronized (this) {
            if ((mAppOps.checkOp(AppOpsManager.OP_READ_CLIPBOARD, Binder.getCallingUid(),
                    callingPackage) != AppOpsManager.MODE_ALLOWED) || isDeviceLocked()) {
                return false;
            }
            return getClipboard().primaryClip != null;
        }
    }

    public void addPrimaryClipChangedListener(IOnPrimaryClipChangedListener listener,
            String callingPackage) {
        synchronized (this) {
            getClipboard().primaryClipListeners.register(listener,
                    new ListenerInfo(Binder.getCallingUid(), callingPackage));
        }
    }

    public void removePrimaryClipChangedListener(IOnPrimaryClipChangedListener listener) {
        synchronized (this) {
            getClipboard().primaryClipListeners.unregister(listener);
        }
    }

    public boolean hasClipboardText(String callingPackage) {
        synchronized (this) {
            if ((mAppOps.checkOp(AppOpsManager.OP_READ_CLIPBOARD, Binder.getCallingUid(),
                    callingPackage) != AppOpsManager.MODE_ALLOWED) || isDeviceLocked()) {
                return false;
            }
            PerUserClipboard clipboard = getClipboard();
            if (clipboard.primaryClip != null) {
                CharSequence text = clipboard.primaryClip.getItemAt(0).getText();
                return text != null && text.length() > 0;
            }
            return false;
        }
    }

    private boolean isDeviceLocked() {
        boolean isLocked = false;
        KeyguardManager keyguardManager = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        boolean inKeyguardRestrictedInputMode = keyguardManager.inKeyguardRestrictedInputMode();
        if (inKeyguardRestrictedInputMode) {
            isLocked = true;
        } else {
            PowerManager powerManager = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
            isLocked = !powerManager.isScreenOn();
        }
        return isLocked;
    }

    private final void checkUriOwnerLocked(Uri uri, int uid) {
        if (!"content".equals(uri.getScheme())) {
            return;
        }
        long ident = Binder.clearCallingIdentity();
        try {
            // This will throw SecurityException for us.
            mAm.checkGrantUriPermission(uid, null, ContentProvider.getUriWithoutUserId(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(uid)));
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private final void checkItemOwnerLocked(ClipData.Item item, int uid) {
        if (item.getUri() != null) {
            checkUriOwnerLocked(item.getUri(), uid);
        }
        Intent intent = item.getIntent();
        if (intent != null && intent.getData() != null) {
            checkUriOwnerLocked(intent.getData(), uid);
        }
    }

    private final void checkDataOwnerLocked(ClipData data, int uid) {
        final int N = data.getItemCount();
        for (int i=0; i<N; i++) {
            checkItemOwnerLocked(data.getItemAt(i), uid);
        }
    }

    private final void grantUriLocked(Uri uri, String pkg, int userId) {
        long ident = Binder.clearCallingIdentity();
        try {
            int sourceUserId = ContentProvider.getUserIdFromUri(uri, userId);
            uri = ContentProvider.getUriWithoutUserId(uri);
            mAm.grantUriPermissionFromOwner(mPermissionOwner, Process.myUid(), pkg,
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION, sourceUserId, userId);
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private final void grantItemLocked(ClipData.Item item, String pkg, int userId) {
        if (item.getUri() != null) {
            grantUriLocked(item.getUri(), pkg, userId);
        }
        Intent intent = item.getIntent();
        if (intent != null && intent.getData() != null) {
            grantUriLocked(intent.getData(), pkg, userId);
        }
    }

    private final void addActiveOwnerLocked(int uid, String pkg) {
        final IPackageManager pm = AppGlobals.getPackageManager();
        final int targetUserHandle = UserHandle.getCallingUserId();
        final long oldIdentity = Binder.clearCallingIdentity();
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, 0, targetUserHandle);
            if (pi == null) {
                throw new IllegalArgumentException("Unknown package " + pkg);
            }
            if (!UserHandle.isSameApp(pi.applicationInfo.uid, uid)) {
                throw new SecurityException("Calling uid " + uid
                        + " does not own package " + pkg);
            }
        } catch (RemoteException e) {
            // Can't happen; the package manager is in the same process
        } finally {
            Binder.restoreCallingIdentity(oldIdentity);
        }
        PerUserClipboard clipboard = getClipboard();
        if (clipboard.primaryClip != null && !clipboard.activePermissionOwners.contains(pkg)) {
            final int N = clipboard.primaryClip.getItemCount();
            for (int i=0; i<N; i++) {
                grantItemLocked(clipboard.primaryClip.getItemAt(i), pkg, UserHandle.getUserId(uid));
            }
            clipboard.activePermissionOwners.add(pkg);
        }
    }

    private final void revokeUriLocked(Uri uri) {
        int userId = ContentProvider.getUserIdFromUri(uri,
                UserHandle.getUserId(Binder.getCallingUid()));
        long ident = Binder.clearCallingIdentity();
        try {
            uri = ContentProvider.getUriWithoutUserId(uri);
            mAm.revokeUriPermissionFromOwner(mPermissionOwner, uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    userId);
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private final void revokeItemLocked(ClipData.Item item) {
        if (item.getUri() != null) {
            revokeUriLocked(item.getUri());
        }
        Intent intent = item.getIntent();
        if (intent != null && intent.getData() != null) {
            revokeUriLocked(intent.getData());
        }
    }

    private final void revokeUris(PerUserClipboard clipboard) {
        if (clipboard.primaryClip == null) {
            return;
        }
        final int N = clipboard.primaryClip.getItemCount();
        for (int i=0; i<N; i++) {
            revokeItemLocked(clipboard.primaryClip.getItemAt(i));
        }
    }
}
