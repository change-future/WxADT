package com.plug.wxadt;

import de.robv.android.xposed.XSharedPreferences;

/**
 * 模块全局配置。
 * 开关由模块 UI（MainActivity）写入 SharedPreferences，
 * 微信进程启动时通过 load() 读取一次，修改后需重启微信生效。
 */
public class Config {

    // ── 调试日志开关 ──────────────────────────────────────────────────────
    public static boolean LOG_DB_INSERT = false;  // 数据库 INSERT 详细日志
    public static boolean LOG_DB_UPDATE = false;  // 数据库 UPDATE 监听

    // ── SharedPreferences 键名（MainActivity 与 Config 共用） ─────────────
    static final String PREF_NAME          = "wxadt_config";
    static final String KEY_LOG_DB_INSERT  = "log_db_insert";
    static final String KEY_LOG_DB_UPDATE  = "log_db_update";

    /**
     * 在微信进程初始化时调用一次（hookLauncherActivity 里）。
     * 读取失败时所有开关保持默认值 false（全部关闭），不影响正常功能。
     *
     * @param packageName 模块包名，用于定位 SharedPreferences 文件
     */
    public static void load(String packageName) {
        try {
            XSharedPreferences prefs = new XSharedPreferences(packageName, PREF_NAME);
            prefs.reload();
            LOG_DB_INSERT = prefs.getBoolean(KEY_LOG_DB_INSERT, false);
            LOG_DB_UPDATE = prefs.getBoolean(KEY_LOG_DB_UPDATE, false);
        } catch (Throwable ignored) {
            // 读取失败使用默认值，不抛出异常
        }
    }
}
