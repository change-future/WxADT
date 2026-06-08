package com.plug.wxadt;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Modifier;
import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class HongBaoHook {

    private static final String HB_RECEIVE_UI =
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNewReceiveUI";
    private static final String HB_BASE_UI =
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyBaseUI";

    private static volatile WeakReference<Activity> sHbActivity = null;
    private static volatile boolean sF7Called = false;
    // 领取页 finish() 时间戳，用于检测并关闭后续"领取成功"页
    private static volatile long sHbClaimFinishTime = 0L;

    public void install(ClassLoader loader) {
        hookOnCreate(loader);
        hookOnSceneEnd(loader);
        hookOnDestroy(loader);
        hookFinish();
        hookSuccessActivity();
    }

    // ── 收到红包消息 ──────────────────────────────────────────────────────────

    public void onHongBaoReceived(final String talker, final String nativeUrl,
                                   final String sendId, final String sendUserName,
                                   final int invalidTime) {
        if (!GeekConfig.AUTO_ACCEPT_HONGBAO) return;
        long delay = GeekConfig.resolvedHongBaoDelay();
        WxLog.i("HongBaoHook: 收到红包 talker=" + talker + " sendId=" + sendId + " 延迟=" + delay + "ms");
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> launchReceiveUI(nativeUrl, sendUserName, invalidTime),
                delay);
    }

    private static void launchReceiveUI(String nativeUrl, String sendUserName, int invalidTime) {
        try {
            Context ctx = getAppContext();
            if (ctx == null) { WxLog.i("HongBaoHook: 无法获取 Context，跳过"); return; }
            Intent intent = new Intent();
            intent.setClassName("com.tencent.mm", HB_RECEIVE_UI);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            intent.putExtra("key_native_url",    nativeUrl);
            intent.putExtra("key_from_username", sendUserName);
            intent.putExtra("key_username",      sendUserName);
            intent.putExtra("key_invalidtime",   invalidTime);
            intent.putExtra("scene_id",          1002);
            intent.putExtra("key_way",           1);
            intent.putExtra("wxadt_hb_launched", true);
            ctx.startActivity(intent);
            WxLog.i("HongBaoHook: 已启动红包领取页");
        } catch (Throwable t) { WxLog.e("HongBaoHook: 启动红包页失败", t); }
    }

    // ── Hook: onCreate ────────────────────────────────────────────────────────

    private static void hookOnCreate(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(
                    HB_RECEIVE_UI, loader,
                    "onCreate", android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity act = (Activity) param.thisObject;
                            if (!GeekConfig.AUTO_ACCEPT_HONGBAO) return;
                            sHbActivity = new WeakReference<>(act);
                            sF7Called = false;
                            try {
                                android.view.Window w = act.getWindow();
                                android.view.WindowManager.LayoutParams lp = w.getAttributes();
                                lp.alpha     = 0f;
                                lp.dimAmount = 0f;
                                w.setAttributes(lp);
                                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                            } catch (Throwable t) {
                                WxLog.e("HongBaoHook: 透明窗口设置失败", t);
                            }
                            boolean inFg = isWeChatInForeground();
                            if (!inFg) {
                                try { act.moveTaskToBack(true); } catch (Throwable ignored) {}
                            }
                            WxLog.i("HongBaoHook: 红包领取页已创建 inFg=" + inFg);
                        }
                    });
            WxLog.i("HongBaoHook: onCreate hook 安装成功");
        } catch (Throwable t) { WxLog.e("HongBaoHook: onCreate hook 失败", t); }
    }

    // ── Hook: onSceneEnd（仅 hook 基类非抽象外层方法，避免双触发）────────────

    private static void hookOnSceneEnd(ClassLoader loader) {
        int hookCount = 0;
        try {
            Class<?> cls = loader.loadClass(HB_BASE_UI);
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                if (!"onSceneEnd".equals(m.getName())) continue;
                if (Modifier.isAbstract(m.getModifiers())) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length < 2 || params[0] != int.class || params[1] != int.class) continue;
                try {
                    XposedBridge.hookMethod(m, sOnSceneEndHook);
                    hookCount++;
                } catch (Throwable t) { WxLog.e("HongBaoHook: hookOnSceneEnd 失败", t); }
            }
        } catch (Throwable t) { WxLog.e("HongBaoHook: 加载 LuckyMoneyBaseUI 失败", t); }
        WxLog.i("HongBaoHook: onSceneEnd hook 安装完成（" + hookCount + " 处）");
    }

    private static final XC_MethodHook sOnSceneEndHook = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            WeakReference<Activity> ref = sHbActivity;
            if (ref == null) return;
            Activity act = ref.get();
            if (act == null || act.isFinishing()) return;
            if (act != param.thisObject) return;
            if (!GeekConfig.AUTO_ACCEPT_HONGBAO) return;

            int errType = (int) param.args[0];
            int errCode = (int) param.args[1];
            WxLog.i("HongBaoHook: onSceneEnd errType=" + errType + " errCode=" + errCode);

            if (errType != 0 || errCode != 0) {
                WxLog.i("HongBaoHook: onSceneEnd 失败，关闭红包页");
                act.finish();
                sHbActivity = null;
                return;
            }

            if (!sF7Called) {
                sF7Called = true;
                final WeakReference<Activity> actRef = new WeakReference<>(act);
                // 超时兜底：15s 内未完成则强制关闭
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Activity a = actRef.get();
                    if (a != null && !a.isFinishing()) {
                        WxLog.i("HongBaoHook: 超时兜底，关闭红包页");
                        a.finish();
                        sHbActivity = null;
                    }
                }, 15_000L);
                // 1500ms 后点击"开"按钮（等待信封动画完成）
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Activity a = actRef.get();
                    if (a == null || a.isFinishing()) return;
                    tryClickOpenButton(a, actRef, 1);
                }, 1_500L);
            }
        }
    };

    // ── Hook: onDestroy ───────────────────────────────────────────────────────

    private static void hookOnDestroy(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(
                    HB_RECEIVE_UI, loader, "onDestroy",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            WeakReference<Activity> ref = sHbActivity;
                            if (ref != null && ref.get() == param.thisObject) {
                                sHbActivity = null;
                                sF7Called = false;
                                WxLog.i("HongBaoHook: 红包领取页已销毁");
                            }
                        }
                    });
            WxLog.i("HongBaoHook: onDestroy hook 安装成功");
        } catch (Throwable t) { WxLog.e("HongBaoHook: onDestroy hook 失败", t); }
    }

    // ── Hook: finish()——记录领取完成时间，供后续页面检测使用 ─────────────────

    private static void hookFinish() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "finish", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    WeakReference<Activity> ref = sHbActivity;
                    if (ref == null || ref.get() != param.thisObject) return;
                    if (sF7Called) {
                        sHbClaimFinishTime = System.currentTimeMillis();
                    }
                }
            });
        } catch (Throwable t) { WxLog.e("HongBaoHook: finish hook 失败", t); }
    }

    // ── Hook: 自动关闭领取成功页 ──────────────────────────────────────────────
    // 微信在领取成功后会启动 luckymoney 相关 Activity 展示结果，在 10s 窗口内
    // 检测到这类页面后 500ms 自动关闭，不清除 flag 以捕获链式跳转的后续页面。

    private static void hookSuccessActivity() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    long finishTime = sHbClaimFinishTime;
                    if (finishTime == 0L) return;
                    Activity act = (Activity) param.thisObject;
                    WeakReference<Activity> mainRef = sHbActivity;
                    if (mainRef != null && mainRef.get() == act) return;
                    String cls = act.getClass().getName();
                    if (!cls.toLowerCase().contains("luckymoney")) return;
                    long elapsed = System.currentTimeMillis() - finishTime;
                    if (elapsed > 10_000L) { sHbClaimFinishTime = 0L; return; }
                    WxLog.i("HongBaoHook: 关闭后续红包页 " + act.getClass().getSimpleName());
                    final Activity actFinal = act;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (!actFinal.isFinishing()) actFinal.finish();
                    }, 500L);
                }
            });
            WxLog.i("HongBaoHook: 后续页面自动关闭 hook 安装成功");
        } catch (Throwable t) { WxLog.e("HongBaoHook: 后续页面自动关闭 hook 失败", t); }
    }

    // ── 点击"开"按钮 ─────────────────────────────────────────────────────────
    // Button 在信封动画播完后变为 VISIBLE，若仍为 GONE 则每 500ms 重试一次（最多 5 次）

    private static void tryClickOpenButton(Activity act,
                                            WeakReference<Activity> actRef,
                                            int attempt) {
        View root = act.getWindow().getDecorView();
        View btn = findButtonView(root);
        if (btn != null) {
            int vis = btn.getVisibility();
            WxLog.i("HongBaoHook: Button id=0x" + Integer.toHexString(btn.getId())
                    + " vis=" + visStr(vis) + " attempt=" + attempt);
            if (vis == View.GONE && attempt <= 5) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Activity a = actRef.get();
                    if (a != null && !a.isFinishing()) tryClickOpenButton(a, actRef, attempt + 1);
                }, 500L);
                return;
            }
            boolean result = btn.performClick();
            WxLog.i("HongBaoHook: performClick result=" + result);
            return;
        }
        // 降级：找最深叶子可点击节点
        View leaf = findClickableLeaf(root);
        if (leaf != null) {
            WxLog.i("HongBaoHook: 点击叶子 " + leaf.getClass().getSimpleName());
            leaf.performClick();
            return;
        }
        if (attempt <= 3) {
            WxLog.i("HongBaoHook: 未找到按钮，重试 attempt=" + attempt);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Activity a = actRef.get();
                if (a != null && !a.isFinishing()) tryClickOpenButton(a, actRef, attempt + 1);
            }, 500L);
        } else {
            WxLog.i("HongBaoHook: 未找到按钮，放弃");
        }
    }

    private static String visStr(int v) {
        if (v == View.VISIBLE)   return "VIS";
        if (v == View.INVISIBLE) return "INV";
        if (v == View.GONE)      return "GONE";
        return "?" + v;
    }

    // 查找 android.widget.Button（不检查可见性/可点击性）
    private static View findButtonView(View view) {
        if (view instanceof android.widget.Button) return view;
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findButtonView(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    // 叶子优先：返回最深的可见可点击非 TextView View
    private static View findClickableLeaf(View view) {
        if (view.getVisibility() != View.VISIBLE || !view.isEnabled()) return null;
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findClickableLeaf(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        if (view.isClickable() && !(view instanceof TextView)) return view;
        return null;
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    static boolean isWeChatInForeground() {
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
        } catch (Throwable t) { WxLog.e("HongBaoHook: getAppContext 失败", t); return null; }
    }
}
