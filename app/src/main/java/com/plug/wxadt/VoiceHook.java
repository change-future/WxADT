package com.plug.wxadt;

import java.io.File;
import java.lang.reflect.Method;

import org.json.JSONObject;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public class VoiceHook {

    // ── 当前录音会话状态 ────────────────────────────────────────────────────
    private String mCurrentWxid              = null;
    private String mCurrentSilkPath          = null;
    private String mCurrentRecordingFileName = null;

    private String mPendingFileName         = null;
    private int    mPendingOriginalDuration = 0;

    // ── 变声服务配置 ────────────────────────────────────────────────────────
    private String mConvertUrl     = "";
    private String mTargetVoice    = "speaker1";
    private String mInputAudioPath = "";

    // ── 缓存的微信类/方法引用 ───────────────────────────────────────────────
    // 版本候选：最新版优先，旧版兜底
    private static final String[] RECORDER_CLASSES      = {"u11.x0",  "qw0.w0"};
    private static final String[] PATH_RESOLVER_CLASSES = {"ru.p0",   "jt.p0"};
    private static final String[] ACCOUNT_CLASSES       = {"nj5.y",   "n75.p"};
    private static final String[] STORAGE_CLASSES       = {"com.tencent.mm.storage.f9",
                                                            "com.tencent.mm.storage.d8"};
    private static final String[] SERVICE_CONTAINER_CLASSES = {"u11.p0", "qw0.q0"};
    private static final String[] RECORDER_ENGINE_CLASSES   = {"jl.p0",  "vk.p0"};

    // silk 路径方法名候选（参数类型跟随 accountClass 动态解析）
    private static final String[] SILK_PATH_METHODS     = {"Jh", "ga"};
    // 获取 VoiceService 的静态方法名候选
    private static final String[] VOICE_SERVICE_METHODS = {"Fh", "ba"};

    private Class<?> mRecorderClass;         // w0/x0  建档 + 打包
    private Class<?> mPathResolverClass;     // p0/p0  路径服务
    private Class<?> mAccountClass;          // n75.p/nj5.y  账号上下文
    private Class<?> mVoiceStorageClass;     // d8/f9  语音 DB 实体
    private Class<?> mServiceContainerClass; // q0/p0  服务容器
    private Method   mGetSilkPathMethod;     // ga/Jh()
    private Method   mPackVoiceMethod;       // w0/x0.t()
    private Method   mGetVoiceServiceMethod; // q0/p0.ba()/Fh()

    // ================================================================== 安装

    public void install(ClassLoader classLoader) {
        if (!resolveClasses(classLoader)) {
            WxLog.i("❌ VoiceHook 初始化失败，变声功能不可用");
            return;
        }
        hookRecordStart();
        hookRecordPack();
        hookVoiceStop(classLoader);
    }

    /**
     * 多版本自适应类/方法解析，只执行一次。
     * 按候选列表顺序尝试，记录成功使用的版本以便排查。
     */
    private boolean resolveClasses(ClassLoader classLoader) {
        try {
            mRecorderClass         = findClass(classLoader, RECORDER_CLASSES);
            mPathResolverClass     = findClass(classLoader, PATH_RESOLVER_CLASSES);
            mAccountClass          = findClass(classLoader, ACCOUNT_CLASSES);
            mServiceContainerClass = findClass(classLoader, SERVICE_CONTAINER_CLASSES);

            if (mRecorderClass == null || mPathResolverClass == null
                    || mAccountClass == null || mServiceContainerClass == null) {
                WxLog.i("resolveClasses: 部分类未找到");
                return false;
            }

            // 用 t() 方法签名来联动确定正确的 storage 类版本：
            // 两个版本都可能同时存在于同一个 APK，必须以实际方法签名为准
            for (String storageName : STORAGE_CLASSES) {
                try {
                    Class<?> candidate = XposedHelpers.findClass(storageName, classLoader);
                    Method m = XposedHelpers.findMethodExact(
                            mRecorderClass, "t",
                            String.class, int.class, int.class, candidate);
                    mVoiceStorageClass = candidate;
                    mPackVoiceMethod   = m;
                    WxLog.i("mPackVoiceMethod: " + mRecorderClass.getSimpleName()
                            + ".t(" + storageName + ")");
                    break;
                } catch (Throwable ignored) {}
            }

            // silk 路径方法：方法名 + 参数类型同步跨版本
            mGetSilkPathMethod = findMethodExact(mPathResolverClass, SILK_PATH_METHODS,
                    mAccountClass, String.class, boolean.class);

            // 服务容器的静态方法 ba()/Fh()，无参数
            mGetVoiceServiceMethod = findNoArgStaticMethod(mServiceContainerClass,
                    VOICE_SERVICE_METHODS);

            if (mVoiceStorageClass == null || mPackVoiceMethod == null
                    || mGetSilkPathMethod == null || mGetVoiceServiceMethod == null) {
                WxLog.i("resolveClasses: 部分方法未找到");
                return false;
            }

            WxLog.i("WeChat 类解析完成: recorder=" + mRecorderClass.getName()
                    + ", silkPath=" + mGetSilkPathMethod.getName()
                    + ", voiceSvc=" + mGetVoiceServiceMethod.getName());
            return true;
        } catch (Throwable t) {
            WxLog.e("resolveClasses 异常", t);
            return false;
        }
    }

    // ================================================================== 配置

    public void setConvertConfig(String url, String targetVoice) {
        mConvertUrl  = url;
        mTargetVoice = targetVoice;
    }

    public void setInputAudio(String path) {
        mInputAudioPath = path;
    }

    // ================================================================== Hook 安装

    /**
     * Hook w0/x0.g(String toUser, String str2)
     * 录音开始时获取 wxid + 计算 silk 落盘路径。
     */
    private void hookRecordStart() {
        try {
            XposedHelpers.findAndHookMethod(mRecorderClass, "g",
                    String.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!GeekConfig.VOICE_CHANGER) return;
                            String toUser   = (String) param.args[0];
                            String fileName = (String) param.getResult();
                            if (toUser == null || toUser.isEmpty() || fileName == null) return;

                            mCurrentWxid = toUser;
                            mCurrentRecordingFileName = fileName;

                            try {
                                Object pathResolver = XposedHelpers.newInstance(mPathResolverClass);
                                mCurrentSilkPath = (String) mGetSilkPathMethod.invoke(
                                        pathResolver, null, fileName, false);
                            } catch (Throwable e) {
                                WxLog.e("路径计算失败", e);
                            }

                            String type = toUser.contains("@chatroom") ? "[群聊]"
                                        : toUser.startsWith("gh_")     ? "[公众号]" : "[单聊]";
                            WxLog.i("捕获 wxid: " + toUser + " " + type
                                    + "  path=" + mCurrentSilkPath);
                        }
                    });
            WxLog.i("w0.g 录音开始 Hook 已安装");
        } catch (Throwable t) {
            WxLog.e("hookRecordStart 安装失败", t);
        }
    }

    /**
     * Hook w0/x0.t(String fileName, int duration, int, d8/f9)
     * 按 fileName 拦截当次录音的封包，取消微信自动发送。
     */
    private void hookRecordPack() {
        try {
            XposedHelpers.findAndHookMethod(mRecorderClass, "t",
                    String.class, int.class, int.class, mVoiceStorageClass,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!GeekConfig.VOICE_CHANGER) return;
                            String callFileName = (String) param.args[0];
                            if (callFileName == null
                                    || !callFileName.equals(mCurrentRecordingFileName)) return;

                            mPendingFileName         = callFileName;
                            mPendingOriginalDuration = (int) param.args[1];
                            mCurrentRecordingFileName = null;
                            param.setResult(false);
                            WxLog.i("拦截 w0.t，fileName=" + callFileName
                                    + "，原始时长=" + mPendingOriginalDuration + "ms");
                        }
                    });
            WxLog.i("w0.t 封包拦截 Hook 已安装");
        } catch (Throwable t) {
            WxLog.e("hookRecordPack 安装失败", t);
        }
    }

    /**
     * Hook vk/jl.p0.stop()
     * 松开录音按钮后 silk 落盘，轮询确认再上传变声，变声完成后补发封包。
     */
    private void hookVoiceStop(ClassLoader classLoader) {
        try {
            Class<?> recorderEngineClass = findClass(classLoader, RECORDER_ENGINE_CLASSES);
            if (recorderEngineClass == null) {
                WxLog.i("hookVoiceStop: 未找到录音引擎类");
                return;
            }

            // 8.0.72 引入了协程异步发送路径（m85230m()=true 时跳过 g()/t()）。
            // 强制 m() 返回 false，始终走直连路径：g() 建档 → t() 封包，使已有 Hook 生效。
            try {
                XposedHelpers.findAndHookMethod(recorderEngineClass, "m", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(false);
                    }
                });
                WxLog.i("录音引擎 m() 已拦截，强制直连发送路径");
            } catch (Throwable t) {
                WxLog.i("录音引擎 m() 不存在或无需拦截（" + t.getMessage() + "）");
            }

            XposedHelpers.findAndHookMethod(recorderEngineClass, "stop", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!GeekConfig.VOICE_CHANGER) return;
                    if (mCurrentSilkPath == null || mCurrentSilkPath.isEmpty()) {
                        WxLog.i("stop 触发但未记录录音路径，跳过变声");
                        return;
                    }
                    File recordedFile = new File(mCurrentSilkPath);
                    if (!recordedFile.exists()) {
                        WxLog.i("录音文件不存在: " + mCurrentSilkPath);
                        mCurrentSilkPath = null;
                        return;
                    }
                    WxLog.i("松开按钮，等待 silk 落盘...");
                    mCurrentSilkPath = null;

                    final String capturedFileName         = mPendingFileName;
                    final int    capturedOriginalDuration = mPendingOriginalDuration;
                    mPendingFileName         = null;
                    mPendingOriginalDuration = 0;

                    new Thread(() -> {
                        try {
                            long prev = -1;
                            int stableCount = 0;
                            for (int i = 0; i < 30; i++) {
                                Thread.sleep(100);
                                long cur = recordedFile.length();
                                if (cur > 0 && cur == prev) {
                                    if (++stableCount >= 2) break;
                                } else {
                                    stableCount = 0;
                                }
                                prev = cur;
                            }
                            WxLog.i("silk 落盘完成，大小: " + recordedFile.length()
                                    + " bytes，开始变声上传");
                        } catch (InterruptedException ignored) {}

                        final String fUrl     = GeekConfig.resolvedVoiceUrl();
                        final String fSpeaker = GeekConfig.resolvedVoiceSpeaker();
                        HttpClient.convertVoice(fUrl, recordedFile, fSpeaker,
                                recordedFile, json -> {
                            try {
                                JSONObject resp = new JSONObject(json);
                                if (resp.getInt("code") != 200) {
                                    WxLog.i("变声失败，已拦截发送（不降级）: " + json);
                                    return;
                                }
                                if (capturedFileName == null) {
                                    WxLog.i("未捕获到 w0.t 的 fileName，无法补发");
                                    return;
                                }
                                int durationMs = (int) resp.optLong("duration", 0);
                                WxLog.i("变声完成，时长: " + durationMs + " ms，开始补发封包...");
                                dispatchVoice(capturedFileName, durationMs);
                            } catch (Throwable t) {
                                WxLog.e("发送回调异常", t);
                            }
                        });
                    }).start();
                }
            });
            WxLog.i("vk/jl.p0.stop 变声 Hook 已安装");
        } catch (Throwable t) {
            WxLog.e("hookVoiceStop 安装失败", t);
        }
    }

    // ================================================================== 内部工具

    /** 调用 w0.t 封包并触发上传队列，变声成功和降级两条路径共用。 */
    private void dispatchVoice(String fileName, int durationMs) {
        try {
            boolean ok = (boolean) mPackVoiceMethod.invoke(null, fileName, durationMs, 0, null);
            if (ok) {
                Object voiceService = mGetVoiceServiceMethod.invoke(null);
                XposedHelpers.callMethod(voiceService, "e");
                WxLog.i("封包发送完成，fileName=" + fileName);
            } else {
                WxLog.i("w0.t 封包返回失败，fileName=" + fileName);
            }
        } catch (Throwable t) {
            WxLog.e("dispatchVoice 异常", t);
        }
    }

    // ================================================================== 主动发送

    /**
     * 发 AI 语音消息，供 MessageHandler 在收到 Ping 指令时调用。
     * 流程：建档 → 获取目标路径 → 上传源音频做变声 → 回调中封包发送。
     */
    public void sendAiVoice() {
        if (mRecorderClass == null || mGetSilkPathMethod == null) {
            WxLog.i("WeChat 类尚未初始化，请等待进入主界面后重试");
            return;
        }
        if (mCurrentWxid == null || mCurrentWxid.isEmpty()) {
            WxLog.i("尚未捕获到 wxid，请先在目标聊天窗口按一下语音按钮");
            return;
        }
        File inputFile = new File(mInputAudioPath);
        if (!inputFile.exists()) {
            WxLog.i("输入音频不存在: " + mInputAudioPath + "，请先调用 setInputAudio()");
            return;
        }

        try {
            WxLog.i("开始主动发送语音...");

            String fileName = (String) XposedHelpers.callStaticMethod(
                    mRecorderClass, "g", mCurrentWxid, "");
            if (fileName == null) {
                WxLog.i("生成语音记录失败！");
                return;
            }
            WxLog.i("数据库建档成功，文件名: " + fileName);

            Object pathResolver = XposedHelpers.newInstance(mPathResolverClass);
            String fullTargetPath = (String) mGetSilkPathMethod.invoke(
                    pathResolver, null, fileName, false);
            WxLog.i("目标写入路径: " + fullTargetPath);

            File outFile = new File(fullTargetPath);
            File parentDir = outFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            final String aiUrl     = GeekConfig.resolvedVoiceUrl();
            final String aiSpeaker = GeekConfig.resolvedVoiceSpeaker();
            HttpClient.convertVoice(aiUrl, inputFile, aiSpeaker, outFile, json -> {
                try {
                    JSONObject resp = new JSONObject(json);
                    if (resp.getInt("code") != 200) {
                        WxLog.i("convertVoice 失败: " + json);
                        return;
                    }
                    int durationMs = (int) resp.optLong("duration", 0);
                    WxLog.i("变声完成，时长: " + durationMs + " ms，开始封包...");
                    dispatchVoice(fileName, durationMs);
                } catch (Throwable t) {
                    WxLog.e("发送回调异常", t);
                }
            });
        } catch (Throwable t) {
            WxLog.e("sendAiVoice 异常", t);
        }
    }

    // ================================================================== 反射工具方法

    /** 按候选名称列表顺序尝试加载类，第一个成功的返回，全部失败返回 null。 */
    private static Class<?> findClass(ClassLoader loader, String... names) {
        for (String name : names) {
            try {
                Class<?> clz = XposedHelpers.findClass(name, loader);
                WxLog.i("findClass: 使用 " + name);
                return clz;
            } catch (XposedHelpers.ClassNotFoundError ignored) {}
        }
        WxLog.i("findClass: 所有候选均未找到: " + java.util.Arrays.toString(names));
        return null;
    }

    /** 按候选方法名列表，用给定参数类型尝试 findMethodExact，第一个成功的返回，全部失败返回 null。 */
    private static Method findMethodExact(Class<?> clz, String[] names, Class<?>... paramTypes) {
        for (String name : names) {
            try {
                Method m = XposedHelpers.findMethodExact(clz, name, paramTypes);
                WxLog.i("findMethodExact: " + clz.getSimpleName() + "." + name + "() 已找到");
                return m;
            } catch (Throwable ignored) {}
        }
        WxLog.i("findMethodExact: 所有候选均未找到: " + java.util.Arrays.toString(names)
                + " in " + clz.getName());
        return null;
    }

    /** 寻找无参静态方法，按候选方法名列表尝试，全部失败返回 null。 */
    private static Method findNoArgStaticMethod(Class<?> clz, String[] names) {
        for (String name : names) {
            try {
                Method m = XposedHelpers.findMethodExact(clz, name);
                WxLog.i("findNoArgStaticMethod: " + clz.getSimpleName() + "." + name + "() 已找到");
                return m;
            } catch (Throwable ignored) {}
        }
        WxLog.i("findNoArgStaticMethod: 所有候选均未找到: " + java.util.Arrays.toString(names));
        return null;
    }
}
