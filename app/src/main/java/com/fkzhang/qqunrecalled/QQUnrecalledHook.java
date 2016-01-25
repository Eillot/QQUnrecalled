package com.fkzhang.qqunrecalled;

import android.text.TextUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
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
    private Set<Long> RevokedUids;
    private Class<?> MessageRecordFactory;
    private Object mQQAppInterface;
    private String mSelfUin;
    private Class<?> ContactUtils;

    public QQUnrecalledHook() {
        RevokedUids = new HashSet<>();
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

                        XposedBridge.log(obj.toString());
                        try {
                            setMessageTip(param.thisObject, obj);
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                        }
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
            if (MessageRecordFactory == null)
                MessageRecordFactory = findClass("com.tencent.mobileqq.service.message.MessageRecordFactory", loader);

            if (ContactUtils == null) {
                ContactUtils = findClass("com.tencent.mobileqq.utils.ContactUtils", loader);
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

    }

    private void setMessageTip(Object QQMessageFacade, Object revokeMsgInfo) {
        XposedUtil.inspectFields(revokeMsgInfo);
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
        msg += "尝试撤回一条消息 （已阻止）";
        List tips = createMessageTip(friendUin, senderUin, msgUid, shmsgseq, time + 1,
                msg, istroop);
        if (tips == null || tips.isEmpty())
            return;

        callMethod(QQMessageFacade, "a", tips, mSelfUin);
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
        String nickname = (String) callStaticMethod(ContactUtils, "a", mQQAppInterface, senderUin,
                friendUin, 2, 0);
        if (TextUtils.isEmpty(nickname)) {
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

}
