package com.plug.wxadt;

import android.app.Activity;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class WeChatHook implements IXposedHookLoadPackage {

    private static final String WECHAT_PKG = "com.tencent.mm";
    private static final String MODULE_PKG  = "com.plug.wxadt";

    private static ClassLoader sWeChatClassLoader = null;

    private final VoiceHook         mVoiceHook         = new VoiceHook();
    private final TransferHook      mTransferHook      = new TransferHook();
    private final HongBaoHook       mHongBaoHook       = new HongBaoHook();
    private final MessageHandler    mMessageHandler    = new MessageHandler(mVoiceHook, mTransferHook, mHongBaoHook);
    private final SettingsInjector  mSettingsInjector  = new SettingsInjector();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (MODULE_PKG.equals(lpparam.packageName)) {
            XposedHelpers.findAndHookMethod(MODULE_PKG + ".MainActivity", lpparam.classLoader,
                    "isModuleActive",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(true);
                        }
                    });
            return;
        }

        if (!WECHAT_PKG.equals(lpparam.packageName) || !lpparam.processName.equals(WECHAT_PKG)) return;

        WxLog.i("🚀 WxADT 模块已注入微信主进程");

        // 设置面板不依赖微信 ClassLoader，立即安装，确保用户进入设置页前按钮已就绪
        mSettingsInjector.install();

        hookLauncherActivity();
    }

    private void hookLauncherActivity() {
        try {
            XposedHelpers.findAndHookMethod(
                    Activity.class,
                    "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (sWeChatClassLoader != null) return;
                            Activity activity = (Activity) param.thisObject;
                            if (!activity.getClass().getName().contains("LauncherUI")) return;

                            sWeChatClassLoader = activity.getClassLoader();
                            WxLog.i("成功捕获真实 ClassLoader");

                            // 从微信自身的 SharedPreferences 加载持久化功能开关
                            GeekConfig.loadFromContext(activity);

                            Config.load(MODULE_PKG);
                            mMessageHandler.install(sWeChatClassLoader);
                            mVoiceHook.install(sWeChatClassLoader);
                            mTransferHook.install(sWeChatClassLoader);
                            mHongBaoHook.install(sWeChatClassLoader);
                        }
                    }
            );
        } catch (Throwable t) {
            WxLog.e("hookLauncherActivity 失败", t);
        }
    }
}
