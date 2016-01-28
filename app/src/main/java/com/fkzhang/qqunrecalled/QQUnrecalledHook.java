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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static com.fkzhang.qqunrecalled.ReflectionUtil.getMethod;
import static com.fkzhang.qqunrecalled.ReflectionUtil.getObjectField;
import static com.fkzhang.qqunrecalled.ReflectionUtil.getStaticMethod;
import static com.fkzhang.qqunrecalled.ReflectionUtil.invokeMethod;
import static com.fkzhang.qqunrecalled.ReflectionUtil.invokeStaticMethod;
import static com.fkzhang.qqunrecalled.ReflectionUtil.isCallingFrom;
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
    private Class<?> MessageRecordFactory;
    private Object mQQAppInterface;
    private String mSelfUin;
    private Class<?> ContactUtils;
    private Class<?> MessageRecord;
    private Context mNotificationContext;
    private Class<?> mNotificationClass;
    private Method mMessageGetter;
    private Object mQQMessageFacade;
    private Map mTroopMap;
    private Method mGetTroopInfo;
    private Object mTroopManager;

    public QQUnrecalledHook() {
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

                        ArrayList list = (ArrayList) param.args[0];
                        if (list == null || list.isEmpty())
                            return;

                        Object obj = list.get(0);

                        initObjects(param.thisObject, loader);

                        try {
                            setMessageTip(obj);
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                        }

                    }
                });
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

            if (mMessageGetter == null) {
                mMessageGetter = getMethod(thisObject, "a", List.class, String.class, int.class,
                        long.class, long.class);
            }
            if (mQQMessageFacade == null) {
                mQQMessageFacade = thisObject;
            }
            if (MessageRecord == null) {
                MessageRecord = findClass("com.tencent.mobileqq.data.MessageRecord", loader);
            }
            if (mTroopMap == null) {
                Class<?> TroopAssistantManagerClass =
                        findClass("com.tencent.mobileqq.managers.TroopAssistantManager", loader);
                mTroopMap = (Map) getObjectField(invokeStaticMethod(getStaticMethod(
                        TroopAssistantManagerClass, "a", TroopAssistantManagerClass)), "a",
                        Map.class);
            }
            if (mGetTroopInfo == null && mQQAppInterface != null) {
                mTroopManager = callMethod(mQQAppInterface, "getManager", 51);
                mGetTroopInfo = getMethod(mTroopManager, "a", "com.tencent.mobileqq.data.TroopMemberInfo",
                        String.class, String.class);
            }

        } catch (Throwable t) {
            XposedBridge.log(t);
        }

    }

    private void setMessageTip(Object revokeMsgInfo) {
        long time = (long) getObjectField(revokeMsgInfo, "c", long.class);


        String friendUin = (String) getObjectField(revokeMsgInfo, "a", String.class);
        String senderUin = (String) getObjectField(revokeMsgInfo, "b", String.class);

        if (mSelfUin.equals(senderUin))
            return;

        int istroop = (int) getObjectField(revokeMsgInfo, "a", int.class);
        long msgUid = (long) getObjectField(revokeMsgInfo, "b", long.class);
        long shmsgseq = (long) getObjectField(revokeMsgInfo, "a", long.class);

        String uin = istroop == 0 ? senderUin : friendUin;

        long id = getMessageId(uin, istroop, shmsgseq, msgUid);

        String msg = istroop == 0 ? "对方" : getTroopName(friendUin, senderUin);

        mSettings.reload();
        if (id != -1) {
            if (isCallingFrom("C2CMessageProcessor"))
                return;

            msg += " " + mSettings.getString("qq_recalled", "尝试撤回一条消息 （已阻止)");

            String message = getMessage(uin, istroop, shmsgseq, msgUid);

            if (mSettings.getBoolean("show_content", false)) {
                if (!TextUtils.isEmpty(message)) {
                    msg += ": " + message;
                }
            }

            showMessageTip(friendUin, senderUin, msgUid, shmsgseq, time, msg, istroop);

            if (!mSettings.getBoolean("enable_recall_notification", true) ||
                    (!mSettings.getBoolean("enable_troopassistant_recall_notification", false)
                            && istroop == 1 && isInTroopAssistant(uin)))
                return;

            showMessageNotification(istroop == 0 ? null : friendUin, senderUin, message);
        } else {
            msg += " " + mSettings.getString("qq_recalled_offline", "撤回了一条消息 (离线时)");
            showMessageTip(friendUin, senderUin, msgUid, shmsgseq, time, msg, istroop);
        }

    }

    private void showMessageTip(String friendUin, String senderUin, long msgUid, long shmsgseq,
                                long time, String msg, int istroop) {
        if (msgUid != 0) {
            msgUid += new Random().nextInt();
        }
        List tips = createMessageTip(friendUin, senderUin, msgUid, shmsgseq, time + 1, msg, istroop);
        if (tips == null || tips.isEmpty())
            return;

        callMethod(mQQMessageFacade, "a", tips, mSelfUin);
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
            nickname = (String) callStaticMethod(ContactUtils, "c", mQQAppInterface, friendUin, senderUin);
        }
        if (TextUtils.isEmpty(nickname)) {
            nickname = (String) callStaticMethod(ContactUtils, "b", mQQAppInterface, senderUin, true);
        }
        if (TextUtils.isEmpty(nickname)) {
            nickname = senderUin;
        }
        return nickname;
    }

    protected String getTroopName(String friendUin, String senderUin) {
        if (mTroopManager == null || friendUin == null)
            return getFriendName(friendUin, senderUin);

        Object troopMemberInfo = invokeMethod(mGetTroopInfo, mTroopManager, friendUin, senderUin);
        if (troopMemberInfo == null) {
            return getFriendName(friendUin, senderUin);
        }
        String nickname = (String) XposedHelpers.getObjectField(troopMemberInfo, "troopnick");
        if (TextUtils.isEmpty(nickname)) {
            nickname = (String) XposedHelpers.getObjectField(troopMemberInfo, "friendnick");
        }
        if (TextUtils.isEmpty(nickname)) {
            nickname = getFriendName(friendUin, senderUin);
        }
        return nickname;
    }


    protected String getMessage(String uin, int istroop, long shmsgseq, long msgUid) {
        List list = (List) invokeMethod(mMessageGetter, mQQMessageFacade, uin, istroop,
                shmsgseq, msgUid);

        if (list == null || list.isEmpty())
            return null;

        return (String) getObjectField(MessageRecord, list.get(0), "msg");
    }


    protected long getMessageId(String uin, int istroop, long shmsgseq, long msgUid) {
        List list = (List) invokeMethod(mMessageGetter, mQQMessageFacade, uin, istroop,
                shmsgseq, msgUid);

        if (list == null || list.isEmpty())
            return -1;

        return (long) getObjectField(MessageRecord, list.get(0), "msgUid");
    }


    protected void showMessageNotification(String frienduin, String senderuin, String msg) {
        try {
            if (TextUtils.isEmpty(msg))
                return;

            String title = getTroopName(frienduin, senderuin) + " " + mSettings.getString("qq_recalled",
                    "尝试撤回一条消息");
            showTextNotification(title, msg, getAvatar(senderuin));
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

    protected boolean isInTroopAssistant(String uin) {
        return mTroopMap != null && mTroopMap.containsKey(uin);
    }


}
