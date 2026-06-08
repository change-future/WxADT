package com.plug.wxadt;

import android.content.ContentValues;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public class MessageHandler {

    private final VoiceHook    mVoiceHook;
    private final TransferHook mTransferHook;
    private final HongBaoHook  mHongBaoHook;
    private ClassLoader mClassLoader;
    private final HashSet<String> mProcessedMsgIds = new HashSet<>();
    private volatile Object mMessageDb = null;
    // 拦截到媒体撤回时记录时间戳，5 秒内保护媒体文件和索引表不被删除
    private volatile long mLastRevokeTime = 0;
    private AutoReplyManager mAutoReplyManager;
    // 语音文件路径 → 发送者 wxid 的临时映射，用于将语音识别结果路由到 AutoReplyManager
    private final Map<String, String> mVoicePathToTalker = new ConcurrentHashMap<>();

    private static final String[] SEND_MSG_FACTORY_CLASSES = {"u01.r1", "qv0.u1"};
    private Class<?> mSendMsgFactoryClass;

    public MessageHandler(VoiceHook voiceHook, TransferHook transferHook, HongBaoHook hongBaoHook) {
        mVoiceHook    = voiceHook;
        mTransferHook = transferHook;
        mHongBaoHook  = hongBaoHook;
    }

    public void install(ClassLoader classLoader) {
        mAutoReplyManager = new AutoReplyManager(
                (t, r) -> sendReply(t, r),
                this::loadHistoryFromDb);
        mClassLoader = classLoader;
        resolveClasses(classLoader);
        hookInsert(classLoader);
        hookUpdate(classLoader);
        hookDelete(classLoader);
        hookRevokeFileDelete(classLoader);
    }

    private void resolveClasses(ClassLoader classLoader) {
        for (String name : SEND_MSG_FACTORY_CLASSES) {
            try {
                mSendMsgFactoryClass = XposedHelpers.findClass(name, classLoader);
                WxLog.i("sendReply 工厂类: " + name);
                break;
            } catch (XposedHelpers.ClassNotFoundError ignored) {}
        }
        if (mSendMsgFactoryClass == null) {
            WxLog.i("sendReply 工厂类未找到，文字回复功能不可用");
        }
    }

    // ================================================================== INSERT

    private void hookInsert(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.tencent.wcdb.database.SQLiteDatabase",
                    classLoader,
                    "insertWithOnConflict",
                    String.class, String.class, ContentValues.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String table = (String) param.args[0];
                            ContentValues values = (ContentValues) param.args[2];
                            if ("message".equals(table) && values != null) {
                                markOldStyleRevoke(values);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String table = (String) param.args[0];
                            ContentValues values = (ContentValues) param.args[2];
                            if ("message".equals(table)) {
                                mMessageDb = param.thisObject;
                            }
                            WxLog.dbInsert(table, values != null ? values.toString() : "null");
                            processInsert(table, values);
                        }
                    }
            );
            WxLog.i("数据库 INSERT 监听已部署");
        } catch (Throwable t) {
            WxLog.e("hookInsert 失败", t);
        }

        for (String[] sig : new String[][]{
                {"insert",        "java.lang.String", "java.lang.String", "android.content.ContentValues"},
                {"insertOrThrow", "java.lang.String", "java.lang.String", "android.content.ContentValues"},
                {"replace",       "java.lang.String", "java.lang.String", "android.content.ContentValues"},
        }) {
            final String methodName = sig[0];
            try {
                XposedHelpers.findAndHookMethod(
                        "com.tencent.wcdb.database.SQLiteDatabase",
                        classLoader,
                        methodName,
                        String.class, String.class, ContentValues.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                String table = (String) param.args[0];
                                ContentValues values = (ContentValues) param.args[2];
                                if ("message".equals(table) && values != null) {
                                    WxLog.i("DB 备用路径 " + methodName + "() message table");
                                    markOldStyleRevoke(values);
                                }
                            }
                        }
                );
                WxLog.i("数据库 " + methodName + "() 备用监听已部署");
            } catch (Throwable t) {
                WxLog.i("数据库 " + methodName + "() 不可用: " + t.getMessage());
            }
        }
    }

    /**
     * 处理旧版微信撤回通知（部分版本以 type=10002 INSERT 实现）：
     * 在通知文本中追加标记，让用户知道原消息已被保留。
     */
    private void markOldStyleRevoke(ContentValues values) {
        Integer type    = values.getAsInteger("type");
        Integer isSend  = values.getAsInteger("isSend");
        String  content = values.getAsString("content");

        if (type != null && type == 10002) {
            WxLog.i("防撤回DIAG: type=10002 isSend=" + isSend + " content[:120]="
                    + (content != null ? content.substring(0, Math.min(content.length(), 120)) : "null"));
        }

        if (type == null || type != 10002 || isSend == null || isSend != 0) return;
        if (content == null || !content.contains("revokemsg")) return;

        String marked = content.replace("</replacemsg>", " [↑已保留]</replacemsg>");
        if (!marked.equals(content)) {
            values.put("content", marked);
        }
    }

    // ================================================================== UPDATE

    private void hookUpdate(ClassLoader classLoader) {
        // 防撤回策略：只在 DB 层拦截 UPDATE，不阻断 az0.u.f()。
        // 阻断 az0.u.f() 会导致 RevokeMsgEvent 不触发，聊天列表适配器状态混乱（消息不可见 bug）。
        //
        // 文字撤回（origType=1 → newType=0x10002710）：
        //   还原原始 type，在 content 追加保留提示，让 UPDATE 正常执行。
        //   RevokeMsgEvent 正常触发，适配器从 DB 重新渲染，展示保留内容。
        //
        // 媒体撤回（origType=3/34/43/62 → newType=0x11002712）：
        //   直接阻断整个 UPDATE，保持原始媒体行不变。
        //   同时设置 mLastRevokeTime，5 秒内保护媒体文件和索引表。
        XC_MethodHook updateHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String table = (String) param.args[0];
                ContentValues values = (ContentValues) param.args[1];
                if (!"message".equals(table) || values == null) return;

                Integer newType = values.getAsInteger("type");
                String whereClause = (String) param.args[2];
                String[] whereArgs = param.args.length > 3 ? (String[]) param.args[3] : null;

                if (newType != null) {
                    WxLog.i("DB UPDATE message: newType=" + newType
                            + "(0x" + Integer.toHexString(newType) + ") where=" + whereClause
                            + " " + (whereArgs != null ? java.util.Arrays.toString(whereArgs) : "null"));
                }

                if (newType == null) return;
                boolean isRevoke = (newType == 10002) || ((newType & 0x10000000) != 0);
                if (!isRevoke) return;

                if (whereArgs == null || whereArgs.length == 0) return;

                try {
                    Object cursor = XposedHelpers.callMethod(param.thisObject, "rawQuery",
                            "SELECT type, content, isSend FROM message WHERE " + whereClause,
                            whereArgs);
                    if (cursor == null) return;
                    try {
                        boolean moved = (Boolean) XposedHelpers.callMethod(cursor, "moveToFirst");
                        if (!moved) return;

                        int    origType    = (Integer) XposedHelpers.callMethod(cursor, "getInt",    0);
                        String origContent = (String)  XposedHelpers.callMethod(cursor, "getString", 1);
                        int    origIsSend  = (Integer) XposedHelpers.callMethod(cursor, "getInt",    2);

                        if (origIsSend == 1) {
                            WxLog.i("防撤回: 自己撤回，放行 where=" + whereClause);
                            return;
                        }

                        if (!GeekConfig.ANTI_REVOKE) {
                            WxLog.i("防撤回: 功能已关闭，放行");
                            return;
                        }

                        if (origType == 1) {
                            // 文字消息：还原 type + 追加保留提示，UPDATE 正常执行，DB 写入原始数据
                            String preserved = (origContent != null ? origContent : "")
                                    + "\n\n━━━━━━━━━━━━"
                                    + "\n🛡 对方试图撤回这条消息，已为您保留";
                            values.put("type", origType);
                            values.put("content", preserved);
                            values.remove("lvbuffer");
                            WxLog.i("防撤回: 文字消息已保留 origType=" + origType);
                            showToast("🛡 防撤回：已为您保留一条文字消息");
                        } else {
                            // 媒体消息：直接阻断 UPDATE，保持原始行不变
                            mLastRevokeTime = System.currentTimeMillis();
                            param.setResult(0);
                            String label = mediaLabel(origType);
                            WxLog.i("防撤回: 媒体消息已保留 origType=" + origType + " (" + label + ")");
                            showToast("🛡 防撤回：已为您保留一条 [" + label + "]");
                        }
                    } finally {
                        XposedHelpers.callMethod(cursor, "close");
                    }
                } catch (Throwable e) {
                    WxLog.i("防撤回: rawQuery 异常，回退阻断: " + e.getMessage());
                    param.setResult(0);
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod(
                    "com.tencent.wcdb.database.SQLiteDatabase", classLoader,
                    "updateWithOnConflict",
                    String.class, ContentValues.class, String.class, String[].class, int.class,
                    updateHook);
            WxLog.i("数据库 UPDATE 监听已部署");
        } catch (Throwable t) {
            WxLog.e("hookUpdate 失败", t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.tencent.wcdb.database.SQLiteDatabase", classLoader,
                    "update",
                    String.class, ContentValues.class, String.class, String[].class,
                    updateHook);
            WxLog.i("数据库 update() 备用监听已部署");
        } catch (Throwable t) {
            WxLog.i("数据库 update() 备用不可用: " + t.getMessage());
        }
    }

    private static String mediaLabel(int type) {
        switch (type) {
            case 3:  return "图片";   // 图片
            case 34: return "语音";   // 语音
            case 43: return "视频";   // 视频
            case 62: return "短视频"; // 短视频
            case 47: return "表情";   // 表情
            default: return "消息(type=" + type + ")";
        }
    }

    // ================================================================== DELETE

    private void hookDelete(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.tencent.wcdb.database.SQLiteDatabase",
                    classLoader,
                    "delete",
                    String.class, String.class, String[].class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String table = (String) param.args[0];
                            String whereClause = (String) param.args[1];
                            String[] whereArgs = (String[]) param.args[2];
                            String whereStr = whereArgs != null
                                    ? java.util.Arrays.toString(whereArgs) : "null";

                            // 媒体撤回拦截后 5 秒内保护媒体索引表不被删除
                            if (("ImgInfo2".equals(table)
                                    || "voiceinfo".equals(table)
                                    || "videoinfo2".equals(table))
                                    && System.currentTimeMillis() - mLastRevokeTime < 5000) {
                                WxLog.i("防撤回: 阻止删除媒体索引 " + table + " args=" + whereStr);
                                param.setResult(0);
                                return;
                            }

                            if ("message".equals(table)) {
                                WxLog.i("DB DELETE message: where=" + whereClause + " args=" + whereStr);
                            }
                        }
                    }
            );
            WxLog.i("DELETE 监听已部署（含媒体表保护）");
        } catch (Throwable t) {
            WxLog.e("hookDelete 失败", t);
        }
    }

    // ================================================================== 消息路由

    private void processInsert(String table, ContentValues values) {
        if (table == null || values == null) return;
        if ("voiceinfo".equals(table)) {
            onVoiceInfoInsert(values);
        } else if ("VoiceTransText".equalsIgnoreCase(table)) {
            onVoiceTransTextInsert(values);
        } else if ("message".equals(table)) {
            onMessageInsert(values);
        }
    }

    private void onVoiceInfoInsert(ContentValues values) {
        String imgPath    = values.getAsString("FileName");
        Long   msgLocalId = values.getAsLong("MsgLocalId");
        String talker     = values.getAsString("User");
        if (imgPath == null || msgLocalId == null || talker == null) return;

        // 仅在 AI 自动回复开启时才需要识别语音（识别结果直接喂给 AI）
        if (!GeekConfig.AUTO_REPLY) return;

        // 缓存路径→发送者，供 onVoiceTransTextInsert 路由使用
        mVoicePathToTalker.put(imgPath, talker);

        new Thread(() -> {
            try {
                Thread.sleep(3000);
                WxLog.i("准备触发底层语音翻译... talker=" + talker);
                voice2Text(imgPath, msgLocalId, talker);
            } catch (Exception e) {
                WxLog.e("voiceinfo 处理线程异常", e);
            }
        }).start();
    }

    private void onVoiceTransTextInsert(ContentValues values) {
        String translatedText = values.getAsString("content");
        String imgPath        = values.getAsString("cmsgId");
        if (translatedText == null || translatedText.isEmpty()) return;

        // 取出并移除缓存（一次性使用）
        String talker = imgPath != null ? mVoicePathToTalker.remove(imgPath) : null;
        WxLog.i("语音识别结果 [" + imgPath + "] talker=" + talker + " 内容: " + translatedText);

        // AUTO_REPLY 开启时将识别文字作为用户消息交给 AI 处理
        if (GeekConfig.AUTO_REPLY && talker != null) {
            mAutoReplyManager.onIncomingMessage(talker, "[语音]" + translatedText);
        }
    }

    private void onMessageInsert(ContentValues values) {
        Integer type   = values.getAsInteger("type");
        Integer isSend = values.getAsInteger("isSend");
        String  talker = values.getAsString("talker");
        WxLog.i("DB INSERT message: type=" + type + " isSend=" + isSend + " talker=" + talker);

        String msgSvrId = values.getAsString("msgSvrId");
        if (msgSvrId != null) {
            synchronized (mProcessedMsgIds) {
                if (mProcessedMsgIds.contains(msgSvrId)) return;
                mProcessedMsgIds.add(msgSvrId);
            }
        }

        // 转账消息（type=49，WeChat 高位可能附加标志位，只比较低 8 位）
        if (type != null && (type & 0xFF) == 49 && isSend != null && isSend == 0 && talker != null) {
            String xml = values.getAsString("content");
            if (xml != null && !xml.isEmpty()) {
                String transferId    = extractXmlTag(xml, "transferid");
                String transactionId = extractXmlTag(xml, "transcationid");
                if (transactionId == null) transactionId = extractXmlTag(xml, "transactionid");
                String invalidTimeStr = extractXmlTag(xml, "invalidtime");
                int invalidTime = 0;
                if (invalidTimeStr != null) {
                    try { invalidTime = Integer.parseInt(invalidTimeStr.trim()); }
                    catch (NumberFormatException ignored) {}
                }
                WxLog.i("TransferHook: 转账入库 talker=" + talker
                        + " transferId=" + transferId
                        + " transactionId=" + transactionId
                        + " invalidTime=" + invalidTime);
                // 只有真正的转账消息才触发（红包没有 transferId/transactionId）
                if (mTransferHook != null && (transferId != null || transactionId != null)) {
                    mTransferHook.onTransferReceived(talker, transferId, transactionId, invalidTime);
                }
            }
        }

        // 红包消息（type=49，appmsg <type>2001</type>）
        if (type != null && (type & 0xFF) == 49 && isSend != null && isSend == 0 && talker != null) {
            String xml = values.getAsString("content");
            if (xml != null && !xml.isEmpty()) {
                String appmsgType = extractXmlTag(xml, "type");
                if ("2001".equals(appmsgType)) {
                    String nativeUrl    = extractXmlTag(xml, "nativeurl");
                    String sendUserName = extractXmlTag(xml, "sendusername");
                    String sendId       = extractXmlTag(xml, "sendid");
                    // sendusername/sendid 可能不在单独 tag 里，而是嵌入 nativeurl 查询参数
                    if (nativeUrl != null) {
                        if (sendUserName == null)
                            sendUserName = extractQueryParam(nativeUrl, "sendusername");
                        if (sendId == null)
                            sendId = extractQueryParam(nativeUrl, "sendid");
                    }
                    String invalidTimeStr = extractXmlTag(xml, "invalidtime");
                    int hbInvalidTime = 0;
                    if (invalidTimeStr != null) {
                        try { hbInvalidTime = Integer.parseInt(invalidTimeStr.trim()); }
                        catch (NumberFormatException ignored) {}
                    }
                    WxLog.i("HongBaoHook: 红包入库 talker=" + talker
                            + " sendId=" + sendId + " sendUserName=" + sendUserName
                            + " invalidTime=" + hbInvalidTime);
                    if (mHongBaoHook != null && nativeUrl != null && sendUserName != null) {
                        mHongBaoHook.onHongBaoReceived(
                                talker, nativeUrl, sendId, sendUserName, hbInvalidTime);
                    }
                }
            }
        }

        // AI 自动回复：仅处理普通文字消息（type=1，低 8 位判断）
        if (!GeekConfig.AUTO_REPLY || type == null || (type & 0xFF) != 1 || talker == null) return;
        String content = values.getAsString("content");
        if (content == null || content.isEmpty()) return;

        if (isSend != null && isSend == 0) {
            mAutoReplyManager.onIncomingMessage(talker, content);
        } else if (isSend != null && isSend == 1) {
            mAutoReplyManager.onOutgoingMessage(talker, content);
        }
    }

    private static String extractQueryParam(String url, String param) {
        String key = param + "=";
        int s = url.indexOf(key);
        if (s < 0) return null;
        s += key.length();
        int e = url.indexOf('&', s);
        return e >= 0 ? url.substring(s, e) : url.substring(s);
    }

    /**
     * 从 XML 字符串中提取指定标签的文本值，支持 CDATA 和普通格式。
     * 用于解析微信 type=49 转账消息 content 字段。
     */
    private static String extractXmlTag(String xml, String tag) {
        // CDATA 格式：<tag><![CDATA[value]]></tag>
        String cdataOpen  = "<" + tag + "><![CDATA[";
        String cdataClose = "]]></" + tag + ">";
        int s = xml.indexOf(cdataOpen);
        if (s >= 0) {
            int e = xml.indexOf(cdataClose, s + cdataOpen.length());
            if (e >= 0) return xml.substring(s + cdataOpen.length(), e).trim();
        }
        // 普通格式：<tag>value</tag>
        String open  = "<" + tag + ">";
        String close = "</" + tag + ">";
        s = xml.indexOf(open);
        if (s >= 0) {
            int e = xml.indexOf(close, s + open.length());
            if (e >= 0) return xml.substring(s + open.length(), e).trim();
        }
        return null;
    }

    // ================================================================== 语音翻译

    private void voice2Text(String imgPath, Long msgLocalId, String talker) {
        try {
            Class<?> eventClass = XposedHelpers.findClass(
                    "com.tencent.mm.autogen.events.ExtTranslateVoiceEvent", mClassLoader);
            Object translateEvent = XposedHelpers.newInstance(eventClass);
            Object eventData      = XposedHelpers.getObjectField(translateEvent, "g");

            XposedHelpers.setIntField(eventData, "c", 0);
            XposedHelpers.setObjectField(eventData, "b", String.valueOf(msgLocalId));
            XposedHelpers.setObjectField(eventData, "a", imgPath);
            XposedHelpers.setObjectField(eventData, "g", talker);
            XposedHelpers.setIntField(eventData, "f", 1);
            XposedHelpers.setIntField(eventData, "d", 1);

            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    XposedHelpers.callMethod(translateEvent, "m53144e");
                } catch (NoSuchMethodError error) {
                    XposedHelpers.callMethod(translateEvent, "e");
                }
                WxLog.i("ExtTranslateVoiceEvent 已触发");
            });
        } catch (Throwable t) {
            WxLog.e("voice2Text 失败", t);
        }
    }

    // ================================================================== 文字回复

    private void sendReply(String talker, String replyText) {
        if (mSendMsgFactoryClass == null) {
            WxLog.i("sendReply 失败: 工厂类未初始化");
            return;
        }
        try {
            Object builder = XposedHelpers.callStaticMethod(mSendMsgFactoryClass, "a", talker);
            if (builder == null) {
                WxLog.i("sendReply 失败: builder 为 null");
                return;
            }
            XposedHelpers.setIntField(builder, "e", 1);
            XposedHelpers.setIntField(builder, "f", 0);
            XposedHelpers.setIntField(builder, "i", 5);
            XposedHelpers.callMethod(builder, "e", replyText);
            Object task = XposedHelpers.callMethod(builder, "a");
            XposedHelpers.callMethod(task, "a");
            WxLog.i("文字回复已发送 -> " + talker);
        } catch (Throwable t) {
            WxLog.e("sendReply 失败", t);
        }
    }

    // ================================================================== 撤回文件删除拦截

    private void hookRevokeFileDelete(ClassLoader classLoader) {
        // az0.b0（RunnableC6069b0）：旧版撤回代码路径中的文件删除 Runnable
        try {
            Class<?> cls = XposedHelpers.findClass("az0.b0", classLoader);
            XposedHelpers.findAndHookMethod(cls, "run", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    WxLog.i("防撤回: az0.b0.run() 已拦截，跳过文件删除");
                    param.setResult(null);
                }
            });
            WxLog.i("防撤回: az0.b0 文件删除 Runnable 已拦截");
        } catch (Throwable t) {
            WxLog.i("防撤回: az0.b0 hook 失败: " + t.getMessage());
        }

        // az0.r（RunnableC6317r）：8.0.72 版本 az0.u.f() 发布的文件删除 Runnable
        // 仅在最近拦截过媒体撤回时才阻断（mLastRevokeTime 保护窗口）
        try {
            Class<?> cls = XposedHelpers.findClass("az0.r", classLoader);
            XposedHelpers.findAndHookMethod(cls, "run", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (System.currentTimeMillis() - mLastRevokeTime < 5000) {
                        WxLog.i("防撤回: az0.r.run() 已拦截（媒体保护窗口），跳过文件删除");
                        param.setResult(null);
                    }
                }
            });
            WxLog.i("防撤回: az0.r 文件删除 Runnable 已拦截");
        } catch (Throwable t) {
            WxLog.i("防撤回: az0.r hook 失败（" + t.getMessage() + "），az0.b0 作为兜底");
        }
    }

    // ================================================================== 数据库历史加载

    /**
     * 从微信 message 表查询指定联系人最近 limit 条文字消息，按时间正序返回。
     * isSend=0（收到）→ role="user"；isSend=1（发出）→ role="assistant"。
     * mMessageDb 为空时返回空列表（首次 DB 操作前调用时的兜底）。
     */
    List<Map<String, String>> loadHistoryFromDb(String talker, int limit) {
        List<Map<String, String>> result = new ArrayList<>();
        Object db = mMessageDb;
        if (db == null) {
            WxLog.i("DB历史: mMessageDb 尚未初始化，跳过预加载 talker=" + talker);
            return result;
        }
        try {
            // 取最近 limit 条文字消息（type=1），倒序查询后再翻转得到正序
            Object cursor = XposedHelpers.callMethod(db, "rawQuery",
                    "SELECT isSend, content FROM message " +
                    "WHERE talker = ? AND type = 1 AND isSend IN (0,1) " +
                    "AND content IS NOT NULL AND content != '' " +
                    "ORDER BY createTime DESC LIMIT ?",
                    new String[]{talker, String.valueOf(limit)});
            if (cursor == null) return result;
            try {
                boolean hasRow = (Boolean) XposedHelpers.callMethod(cursor, "moveToFirst");
                while (hasRow) {
                    int    isSend  = (Integer) XposedHelpers.callMethod(cursor, "getInt",    0);
                    String content = (String)  XposedHelpers.callMethod(cursor, "getString", 1);
                    if (content != null && !content.isEmpty()) {
                        Map<String, String> msg = new LinkedHashMap<>();
                        msg.put("role",    isSend == 1 ? "assistant" : "user");
                        msg.put("content", content);
                        result.add(msg);
                    }
                    hasRow = (Boolean) XposedHelpers.callMethod(cursor, "moveToNext");
                }
            } finally {
                XposedHelpers.callMethod(cursor, "close");
            }
            // 翻转：DB 返回的是最新→最旧，AI 需要最旧→最新
            Collections.reverse(result);
            WxLog.i("DB历史: talker=" + talker + " 加载 " + result.size() + " 条");
        } catch (Throwable t) {
            WxLog.e("DB历史: 查询失败 talker=" + talker, t);
        }
        return result;
    }

    // ================================================================== 工具

    private void showToast(final String msg) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object app = at.getMethod("currentApplication").invoke(null);
                Toast.makeText((Context) app, msg, Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                WxLog.i("Toast 失败: " + t.getMessage());
            }
        });
    }
}
