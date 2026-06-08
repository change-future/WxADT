package com.plug.wxadt;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 微信转账自动收款 Hook。
 *
 * 收款流程（双轨）：
 *
 *   [主路径] 无界面 CGI（sNetSceneMgr 已缓存后生效）
 *     onTransferReceived
 *       → 1s 延迟
 *       → new n0(transactionId, transferId, 0, "confirm", talker, invalidTime,
 *                "", null, 1, "", null, 0L, talker, "")
 *       → n0.doScene(sNetSceneMgr, null)
 *
 *   [降级路径] 不可见 Activity（首次收款、或 CGI 失败时）
 *     onTransferReceived
 *       → 3s 延迟 → startActivity(RemittanceDetailUI, NO_ANIMATION|NO_HISTORY)
 *       → onCreate hook → moveTaskToBack(true)（用户不可见）
 *       → 3.5s 延迟 → B7()
 *       → 3s 延迟 → finish()
 *
 * 注意：Handler 不能作为字段初始化（LSPosed 注入极早期 Looper 可能未就绪），
 *       改为在方法内按需创建。
 */
public class TransferHook {

    private static final String REMITTANCE_CLASS =
            "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI";
    private static final String N0_CLASS =
            "com.tencent.mm.plugin.remittance.model.n0";

    private static final long HEADLESS_DELAY_MS = 1_000L;  // 无界面路径等待 DB 写完
    private static final long LAUNCH_DELAY_MS   = 3_000L;  // 降级路径启动 Activity 延迟
    private static final long ACCEPT_DELAY_MS   = 3_500L;  // Activity 内 B7() 延迟

    // 无界面路径所需缓存（install() 后可用；sNetSceneMgr 在首个 RemittanceDetailUI 打开后缓存）
    // 强引用：zm5.b0 是微信全局单例，持有不会泄漏
    private static volatile ClassLoader sLoader        = null;
    private static volatile Object      sNetSceneMgr   = null;

    // 保活的 RemittanceDetailUI 实例（WeakRef 即可；系统通过 ActivityManager 维持其存活）
    // zm5.b0 的网络分发队列在 Activity.onDestroy 时才会被关闭；只要 Activity 存活，CGI 就可用
    private static volatile WeakReference<Activity> sRemittanceActivity = null;

    // 持有飞行中的 n0 实例，防止 GC 在网络回调前回收
    private static final java.util.Set<Object> sPendingCgi =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    // ── Hook 安装 ─────────────────────────────────────────────────────────────

    public void install(ClassLoader loader) {
        sLoader = loader;

        // ── 预缓存 zm5.b0：hook 构造函数，微信启动期间实例化时立即捕获 ────────
        try {
            Class<?> b0Class = loader.loadClass("zm5.b0");
            int ctorCount = 0;
            for (java.lang.reflect.Constructor<?> ctor : b0Class.getDeclaredConstructors()) {
                de.robv.android.xposed.XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // 打印构造参数，用于判断能否脱离 Activity 自行构造 zm5.b0
                        StringBuilder sb = new StringBuilder("TransferHook: zm5.b0 构造参数=[");
                        for (int i = 0; i < param.args.length; i++) {
                            if (i > 0) sb.append(", ");
                            Object a = param.args[i];
                            if (a == null) { sb.append("null"); }
                            else {
                                String v = String.valueOf(a);
                                sb.append(a.getClass().getName()).append(":")
                                  .append(v, 0, Math.min(100, v.length()));
                            }
                        }
                        sb.append("]");
                        WxLog.i(sb.toString());
                        if (sNetSceneMgr == null) {
                            sNetSceneMgr = param.thisObject;
                            WxLog.i("TransferHook: zm5.b0 构造完成，NetSceneMgr 已预缓存");
                        }
                    }
                });
                ctorCount++;
            }
            // 同时 hook 所有静态无参工厂方法（单例 getter），确保懒加载也能捕获
            int factoryCount = 0;
            for (java.lang.reflect.Method m : b0Class.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && m.getReturnType().equals(b0Class)
                        && m.getParameterTypes().length == 0) {
                    de.robv.android.xposed.XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (sNetSceneMgr == null && param.getResult() != null) {
                                sNetSceneMgr = param.getResult();
                                WxLog.i("TransferHook: zm5.b0 静态工厂返回，NetSceneMgr 已预缓存");
                            }
                        }
                    });
                    factoryCount++;
                }
            }
            WxLog.i("TransferHook: zm5.b0 预缓存 hook 已安装（构造函数=" + ctorCount
                    + " 静态工厂=" + factoryCount + "）");
        } catch (Throwable t) {
            WxLog.e("TransferHook: zm5.b0 预缓存 hook 失败", t);
        }

        // ── 拦截 n0.onGYNetEnd，记录服务器响应（诊断无界面 CGI 是否被拒绝）─────
        try {
            Class<?> n0Class = loader.loadClass(N0_CLASS);
            Class<?> cls = n0Class;
            int hookCount = 0;
            while (cls != null && !cls.getName().equals("java.lang.Object")) {
                for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                    if ("onGYNetEnd".equals(m.getName())
                            && !java.lang.reflect.Modifier.isAbstract(m.getModifiers())) {
                        try {
                            de.robv.android.xposed.XposedBridge.hookMethod(m, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    sPendingCgi.remove(param.thisObject);
                                    StringBuilder sb = new StringBuilder(
                                            "TransferHook: n0.onGYNetEnd ["
                                            + param.thisObject.getClass().getSimpleName() + "]");
                                    for (int i = 0; i < param.args.length; i++) {
                                        Object a = param.args[i];
                                        sb.append(" arg[").append(i).append("]=");
                                        if (a == null) sb.append("null");
                                        else try { sb.append(String.valueOf(a), 0, Math.min(200, String.valueOf(a).length())); }
                                        catch (Throwable ignored2) { sb.append("?"); }
                                    }
                                    WxLog.i(sb.toString());
                                }
                            });
                            hookCount++;
                        } catch (Throwable ignored) {}
                    }
                }
                try { cls = cls.getSuperclass(); } catch (Throwable ignored) { break; }
            }
            WxLog.i("TransferHook: n0.onGYNetEnd hook 已安装（" + hookCount + " 处）");
        } catch (Throwable t) {
            WxLog.e("TransferHook: n0.onGYNetEnd hook 失败", t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    REMITTANCE_CLASS, loader,
                    "onCreate", android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // 缓存 mNetSceneMgr，供后续无界面收款使用
                            if (sNetSceneMgr == null) {
                                try {
                                    Object mgr = XposedHelpers.getObjectField(
                                            param.thisObject, "mNetSceneMgr");
                                    if (mgr != null) {
                                        sNetSceneMgr = mgr;
                                        WxLog.i("TransferHook: mNetSceneMgr 已缓存 ["
                                                + mgr.getClass().getName() + "]");
                                    }
                                } catch (Throwable t) {
                                    WxLog.e("TransferHook: 缓存 mNetSceneMgr 失败", t);
                                }
                            }

                            if (!GeekConfig.AUTO_ACCEPT_TRANSFER) return;

                            Activity act = (Activity) param.thisObject;

                            // 读取启动时传入的前台标志（launchRemittanceUI 注入）
                            boolean fromForeground = act.getIntent()
                                    .getBooleanExtra("wxadt_from_foreground", false);

                            // 窗口设为完全透明 + 不捕获触摸，对用户完全不可见
                            try {
                                android.view.Window w = act.getWindow();
                                android.view.WindowManager.LayoutParams lp = w.getAttributes();
                                lp.alpha    = 0f;
                                lp.dimAmount = 0f;
                                w.setAttributes(lp);
                                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                            } catch (Throwable ignored) {}

                            sRemittanceActivity = new WeakReference<>(act);

                            if (!fromForeground) {
                                // 微信本在后台：将任务压后台，Activity 保活以维持 zm5.b0 可用
                                try { act.moveTaskToBack(true); } catch (Throwable ignored) {}
                                WxLog.i("TransferHook: 降级路径（后台）— 收款页压后台保活，"
                                        + ACCEPT_DELAY_MS + "ms 后 B7()");
                            } else {
                                // 微信本在前台：不压后台，用户无感知；B7() 后 finish() 恢复原界面
                                WxLog.i("TransferHook: 降级路径（前台）— 收款页透明启动，"
                                        + ACCEPT_DELAY_MS + "ms 后 B7()");
                            }

                            final WeakReference<Activity> actRef = new WeakReference<>(act);
                            final boolean needFinishAfterB7 = fromForeground;
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                Activity a = actRef.get();
                                if (a == null || a.isFinishing()) return;
                                try {
                                    XposedHelpers.callMethod(a, "B7");
                                    WxLog.i("TransferHook: B7() 已调用（降级路径）");
                                } catch (Throwable t) {
                                    WxLog.e("TransferHook: B7() 失败", t);
                                }
                                if (needFinishAfterB7) {
                                    // 前台模式：B7() 后延迟关闭，防止残留在返回栈
                                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                        Activity a2 = actRef.get();
                                        if (a2 != null && !a2.isFinishing()) {
                                            a2.finish();
                                            WxLog.i("TransferHook: 降级路径（前台）收款页已关闭");
                                        }
                                    }, 1_500L);
                                }
                            }, ACCEPT_DELAY_MS);
                        }
                    });
            WxLog.i("TransferHook: RemittanceDetailUI hook 安装成功");
        } catch (Throwable t) {
            WxLog.e("TransferHook: hook 安装失败", t);
        }

        // ── 监听 Activity 销毁：清除已失效的 zm5.b0 缓存 ─────────────────────
        try {
            XposedHelpers.findAndHookMethod(
                    REMITTANCE_CLASS, loader, "onDestroy",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            WeakReference<Activity> ref = sRemittanceActivity;
                            if (ref != null && ref.get() == param.thisObject) {
                                sRemittanceActivity = null;
                                sNetSceneMgr = null;
                                WxLog.i("TransferHook: 收款页已销毁，NetSceneMgr 缓存已清除");
                            }
                        }
                    });
            WxLog.i("TransferHook: RemittanceDetailUI onDestroy hook 安装成功");
        } catch (Throwable t) {
            WxLog.e("TransferHook: onDestroy hook 安装失败", t);
        }

        // ── 拦截返回键，防止用户回退到保活的收款页 ───────────────────────────
        try {
            XposedHelpers.findAndHookMethod(
                    REMITTANCE_CLASS, loader, "onBackPressed",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // 收款页不应出现在用户的回退路径中，直接关闭
                            Activity act = (Activity) param.thisObject;
                            act.finish();
                            param.setResult(null);
                        }
                    });
            WxLog.i("TransferHook: RemittanceDetailUI onBackPressed hook 安装成功");
        } catch (Throwable t) {
            WxLog.e("TransferHook: onBackPressed hook 安装失败", t);
        }
    }

    // ── 收到新转账（MessageHandler 调用）─────────────────────────────────────

    public void onTransferReceived(final String talker, final String transferId,
                                    final String transactionId, final int invalidTime) {
        if (!GeekConfig.AUTO_ACCEPT_TRANSFER) return;
        WxLog.i("TransferHook: 收到转账 talker=" + talker
                + " transferId=" + transferId
                + " transactionId=" + transactionId);

        // 若尚未缓存，先尝试通过反射查找 zm5.b0 单例（避免启动降级 Activity）
        if (sLoader != null && sNetSceneMgr == null) {
            Object mgr = tryGetNetSceneMgr(sLoader);
            if (mgr != null) {
                sNetSceneMgr = mgr;
                WxLog.i("TransferHook: 反射获取 NetSceneMgr 成功");
            }
        }

        if (sLoader != null && sNetSceneMgr != null) {
            // ── 主路径：无界面 CGI ──────────────────────────────────────────────
            final Object finalMgr = sNetSceneMgr;
            WeakReference<Activity> actRef = sRemittanceActivity;
            boolean actAlive = actRef != null && actRef.get() != null && !actRef.get().isFinishing();
            long delay = GeekConfig.resolvedTransferDelay();
            WxLog.i("TransferHook: 使用无界面路径（保活Activity=" + actAlive + "），延迟=" + delay + "ms");
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> sendHeadlessCgi(talker, transferId, transactionId, invalidTime, finalMgr),
                    delay);
        } else {
            // ── 降级路径：不可见 Activity（反射也无法获取时）─────────────────
            long delay = GeekConfig.resolvedTransferDelay();
            WxLog.i("TransferHook: mNetSceneMgr 未就绪，使用降级路径，延迟=" + delay + "ms");
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> launchRemittanceUI(talker, transferId, transactionId, invalidTime),
                    delay);
        }
    }

    // ── 反射查找 zm5.b0 单例（无需打开 Activity）────────────────────────────

    private static Object tryGetNetSceneMgr(ClassLoader loader) {
        try {
            Class<?> b0Class = loader.loadClass("zm5.b0");
            // 查找并调用静态无参工厂方法（单例 getter，如 zm5.b0.a()）
            for (java.lang.reflect.Method m : b0Class.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && m.getReturnType().equals(b0Class)
                        && m.getParameterTypes().length == 0) {
                    m.setAccessible(true);
                    Object result = m.invoke(null);
                    if (result != null) {
                        WxLog.i("TransferHook: zm5.b0." + m.getName() + "() → NetSceneMgr 已获取");
                        return result;
                    }
                }
            }
            // 查找静态字段（直接存放单例实例）
            for (java.lang.reflect.Field f : b0Class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        && f.getType().equals(b0Class)) {
                    f.setAccessible(true);
                    Object result = f.get(null);
                    if (result != null) {
                        WxLog.i("TransferHook: zm5.b0." + f.getName() + " → NetSceneMgr 已获取");
                        return result;
                    }
                }
            }
            WxLog.i("TransferHook: 反射未找到 zm5.b0 单例（单例可能尚未初始化）");
        } catch (Throwable t) {
            WxLog.e("TransferHook: 反射获取 zm5.b0 失败", t);
        }
        return null;
    }

    // ── 无界面 CGI 发送 ───────────────────────────────────────────────────────

    private static void sendHeadlessCgi(String talker, String transferId,
                                         String transactionId, int invalidTime,
                                         Object netSceneMgr) {
        try {
            // 群聊转账：arg[7] = group wxid；个人转账：null
            String groupUsername = (talker != null && talker.startsWith("@@")) ? talker : null;

            /*
             * n0 构造参数（由 Frida 逆向确认）：
             *   (transactionId, transferId, 0, "confirm", talker, invalidTime,
             *    "", groupUsername, 1, "", null, 0L, talker, "")
             */
            Object n0 = XposedHelpers.newInstance(
                    sLoader.loadClass(N0_CLASS),
                    transactionId,  // arg[0]  transaction_id
                    transferId,     // arg[1]  trans_id
                    0,              // arg[2]  int
                    "confirm",      // arg[3]  op
                    talker,         // arg[4]  talker wxid
                    invalidTime,    // arg[5]  invalid_time (int)
                    "",             // arg[6]
                    groupUsername,  // arg[7]  group_username（个人转账为 null）
                    1,              // arg[8]  recv_account_type
                    "",             // arg[9]
                    null,           // arg[10] Map (null)
                    0L,             // arg[11] long
                    talker,         // arg[12]
                    ""              // arg[13]
            );

            // mNetSceneMgr.h(n0) 是 C7 调用的单参数提交入口
            // （Frida 逆向确认：zm5.b0.h(n0) → d1.dispatch → 网络请求）
            // 先加入 pending 集合，防止 GC 在回调前回收 n0
            sPendingCgi.add(n0);
            XposedHelpers.callMethod(netSceneMgr, "h", n0);
            WxLog.i("TransferHook: 无界面 CGI 已发送 transferId=" + transferId
                    + " pending=" + sPendingCgi.size());

        } catch (Throwable t) {
            WxLog.e("TransferHook: 无界面 CGI 失败，降级启动 Activity", t);
            launchRemittanceUI(talker, transferId, transactionId, invalidTime);
        }
    }

    // ── 启动不可见收款详情页（降级路径）──────────────────────────────────────

    private static void launchRemittanceUI(String talker, String transferId,
                                            String transactionId, int invalidTime) {
        try {
            Context ctx = getAppContext();
            if (ctx == null) {
                WxLog.i("TransferHook: 无法获取 Context，跳过降级启动");
                return;
            }
            // 在启动前检测微信是否在前台，传入 Activity 供差异化处理
            boolean inForeground = isWeChatInForeground();
            Intent intent = new Intent();
            intent.setClassName("com.tencent.mm", REMITTANCE_CLASS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            intent.putExtra("wxadt_from_foreground", inForeground);
            if (transferId    != null) intent.putExtra("transfer_id",    transferId);
            if (transactionId != null) intent.putExtra("transaction_id", transactionId);
            if (invalidTime   != 0)   intent.putExtra("invalid_time",   invalidTime);
            if (talker        != null) intent.putExtra("Chat_User",      talker);
            ctx.startActivity(intent);
            WxLog.i("TransferHook: 降级路径已启动收款页 transferId=" + transferId
                    + "（微信前台=" + inForeground + "）");
        } catch (Throwable t) {
            WxLog.e("TransferHook: 启动收款页失败", t);
        }
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    /** 检测微信进程当前是否在前台（importance == FOREGROUND） */
    private static boolean isWeChatInForeground() {
        try {
            Context ctx = getAppContext();
            if (ctx == null) return false;
            android.app.ActivityManager am =
                    (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            int myPid = android.os.Process.myPid();
            for (android.app.ActivityManager.RunningAppProcessInfo info
                    : am.getRunningAppProcesses()) {
                if (info.pid == myPid) {
                    return info.importance
                            <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static Context getAppContext() {
        try {
            return (Context) XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityThread"),
                    "currentApplication");
        } catch (Throwable t) {
            WxLog.e("TransferHook: getAppContext 失败", t);
            return null;
        }
    }
}
