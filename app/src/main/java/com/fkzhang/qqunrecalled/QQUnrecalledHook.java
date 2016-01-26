package com.fkzhang.qqunrecalled;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.support.v4.app.TaskStackBuilder;
import android.text.TextUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.callStaticMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.setObjectField;

/**
 * Created by fkzhang on 1/20/2016.
 */
public class QQUnrecalledHook {
    private final SettingsHelper mSettings;
    private Set<Long> RevokedUids;
    private Class<?> MessageRecordFactory;
    private Object mQQAppInterface;
    private String mSelfUin;
    private Class<?> ContactUtils;
    private Class<?> MessageRecord;
    private HashMap<Long, String> mMessageCache;
    private Context mNotificationContext;
    private Class<?> mNotificationClass;

    public QQUnrecalledHook() {
        RevokedUids = new HashSet<>();
        mMessageCache = new HashMap<>();
        mSettings = new SettingsHelper("com.fkzhang.qqunrecalled");
    }

    public void hook(final ClassLoader loader) {
        try {
            hookQQMessageFacade(loader);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    protected void hookQQMessageFacade(final ClassLoader loader) {
        findAndHookMethod("com.tencent.mobileqq.app.message.QQMessageFacade", loader,
                "a", ArrayList.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param)
                            throws Throwable {
                        param.setResult(null); // prevent call

                        if (isCallingFrom("C2CMessageProcessor")) {
                            return;
                        }

                        ArrayList list = (ArrayList) param.args[0];
                        if (list == null || list.isEmpty())
                            return;

                        Object obj = list.get(0);

                        initObjects(param.thisObject, loader);

                        try {
                            setMessageTip(param.thisObject, obj);
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                        }

                    }
                });

        findAndHookMethod("com.tencent.mobileqq.app.message.QQMessageFacade", loader,
                "notifyObservers", Object.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object object = param.args[0];
                        if (!object.getClass().getName().contains("MessageForText"))
                            return;

                        storeMessage(object);
                    }
                });
    }

    protected boolean isCallingFrom(String className) {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stackTraceElements) {
            if (element.getClassName().contains(className)) {
                return true;
            }
        }
        return false;
    }

    private void initObjects(Object thisObject, ClassLoader loader) {
        try {
            if (mQQAppInterface == null) {
                mQQAppInterface = getObjectField(thisObject, "a",
                        "com.tencent.mobileqq.app.QQAppInterface");
            }
            if (mSelfUin == null && mQQAppInterface != null) {
                mSelfUin = (String) callMethod(mQQAppInterface, "getAccount");
            }
            if (MessageRecordFactory == null) {
                MessageRecordFactory = findClass("com.tencent.mobileqq.service.message.MessageRecordFactory", loader);
            }
            if (ContactUtils == null) {
                ContactUtils = findClass("com.tencent.mobileqq.utils.ContactUtils", loader);
            }

            if (mNotificationContext == null) {
                mNotificationContext = (Context) callStaticMethod(
                        findClass("com.tencent.qphone.base.util.BaseApplication", loader),
                        "getContext");
            }
            if (mNotificationClass == null) {
                mNotificationClass = findClass("com.tencent.mobileqq.activity.SplashActivity", loader);
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

    }

    private void setMessageTip(Object QQMessageFacade, Object revokeMsgInfo) {
        long time = (long) getObjectField(revokeMsgInfo, "c", long.class);

        if (RevokedUids.contains(time)) {
            return;
        }
        RevokedUids.add(time);

        String friendUin = (String) getObjectField(revokeMsgInfo, "a", String.class);
        String senderUin = (String) getObjectField(revokeMsgInfo, "b", String.class);

        int istroop = (int) getObjectField(revokeMsgInfo, "a", int.class);
        long msgUid = (long) getObjectField(revokeMsgInfo, "b", long.class)
                + new Random().nextInt();
        long shmsgseq = (long) getObjectField(revokeMsgInfo, "a", long.class);
        String msg;
        if (istroop == 0) {
            msg = "对方";
        } else {
            msg = getFriendName(friendUin, senderUin);
        }
        mSettings.reload();
        msg += " " + mSettings.getString("qq_recalled", "尝试撤回一条消息 （已阻止)");

        if (mSettings.getBoolean("show_content", false)) {
            String message = getMessage(time);
            if (!TextUtils.isEmpty(message)) {
                msg += ": " + message;
            }
        }

        List tips = createMessageTip(friendUin, senderUin, msgUid, shmsgseq, time + 1,
                msg, istroop);
        if (tips == null || tips.isEmpty())
            return;

        callMethod(QQMessageFacade, "a", tips, mSelfUin);

        if (!mSettings.getBoolean("enable_recall_notification", true))
            return;

        showMessageNotification(senderUin, time);
    }

    private List createMessageTip(String friendUin, String senderUin, long msgUid,
                                  long shmsgseq,
                                  long time, String msg, int istroop) {
        int msgtype = -2031; // MessageRecord.MSG_TYPE_REVOKE_GRAY_TIPS
        Object messageRecord = callStaticMethod(MessageRecordFactory, "a", msgtype);
        if (istroop == 0) { // private chat revoke
            callMethod(messageRecord, "init", mSelfUin, senderUin, senderUin, msg, time, msgtype,
                    istroop, time);
        } else { // group chat revoke
            callMethod(messageRecord, "init", mSelfUin, friendUin, senderUin, msg, time, msgtype,
                    istroop, time);
        }

        setObjectField(messageRecord, "msgUid", msgUid);
        setObjectField(messageRecord, "shmsgseq", shmsgseq);
        setObjectField(messageRecord, "isread", true);

        List<Object> list = new ArrayList<>();
        list.add(messageRecord);

        return list;
    }

    protected String getFriendName(String friendUin, String senderUin) {
        String nickname = null;

        if (friendUin != null) {
            nickname = (String) callStaticMethod(ContactUtils, "a", mQQAppInterface, senderUin,
                    friendUin, 2, 0);
            if (TextUtils.isEmpty(nickname)) {
                nickname = (String) callStaticMethod(ContactUtils, "c", mQQAppInterface, friendUin, senderUin);
            }
        }
        if (TextUtils.isEmpty(nickname)) {
            nickname = (String) callStaticMethod(ContactUtils, "b", mQQAppInterface, senderUin, true);
        }
        if (TextUtils.isEmpty(nickname)) {
            nickname = senderUin;
        }
        return nickname;
    }

    public static Object getObjectField(Object o, String fieldName, String type) {
        Field[] fields = o.getClass().getDeclaredFields();
        for (Field field : fields) {
            if (field.getName().equals(fieldName) && field.getType().getName().equals(type)) {
                field.setAccessible(true);
                try {
                    return field.get(o);
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    public static Object getObjectField(Object o, String fieldName, Class<?> type) {
        return getObjectField(o, fieldName, type.getName());
    }

    public static Object getObjectField(Class<?> cls, Object o, String fieldName) {
        try {
            Field field = cls.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(o);
        } catch (Exception e) {
            XposedBridge.log(e);
        }
        return null;
    }

    protected void storeMessage(Object object) {
        if (MessageRecord == null) {
            MessageRecord = object.getClass().getSuperclass().getSuperclass();
        }
        try {
            long time = (long) getObjectField(MessageRecord, object, "time");
            String msg = (String) getObjectField(MessageRecord, object, "msg");

            mMessageCache.put(time, msg);
            manageMessages();
        } catch (Exception e) {
            XposedBridge.log(e);
        }
    }

    protected String getMessage(long time) {
        if (mMessageCache.containsKey(time)) {
            return mMessageCache.get(time);
        }
        return null;
    }

    protected void manageMessages() {
        long time = System.currentTimeMillis() / 1000;
        for (long t : mMessageCache.keySet()) {
            if (time - t > 10800) {
                mMessageCache.remove(t);
            }
        }
    }

    protected void showMessageNotification(String uin, long time) {
        try {
            String msg = getMessage(time);
            if (TextUtils.isEmpty(msg))
                return;

            mMessageCache.remove(time);
            String title = getFriendName(null, uin) + " " + mSettings.getString("qq_recalled",
                    "尝试撤回一条消息");
            showTextNotification(title, msg, getAvatar(uin));
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    public Bitmap getAvatar(String uin) {
        if (mQQAppInterface == null)
            return null;

        return (Bitmap) callMethod(mQQAppInterface, "a", uin, (byte) 3, true);
    }

    protected void showTextNotification(String title, String content, Bitmap icon) {

        Notification.Builder builder = new Notification.Builder(mNotificationContext)
                .setContentTitle(title)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true);

        if (icon != null) {
            builder.setLargeIcon(icon);
        }

        if (content != null) {
            builder.setContentText(content);
        }

        Intent resultIntent = new Intent();
        resultIntent.setClassName(mNotificationContext.getPackageName(), mNotificationClass.getName());

        showNotification(builder, resultIntent);
    }

    protected void showNotification(Notification.Builder builder, Intent intent) {
        TaskStackBuilder stackBuilder;
        stackBuilder = TaskStackBuilder.create(mNotificationContext);
        stackBuilder.addParentStack(mNotificationClass);

        stackBuilder.addNextIntent(intent);
        PendingIntent resultPendingIntent =
                stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT);
        builder.setContentIntent(resultPendingIntent);

        Notification notification = builder.build();
        notification.flags = Notification.FLAG_ONLY_ALERT_ONCE | Notification.FLAG_SHOW_LIGHTS
                | Notification.FLAG_AUTO_CANCEL;

        notification.ledOnMS = 300;
        notification.ledOffMS = 1000;
        notification.ledARGB = Color.GREEN;

        NotificationManager mNotificationManager =
                (NotificationManager) mNotificationContext
                        .getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.notify(getNotificationId(), notification);
    }

    protected int getNotificationId() {
        return (int) (System.currentTimeMillis() & 0xfffffff);
    }


}
