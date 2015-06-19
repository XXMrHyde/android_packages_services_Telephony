/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.phone;

import java.util.ArrayList;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.preference.PreferenceManager;
import android.provider.ContactsContract.PhoneLookup;
import android.provider.Settings;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.SpannableStringBuilder;
import android.text.format.DateUtils;
import android.text.style.RelativeSizeSpan;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.widget.Toast;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.TelephonyCapabilities;
import com.android.internal.telephony.util.BlacklistUtils;
import com.android.phone.settings.VoicemailNotificationSettingsUtil;
import com.android.phone.settings.VoicemailProviderSettingsUtil;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NotificationManager-related utility code for the Phone app.
 *
 * This is a singleton object which acts as the interface to the
 * framework's NotificationManager, and is used to display status bar
 * icons and control other status bar-related behavior.
 *
 * @see PhoneGlobals.notificationMgr
 */
public class NotificationMgr {
    private static final String LOG_TAG = NotificationMgr.class.getSimpleName();
    private static final boolean DBG =
            (PhoneGlobals.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    // Do not check in with VDBG = true, since that may write PII to the system log.
    private static final boolean VDBG = false;

    // notification types
    static final int MMI_NOTIFICATION = 1;
    static final int NETWORK_SELECTION_NOTIFICATION = 2;
    static final int VOICEMAIL_NOTIFICATION = 3;
    static final int CALL_FORWARD_NOTIFICATION = 4;
    static final int DATA_DISCONNECTED_ROAMING_NOTIFICATION = 5;
    static final int SELECTED_OPERATOR_FAIL_NOTIFICATION = 6;
    static final int BLACKLISTED_CALL_NOTIFICATION = 7;
    static final int BLACKLISTED_MESSAGE_NOTIFICATION = 8;

    /** The singleton NotificationMgr instance. */
    private static NotificationMgr sInstance;

    private PhoneGlobals mApp;
    private Phone mPhone;

    private Context mContext;
    private NotificationManager mNotificationManager;
    private StatusBarManager mStatusBarManager;
    private UserManager mUserManager;
    private Toast mToast;
    private SubscriptionManager mSubscriptionManager;
    private TelecomManager mTelecomManager;
    private TelephonyManager mTelephonyManager;

    public StatusBarHelper statusBarHelper;

    // used to track blacklisted calls and messages
    private static class BlacklistedItemInfo {
        String number;
        long date;
        int matchType;

        BlacklistedItemInfo(String number, long date, int matchType) {
            this.number = number;
            this.date = date;
            this.matchType = matchType;
        }
    };
    private ArrayList<BlacklistedItemInfo> mBlacklistedCalls =
            new ArrayList<BlacklistedItemInfo>();
    private ArrayList<BlacklistedItemInfo> mBlacklistedMessages =
            new ArrayList<BlacklistedItemInfo>();

    // used to track the notification of selected network unavailable
    private boolean mSelectedUnavailableNotify = false;

    // used to track whether the message waiting indicator is visible, per subscription id.
    private ArrayMap<Integer, Boolean> mMwiVisible = new ArrayMap<Integer, Boolean>();

    /**
     * Private constructor (this is a singleton).
     * @see #init(PhoneGlobals)
     */
    private NotificationMgr(PhoneGlobals app) {
        mApp = app;
        mContext = app;
        mNotificationManager =
                (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        mStatusBarManager =
                (StatusBarManager) app.getSystemService(Context.STATUS_BAR_SERVICE);
        mUserManager = (UserManager) app.getSystemService(Context.USER_SERVICE);
        mPhone = app.mCM.getDefaultPhone();
        statusBarHelper = new StatusBarHelper();
        mSubscriptionManager = SubscriptionManager.from(mContext);
        mTelecomManager = TelecomManager.from(mContext);
        mTelephonyManager = (TelephonyManager) app.getSystemService(Context.TELEPHONY_SERVICE);
    }

    /**
     * Initialize the singleton NotificationMgr instance.
     *
     * This is only done once, at startup, from PhoneApp.onCreate().
     * From then on, the NotificationMgr instance is available via the
     * PhoneApp's public "notificationMgr" field, which is why there's no
     * getInstance() method here.
     */
    /* package */ static NotificationMgr init(PhoneGlobals app) {
        synchronized (NotificationMgr.class) {
            if (sInstance == null) {
                sInstance = new NotificationMgr(app);
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    /**
     * Helper class that's a wrapper around the framework's
     * StatusBarManager.disable() API.
     *
     * This class is used to control features like:
     *
     *   - Disabling the status bar "notification windowshade"
     *     while the in-call UI is up
     *
     *   - Disabling notification alerts (audible or vibrating)
     *     while a phone call is active
     *
     *   - Disabling navigation via the system bar (the "soft buttons" at
     *     the bottom of the screen on devices with no hard buttons)
     *
     * We control these features through a single point of control to make
     * sure that the various StatusBarManager.disable() calls don't
     * interfere with each other.
     */
    public class StatusBarHelper {
        // Current desired state of status bar / system bar behavior
        private boolean mIsNotificationEnabled = true;
        private boolean mIsExpandedViewEnabled = true;
        private boolean mIsSystemBarNavigationEnabled = true;

        private StatusBarHelper() {
        }

        /**
         * Enables or disables auditory / vibrational alerts.
         *
         * (We disable these any time a voice call is active, regardless
         * of whether or not the in-call UI is visible.)
         */
        public void enableNotificationAlerts(boolean enable) {
            if (mIsNotificationEnabled != enable) {
                mIsNotificationEnabled = enable;
                updateStatusBar();
            }
        }

        /**
         * Enables or disables the expanded view of the status bar
         * (i.e. the ability to pull down the "notification windowshade").
         *
         * (This feature is disabled by the InCallScreen while the in-call
         * UI is active.)
         */
        public void enableExpandedView(boolean enable) {
            if (mIsExpandedViewEnabled != enable) {
                mIsExpandedViewEnabled = enable;
                updateStatusBar();
            }
        }

        /**
         * Enables or disables the navigation via the system bar (the
         * "soft buttons" at the bottom of the screen)
         *
         * (This feature is disabled while an incoming call is ringing,
         * because it's easy to accidentally touch the system bar while
         * pulling the phone out of your pocket.)
         */
        public void enableSystemBarNavigation(boolean enable) {
            if (mIsSystemBarNavigationEnabled != enable) {
                mIsSystemBarNavigationEnabled = enable;
                updateStatusBar();
            }
        }

        /**
         * Updates the status bar to reflect the current desired state.
         */
        private void updateStatusBar() {
            int state = StatusBarManager.DISABLE_NONE;

            if (!mIsExpandedViewEnabled) {
                state |= StatusBarManager.DISABLE_EXPAND;
            }
            if (!mIsNotificationEnabled) {
                state |= StatusBarManager.DISABLE_NOTIFICATION_ALERTS;
            }
            if (!mIsSystemBarNavigationEnabled) {
                // Disable *all* possible navigation via the system bar.
                state |= StatusBarManager.DISABLE_HOME;
                state |= StatusBarManager.DISABLE_RECENT;
                state |= StatusBarManager.DISABLE_BACK;
                state |= StatusBarManager.DISABLE_SEARCH;
            }

            if (DBG) log("updateStatusBar: state = 0x" + Integer.toHexString(state));
            mStatusBarManager.disable(state);
        }
    }

    /** The projection to use when querying the phones table */
    static final String[] PHONES_PROJECTION = new String[] {
        PhoneLookup.NUMBER,
        PhoneLookup.DISPLAY_NAME,
        PhoneLookup._ID
    };

    private static final RelativeSizeSpan TIME_SPAN = new RelativeSizeSpan(0.7f);

    private CharSequence formatSingleCallLine(String caller, long date) {
        int flags = DateUtils.FORMAT_SHOW_TIME;
        if (!DateUtils.isToday(date)) {
            flags |= DateUtils.FORMAT_SHOW_WEEKDAY;
        }

        SpannableStringBuilder lineBuilder = new SpannableStringBuilder();
        lineBuilder.append(caller);
        lineBuilder.append("  ");

        int timeIndex = lineBuilder.length();
        lineBuilder.append(DateUtils.formatDateTime(mContext, date, flags));
        lineBuilder.setSpan(TIME_SPAN, timeIndex, lineBuilder.length(), 0);

        return lineBuilder;
    }

    /* package */ void notifyBlacklistedCall(String number, long date, int matchType) {
        notifyBlacklistedItem(number, date, matchType, BLACKLISTED_CALL_NOTIFICATION);
    }

    /* package */ void notifyBlacklistedMessage(String number, long date, int matchType) {
        notifyBlacklistedItem(number, date, matchType, BLACKLISTED_MESSAGE_NOTIFICATION);
    }

    private void notifyBlacklistedItem(String number, long date,
            int matchType, int notificationId) {
        if (!BlacklistUtils.isBlacklistNotifyEnabled(mContext)) {
            return;
        }

        if (VDBG) {
            log("notifyBlacklistedItem(). number: " + number
                + ", match type: " + matchType + ", date: " + date + ", type: " + notificationId);
        }

        ArrayList<BlacklistedItemInfo> items = notificationId == BLACKLISTED_CALL_NOTIFICATION
                ? mBlacklistedCalls : mBlacklistedMessages;
        PendingIntent clearIntent = notificationId == BLACKLISTED_CALL_NOTIFICATION
                ? createClearBlacklistedCallsIntent() : createClearBlacklistedMessagesIntent();
        int iconDrawableResId = notificationId == BLACKLISTED_CALL_NOTIFICATION
                ? R.drawable.ic_block_incoming_call : R.drawable.ic_block_incoming_message;

        // Keep track of the call/message, keeping list sorted from newest to oldest
        items.add(0, new BlacklistedItemInfo(number, date, matchType));

        // Get the intent to open Blacklist settings if user taps on content ready
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClassName("com.android.settings",
                "com.android.settings.Settings$BlacklistSettingsActivity");
        PendingIntent blSettingsIntent = PendingIntent.getActivity(mContext, 0, intent, 0);

        // Start building the notification
        Notification.Builder builder = new Notification.Builder(mContext);
        builder.setSmallIcon(iconDrawableResId)
                .setContentIntent(blSettingsIntent)
                .setAutoCancel(true)
                .setContentTitle(mContext.getString(R.string.blacklist_title))
                .setWhen(date)
                .setDeleteIntent(clearIntent);

        // Add the 'Remove block' notification action only for MATCH_LIST items since
        // MATCH_REGEX and MATCH_PRIVATE items does not have an associated specific number
        // to unblock, and MATCH_UNKNOWN unblock for a single number does not make sense.
        boolean addUnblockAction = true;

        if (items.size() == 1) {
            int messageResId;

            switch (matchType) {
                case BlacklistUtils.MATCH_PRIVATE:
                    messageResId = notificationId == BLACKLISTED_CALL_NOTIFICATION
                            ? R.string.blacklist_call_notification_private_number
                            : R.string.blacklist_message_notification_private_number;
                    break;
                case BlacklistUtils.MATCH_UNKNOWN:
                    messageResId = notificationId == BLACKLISTED_CALL_NOTIFICATION
                            ? R.string.blacklist_call_notification_unknown_number
                            : R.string.blacklist_message_notification_unknown_number;
                    break;
                default:
                    messageResId = notificationId == BLACKLISTED_CALL_NOTIFICATION
                            ? R.string.blacklist_call_notification
                            : R.string.blacklist_message_notification;
                    break;
            }
            builder.setContentText(mContext.getString(messageResId, number));

            if (matchType != BlacklistUtils.MATCH_LIST) {
                addUnblockAction = false;
            }
        } else {
            int messageResId = notificationId == BLACKLISTED_CALL_NOTIFICATION
                    ? R.string.blacklist_call_notification_multiple
                    : R.string.blacklist_message_notification_multiple;
            String message = mContext.getString(messageResId, items.size());

            builder.setContentText(message);
            builder.setNumber(items.size());

            Notification.InboxStyle style = new Notification.InboxStyle(builder);

            for (BlacklistedItemInfo info : items) {
                // Takes care of displaying "Private" instead of an empty string
                String numberString = TextUtils.isEmpty(info.number)
                        ? mContext.getString(R.string.blacklist_notification_list_private)
                        : info.number;
                style.addLine(formatSingleCallLine(numberString, info.date));

                if (!TextUtils.equals(number, info.number)) {
                    addUnblockAction = false;
                } else if (info.matchType != BlacklistUtils.MATCH_LIST) {
                    addUnblockAction = false;
                }
            }
            style.setBigContentTitle(message);
            style.setSummaryText(" ");
            builder.setStyle(style);
        }

        if (addUnblockAction) {
            int actionDrawableResId = notificationId == BLACKLISTED_CALL_NOTIFICATION
                    ? R.drawable.ic_unblock_incoming_call
                    : R.drawable.ic_unblock_incoming_message;
            int unblockType = notificationId == BLACKLISTED_CALL_NOTIFICATION
                    ? BlacklistUtils.BLOCK_CALLS : BlacklistUtils.BLOCK_MESSAGES;
            PendingIntent action = PhoneGlobals.getUnblockNumberFromNotificationPendingIntent(
                    mContext, number, unblockType);

            builder.addAction(actionDrawableResId,
                    mContext.getString(R.string.unblock_number), action);
        }

        mNotificationManager.notify(notificationId, builder.getNotification());
    }

    private PendingIntent createClearBlacklistedCallsIntent() {
        Intent intent = new Intent(mContext, ClearBlacklistService.class);
        intent.setAction(ClearBlacklistService.ACTION_CLEAR_BLACKLISTED_CALLS);
        return PendingIntent.getService(mContext, 0, intent, 0);
    }

    private PendingIntent createClearBlacklistedMessagesIntent() {
        Intent intent = new Intent(mContext, ClearBlacklistService.class);
        intent.setAction(ClearBlacklistService.ACTION_CLEAR_BLACKLISTED_MESSAGES);
        return PendingIntent.getService(mContext, 0, intent, 0);
    }

    void cancelBlacklistedNotification(int type) {
        if ((type & BlacklistUtils.BLOCK_CALLS) != 0) {
            mBlacklistedCalls.clear();
            mNotificationManager.cancel(BLACKLISTED_CALL_NOTIFICATION);
        }
        if ((type & BlacklistUtils.BLOCK_MESSAGES) != 0) {
            mBlacklistedMessages.clear();
            mNotificationManager.cancel(BLACKLISTED_MESSAGE_NOTIFICATION);
        }
    }

    /**
     * Re-creates the message waiting indicator (voicemail) notification if it is showing.  Used to
     * refresh the voicemail intent on the indicator when the user changes it via the voicemail
     * settings screen.  The voicemail notification sound is suppressed.
     *
     * @param subId The subscription Id.
     */
    /* package */ void refreshMwi(int subId) {
        // In a single-sim device, subId can be -1 which means "no sub id".  In this case we will
        // reference the single subid stored in the mMwiVisible map.
        if (subId == SubscriptionInfoHelper.NO_SUB_ID) {
            if (mMwiVisible.keySet().size() == 1) {
                Set<Integer> keySet = mMwiVisible.keySet();
                Iterator<Integer> keyIt = keySet.iterator();
                if (!keyIt.hasNext()) {
                    return;
                }
                subId = keyIt.next();
            }
        }
        if (mMwiVisible.containsKey(subId)) {
            boolean mwiVisible = mMwiVisible.get(subId);
            if (mwiVisible) {
                updateMwi(subId, mwiVisible, false /* enableNotificationSound */);
            }
        }
    }

    /**
     * Updates the message waiting indicator (voicemail) notification.
     *
     * @param visible true if there are messages waiting
     */
    /* package */ void updateMwi(int subId, boolean visible) {
        updateMwi(subId, visible, true /* enableNotificationSound */);
    }

    /**
     * Updates the message waiting indicator (voicemail) notification.
     *
     * @param subId the subId to update.
     * @param visible true if there are messages waiting
     * @param enableNotificationSound {@code true} if the notification sound should be played.
     */
    void updateMwi(int subId, boolean visible, boolean enableNotificationSound) {
        if (!PhoneGlobals.sVoiceCapable) {
            // Do not show the message waiting indicator on devices which are not voice capable.
            // These events *should* be blocked at the telephony layer for such devices.
            Log.w(LOG_TAG, "Called updateMwi() on non-voice-capable device! Ignoring...");
            return;
        }

        Log.i(LOG_TAG, "updateMwi(): subId " + subId + " update to " + visible);
        mMwiVisible.put(subId, visible);

        if (visible) {
            Phone phone = PhoneGlobals.getPhone(subId);
            if (phone == null) {
                Log.w(LOG_TAG, "Found null phone for: " + subId);
                return;
            }

            SubscriptionInfo subInfo = mSubscriptionManager.getActiveSubscriptionInfo(subId);
            if (subInfo == null) {
                Log.w(LOG_TAG, "Found null subscription info for: " + subId);
                return;
            }

            int resId = android.R.drawable.stat_notify_voicemail;

            // This Notification can get a lot fancier once we have more
            // information about the current voicemail messages.
            // (For example, the current voicemail system can't tell
            // us the caller-id or timestamp of a message, or tell us the
            // message count.)

            // But for now, the UI is ultra-simple: if the MWI indication
            // is supposed to be visible, just show a single generic
            // notification.

            String notificationTitle = mContext.getString(R.string.notification_voicemail_title);
            String vmNumber = phone.getVoiceMailNumber();
            if (DBG) log("- got vm number: '" + vmNumber + "'");

            // The voicemail number may be null because:
            //   (1) This phone has no voicemail number.
            //   (2) This phone has a voicemail number, but the SIM isn't ready yet. This may
            //       happen when the device first boots if we get a MWI notification when we
            //       register on the network before the SIM has loaded. In this case, the
            //       SubscriptionListener in CallNotifier will update this once the SIM is loaded.
            if ((vmNumber == null) && !phone.getIccRecordsLoaded()) {
                if (DBG) log("- Null vm number: SIM records not loaded (yet)...");
                return;
            }

            if (TelephonyCapabilities.supportsVoiceMessageCount(phone)) {
                int vmCount = phone.getVoiceMessageCount();
                String titleFormat = mContext.getString(R.string.notification_voicemail_title_count);
                notificationTitle = String.format(titleFormat, vmCount);
            }

            // This pathway only applies to PSTN accounts; only SIMS have subscription ids.
            PhoneAccountHandle phoneAccountHandle = PhoneUtils.makePstnPhoneAccountHandle(phone);

            Intent intent;
            String notificationText;
            if (TextUtils.isEmpty(vmNumber)) {
                notificationText = mContext.getString(
                        R.string.notification_voicemail_no_vm_number);

                // If the voicemail number if unknown, instead of calling voicemail, take the user
                // to the voicemail settings.
                notificationText = mContext.getString(
                        R.string.notification_voicemail_no_vm_number);
                intent = new Intent(CallFeaturesSetting.ACTION_ADD_VOICEMAIL);
                intent.putExtra(CallFeaturesSetting.SETUP_VOICEMAIL_EXTRA, true);
                intent.putExtra(SubscriptionInfoHelper.SUB_ID_EXTRA, subId);
                intent.setClass(mContext, CallFeaturesSetting.class);
            } else {
                if (mTelephonyManager.getPhoneCount() > 1) {
                    notificationText = subInfo.getDisplayName().toString();
                } else {
                    notificationText = String.format(
                            mContext.getString(R.string.notification_voicemail_text_format),
                            PhoneNumberUtils.formatNumber(vmNumber));
                }
                intent = new Intent(
                        Intent.ACTION_CALL, Uri.fromParts(PhoneAccount.SCHEME_VOICEMAIL, "",
                        null));
                intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle);
            }

            PendingIntent pendingIntent =
                    PendingIntent.getActivity(mContext, subId /* requestCode */, intent, 0);
            Uri ringtoneUri = null;

            if (enableNotificationSound) {
                ringtoneUri = VoicemailNotificationSettingsUtil.getRingtoneUri(mPhone);
            }

            Notification.Builder builder = new Notification.Builder(mContext);
            builder.setSmallIcon(resId)
                    .setWhen(System.currentTimeMillis())
                    .setColor(subInfo.getIconTint())
                    .setContentTitle(notificationTitle)
                    .setContentText(notificationText)
                    .setContentIntent(pendingIntent)
                    .setSound(ringtoneUri)
                    .setColor(mContext.getResources().getColor(R.color.dialer_theme_color))
                    .setOngoing(true);

            if (VoicemailNotificationSettingsUtil.isVibrationEnabled(phone)) {
                builder.setDefaults(Notification.DEFAULT_VIBRATE);
            }

            final Notification notification = builder.build();
            List<UserInfo> users = mUserManager.getUsers(true);
            for (int i = 0; i < users.size(); i++) {
                final UserInfo user = users.get(i);
                final UserHandle userHandle = user.getUserHandle();
                if (!mUserManager.hasUserRestriction(
                        UserManager.DISALLOW_OUTGOING_CALLS, userHandle)
                            && !user.isManagedProfile()) {
                    mNotificationManager.notifyAsUser(
                            Integer.toString(subId) /* tag */,
                            VOICEMAIL_NOTIFICATION,
                            notification,
                            userHandle);
                }
            }
        } else {
            mNotificationManager.cancelAsUser(
                    Integer.toString(subId) /* tag */,
                    VOICEMAIL_NOTIFICATION,
                    UserHandle.ALL);
        }
    }

    /**
     * Updates the message call forwarding indicator notification.
     *
     * @param visible true if there are messages waiting
     */
    /* package */ void updateCfi(int subId, boolean visible) {
        if (DBG) log("updateCfi(): " + visible);
        if (visible) {
            // If Unconditional Call Forwarding (forward all calls) for VOICE
            // is enabled, just show a notification.  We'll default to expanded
            // view for now, so the there is less confusion about the icon.  If
            // it is deemed too weird to have CF indications as expanded views,
            // then we'll flip the flag back.

            // TODO: We may want to take a look to see if the notification can
            // display the target to forward calls to.  This will require some
            // effort though, since there are multiple layers of messages that
            // will need to propagate that information.

            SubscriptionInfo subInfo = mSubscriptionManager.getActiveSubscriptionInfo(subId);
            if (subInfo == null) {
                Log.w(LOG_TAG, "Found null subscription info for: " + subId);
                return;
            }

            String notificationTitle;
            if (mTelephonyManager.getPhoneCount() > 1) {
                notificationTitle = subInfo.getDisplayName().toString();
            } else {
                notificationTitle = mContext.getString(R.string.labelCF);
            }

            Notification.Builder builder = new Notification.Builder(mContext)
                    .setSmallIcon(R.drawable.stat_sys_phone_call_forward)
                    .setColor(subInfo.getIconTint())
                    .setContentTitle(notificationTitle)
                    .setContentText(mContext.getString(R.string.sum_cfu_enabled_indicator))
                    .setShowWhen(false)
                    .setOngoing(true);

            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.setClassName("com.android.phone", "com.android.phone.CallFeaturesSetting");
            SubscriptionInfoHelper.addExtrasToIntent(
                    intent, mSubscriptionManager.getActiveSubscriptionInfo(subId));
            PendingIntent contentIntent =
                    PendingIntent.getActivity(mContext, subId /* requestCode */, intent, 0);

            List<UserInfo> users = mUserManager.getUsers(true);
            for (int i = 0; i < users.size(); i++) {
                final UserInfo user = users.get(i);
                if (user.isManagedProfile()) {
                    continue;
                }
                UserHandle userHandle = user.getUserHandle();
                builder.setContentIntent(userHandle.isOwner() ? contentIntent : null);
                mNotificationManager.notifyAsUser(
                        Integer.toString(subId) /* tag */,
                        CALL_FORWARD_NOTIFICATION,
                        builder.build(),
                        userHandle);
            }
        } else {
            mNotificationManager.cancelAsUser(
                    Integer.toString(subId) /* tag */,
                    CALL_FORWARD_NOTIFICATION,
                    UserHandle.ALL);
        }
    }

    /**
     * Shows the "data disconnected due to roaming" notification, which
     * appears when you lose data connectivity because you're roaming and
     * you have the "data roaming" feature turned off.
     */
    /* package */ void showDataDisconnectedRoaming() {
        if (DBG) log("showDataDisconnectedRoaming()...");

        // "Mobile network settings" screen / dialog
        Intent intent = new Intent(mContext, com.android.phone.MobileNetworkSettings.class);
        PendingIntent contentIntent = PendingIntent.getActivity(mContext, 0, intent, 0);

        final CharSequence contentText = mContext.getText(R.string.roaming_reenable_message);

        final Notification.Builder builder = new Notification.Builder(mContext)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(mContext.getText(R.string.roaming))
                .setColor(mContext.getResources().getColor(R.color.dialer_theme_color))
                .setContentText(contentText);

        List<UserInfo> users = mUserManager.getUsers(true);
        for (int i = 0; i < users.size(); i++) {
            final UserInfo user = users.get(i);
            if (user.isManagedProfile()) {
                continue;
            }
            UserHandle userHandle = user.getUserHandle();
            builder.setContentIntent(userHandle.isOwner() ? contentIntent : null);
            final Notification notif =
                    new Notification.BigTextStyle(builder).bigText(contentText).build();
            mNotificationManager.notifyAsUser(
                    null /* tag */, DATA_DISCONNECTED_ROAMING_NOTIFICATION, notif, userHandle);
        }
    }

    /**
     * Turns off the "data disconnected due to roaming" notification.
     */
    /* package */ void hideDataDisconnectedRoaming() {
        if (DBG) log("hideDataDisconnectedRoaming()...");
        mNotificationManager.cancel(DATA_DISCONNECTED_ROAMING_NOTIFICATION);
    }

    /**
     * Display the network selection "no service" notification
     * @param operator is the numeric operator number
     */
    private void showNetworkSelection(String operator) {
        if (DBG) log("showNetworkSelection(" + operator + ")...");

        Notification.Builder builder = new Notification.Builder(mContext)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(mContext.getString(R.string.notification_network_selection_title))
                .setContentText(
                        mContext.getString(R.string.notification_network_selection_text, operator))
                .setShowWhen(false)
                .setOngoing(true);

        // create the target network operators settings intent
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // Use NetworkSetting to handle the selection intent
        intent.setComponent(new ComponentName("com.android.phone",
                "com.android.phone.NetworkSetting"));
        PendingIntent contentIntent = PendingIntent.getActivity(mContext, 0, intent, 0);

        List<UserInfo> users = mUserManager.getUsers(true);
        for (int i = 0; i < users.size(); i++) {
            final UserInfo user = users.get(i);
            if (user.isManagedProfile()) {
                continue;
            }
            UserHandle userHandle = user.getUserHandle();
            builder.setContentIntent(userHandle.isOwner() ? contentIntent : null);
            mNotificationManager.notifyAsUser(
                    null /* tag */,
                    SELECTED_OPERATOR_FAIL_NOTIFICATION,
                    builder.build(),
                    userHandle);
        }
    }

    /**
     * Turn off the network selection "no service" notification
     */
    private void cancelNetworkSelection() {
        if (DBG) log("cancelNetworkSelection()...");
        mNotificationManager.cancelAsUser(
                null /* tag */, SELECTED_OPERATOR_FAIL_NOTIFICATION, UserHandle.ALL);
    }

    /**
     * Update notification about no service of user selected operator
     *
     * @param serviceState Phone service state
     */
    void updateNetworkSelection(int serviceState) {
        if (TelephonyCapabilities.supportsNetworkSelection(mPhone)) {
            int subId = mPhone.getSubId();
            if (SubscriptionManager.isValidSubscriptionId(subId)) {
                // get the shared preference of network_selection.
                // empty is auto mode, otherwise it is the operator alpha name
                // in case there is no operator name, check the operator numeric
                SharedPreferences sp =
                        PreferenceManager.getDefaultSharedPreferences(mContext);
                String networkSelection =
                        sp.getString(PhoneBase.NETWORK_SELECTION_NAME_KEY + subId, "");
                if (TextUtils.isEmpty(networkSelection)) {
                    networkSelection =
                            sp.getString(PhoneBase.NETWORK_SELECTION_KEY + subId, "");
                }

                if (DBG) log("updateNetworkSelection()..." + "state = " +
                        serviceState + " new network " + networkSelection);

                if (serviceState == ServiceState.STATE_OUT_OF_SERVICE
                        && !TextUtils.isEmpty(networkSelection)) {
                    if (!mSelectedUnavailableNotify) {
                        showNetworkSelection(networkSelection);
                        mSelectedUnavailableNotify = true;
                    }
                } else {
                    if (mSelectedUnavailableNotify) {
                        cancelNetworkSelection();
                        mSelectedUnavailableNotify = false;
                    }
                }
            } else {
                if (DBG) log("updateNetworkSelection()..." + "state = " +
                        serviceState + " not updating network due to invalid subId " + subId);
            }
        }
    }

    /* package */ void postTransientNotification(int notifyId, CharSequence msg) {
        if (mToast != null) {
            mToast.cancel();
        }

        mToast = Toast.makeText(mContext, msg, Toast.LENGTH_LONG);
        mToast.show();
    }

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
