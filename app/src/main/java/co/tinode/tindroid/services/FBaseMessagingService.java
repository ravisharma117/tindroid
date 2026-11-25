package co.tinode.tindroid.services;

import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.RingtoneManager;
import android.text.TextUtils;
import android.util.Log;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import co.tinode.tindroid.Cache;
import co.tinode.tindroid.CallInProgress;
import co.tinode.tindroid.ChatsActivity;
import co.tinode.tindroid.Const;
import co.tinode.tindroid.HangUpBroadcastReceiver;
import co.tinode.tindroid.MessageActivity;
import co.tinode.tindroid.R;
import co.tinode.tindroid.UiUtils;
import co.tinode.tindroid.account.Utils;
import co.tinode.tinodesdk.Tinode;
import co.tinode.tinodesdk.Topic;

/**
 * Receive and handle (e.g. show) a push notification message.
 */
public class FBaseMessagingService extends FirebaseMessagingService {

    private static final String TAG = "FBaseMessagingService";

    // Width and height of the large icon (avatar).
    private static final int AVATAR_SIZE = 128;
    // Max length of the message.
    private static final int MAX_MESSAGE_LENGTH = 80;

    @Override
    public void onNewToken(@NonNull final String token) {
        super.onNewToken(token);
        Log.d(TAG, "New registration token: " + token);
        broadcastToken(token);
    }

    @Override
    public void onRegistered(@NonNull final String installationId) {
        super.onRegistered(installationId);
        Log.d(TAG, "Registered FID: " + installationId);
        broadcastToken(installationId);
    }

    @Override
    public void onUnregistered(@NonNull final String installationId) {
        super.onUnregistered(installationId);
        Log.d(TAG, "Unregistered FID: " + installationId);
        broadcastToken("");
    }

    private void broadcastToken(String token) {
        // Send token to the server.
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        Intent intent = new Intent("FCM_REFRESH_TOKEN");
        intent.putExtra("token", token);
        lbm.sendBroadcast(intent);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onMessageReceived(RemoteMessage remoteMessage) {
        // There are two types of messages data messages and notification messages. Data messages are handled
        // here in onMessageReceived whether the app is in the foreground or background. Data messages are the type
        // traditionally used with GCM. Notification messages are only received here in onMessageReceived when the app
        // is in the foreground. When the app is in the background an automatically generated notification is displayed.
        // When the user taps on the notification they are returned to the app. Messages containing both notification
        // and data payloads are treated as notification messages. The Firebase console always sends notification
        // messages. For more see: https://firebase.google.com/docs/cloud-messaging/concept-options

        // Not getting messages here? See why this may be: https://goo.gl/39bRNJ
        Log.d(TAG, "From: " + remoteMessage.getFrom());

        // GEIA: every visible notification (message, new chat) reads "Geia Messaging" / "New message received":
        // nothing private is shown, whatever the push contains. Silent pushes (read, deleted, calls) are unchanged.
        //

        String topicName;

        final Tinode tinode = Cache.getTinode();
        NotificationCompat.Builder builder;

        // Check if message contains a data payload.
        if (!remoteMessage.getData().isEmpty()) {
            Map<String, String> data = remoteMessage.getData();

            // Check notification type: message, subscription.
            String what = data.get("what");
            topicName = data.get("topic");
            if (topicName == null || what == null) {
                Log.w(TAG, "Invalid payload: " + (what == null ? "what" : "topic") + " is NULL");
                return;
            }

            String webrtc = data.get("webrtc");
            String senderId = data.get("xfrom");

            // Update data state, maybe fetch missing data.
            String token = Utils.getLoginToken(getApplicationContext());
            String selectedTopic = Cache.getSelectedTopicName();
            tinode.oobNotification(data, token, "started".equals(webrtc) ||
                    topicName.equals(selectedTopic));

            if (webrtc != null) {
                // It's a video call.
                handleCallNotification(webrtc, tinode.isMe(senderId), data);
                return;
            }

            if (Boolean.parseBoolean(data.get("silent"))) {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.cancel(topicName, 0);
                }
                return;
            }

            String visibleTopic = UiUtils.getVisibleTopic();
            if (visibleTopic != null && visibleTopic.equals(topicName)) {
                // No need to do anything if we are in the topic already.
                return;
            }

            Topic.TopicType tp = Topic.getTopicTypeByName(topicName);
            if (tp != Topic.TopicType.P2P && tp != Topic.TopicType.GRP) {
                Log.w(TAG, "Unexpected topic type=" + tp);
                return;
            }

            // GEIA: content-free notifications. Whatever the push carries, the visible text is fixed: no sender,
            // no chat name, no avatar and no message text on the lock screen. The app fetches the real message
            // itself (oobNotification above). The server sends references only (see the fcm payload patch).
            if (!TextUtils.isEmpty(what) && !"msg".equals(what) && !"sub".equals(what)) {
                Log.w(TAG, "Unexpected notification type=" + what);
                return;
            }
            builder = composeNotification(getResources().getString(R.string.push_title),
                    getResources().getString(R.string.push_body), null);

        } else if (remoteMessage.getNotification() != null) {
            RemoteMessage.Notification remote = remoteMessage.getNotification();

            topicName = remote.getTag();
            builder = composeNotification(remote);
        } else {
            // Everything is null.
            return;
        }

        showNotification(builder, topicName);
    }

    private void showNotification(NotificationCompat.Builder builder, String topicName) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            Log.e(TAG, "NotificationManager is not available");
            return;
        }

        // Workaround for an FCM bug or poor documentation.
        int requestCode = 0;

        Intent intent;
        if (TextUtils.isEmpty(topicName)) {
            // Communication on an unknown topic
            intent = new Intent(this, ChatsActivity.class);
        } else {
            requestCode = topicName.hashCode();
            // Communication on a known topic
            intent = new Intent(this, MessageActivity.class);
            intent.putExtra(Const.INTENT_EXTRA_TOPIC, topicName);
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, requestCode, intent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);

        // MessageActivity will cancel all notifications by tag, which is just topic name.
        // All notifications receive the same id 0 because id is not used.
        nm.notify(topicName, 0, builder.setContentIntent(pendingIntent).build());
    }

    private void handleCallNotification(@NonNull String webrtc, boolean isMe, @NonNull Map<String, String> data) {
        String seqStr = data.get("seq");
        String topicName = data.get("topic");
        try {
            int seq = seqStr != null ? Integer.parseInt(seqStr) : 0;
            if (seq <= 0) {
                Log.w(TAG, "Invalid seq value '" + seqStr + "'");
                return;
            }
            int origSeq = UiUtils.parseSeqReference(data.get("replace"));
            switch (webrtc) {
                case "started":
                    // Do nothing here: the incoming call is accepted in onData.
                    break;
                case "accepted":
                    CallInProgress call = Cache.getCallInProgress();
                    if (origSeq > 0 && call != null && call.isConnected() && call.equals(topicName, origSeq)) {
                        // The server notifies us of the call that we've already accepted. Do nothing.
                        return;
                    }
                case "busy":
                case "declined":
                case "disconnected":
                case "finished":
                case "missed":
                    if (origSeq > 0) {
                        // Dismiss the call UI.
                        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
                        final Intent intent = new Intent(this, HangUpBroadcastReceiver.class);
                        intent.setAction(Const.INTENT_ACTION_CALL_CLOSE);
                        intent.putExtra(Const.INTENT_EXTRA_TOPIC, topicName);
                        intent.putExtra(Const.INTENT_EXTRA_SEQ, origSeq);
                        lbm.sendBroadcast(intent);
                    }
                    break;
                default:
                    Log.w(TAG, "Unknown webrtc action '" + webrtc + "'");
                    break;
            }
        } catch (NumberFormatException ex) {
            Log.w(TAG, "Invalid seq value '" + seqStr + "'");
        }
    }

    /**
     * Create and show a simple notification containing the received FCM message.
     *
     * @param title  message title.
     * @param body   message body.
     * @param avatar sender's avatar.
     */
    private NotificationCompat.Builder composeNotification(String title, CharSequence body, Bitmap avatar) {
        NotificationCompat.Builder notificationBuilder =
                new NotificationCompat.Builder(this, Const.NEWMSG_NOTIFICATION_CHAN_ID);

        return notificationBuilder
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setSmallIcon(R.drawable.ic_icon_push)
                .setLargeIcon(avatar)
                .setColor(ContextCompat.getColor(this, R.color.colorNotificationBackground))
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
    }

    private NotificationCompat.Builder composeNotification(@NonNull RemoteMessage.Notification remote) {
        NotificationCompat.Builder notificationBuilder =
                new NotificationCompat.Builder(this, Const.NEWMSG_NOTIFICATION_CHAN_ID);

        final Resources res = getResources();
        final String packageName = getPackageName();

        return notificationBuilder
                .setPriority(unwrapInteger(remote.getNotificationPriority(), NotificationCompat.PRIORITY_HIGH))
                .setVisibility(unwrapInteger(remote.getVisibility(), NotificationCompat.VISIBILITY_PRIVATE))
                .setSmallIcon(resourceId(res, remote.getIcon(), R.drawable.ic_icon_push, "drawable", packageName))
                .setColor(unwrapColor(remote.getColor(), ContextCompat.getColor(this, R.color.colorNotificationBackground)))
                .setContentTitle(locText(res, remote.getTitleLocalizationKey(), remote.getTitleLocalizationArgs(),
                        remote.getTitle(), packageName))
                .setContentText(locText(res, remote.getBodyLocalizationKey(), remote.getBodyLocalizationArgs(),
                        remote.getBody(), packageName))
                .setAutoCancel(true)
                // TODO: use remote.getSound() instead of default.
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
    }

    private static int unwrapInteger(Integer value, int defaultValue) {
        return value != null ? value : defaultValue;
    }

    @SuppressWarnings("SameParameterValue")
    private static int resourceId(Resources res, String name, int defaultId, String resourceType, String packageName) {
        @SuppressLint("DiscouragedApi") int id = res.getIdentifier(name, resourceType, packageName);
        return id != 0 ? id : defaultId;
    }

    private static int unwrapColor(String strColor, int defaultColor) {
        int color = defaultColor;
        if (strColor != null) {
            try {
                color = Color.parseColor(strColor);
            } catch (IllegalAccessError ignored) {
            }
        }
        return color;
    }

    // Localized text from resource name.
    private static String locText(Resources res, String locKey, String[] locArgs, String defaultText, String packageName) {
        String result = defaultText;
        if (locKey != null) {
            @SuppressLint("DiscouragedApi") int id = res.getIdentifier(locKey, "string", packageName);
            if (id != 0) {
                if (locArgs != null) {
                    result = res.getString(id, (Object[]) locArgs);
                } else {
                    result = res.getString(id);
                }
            }
        }
        return result;
    }
}
