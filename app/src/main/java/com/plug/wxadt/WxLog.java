package com.plug.wxadt;

import de.robv.android.xposed.XposedBridge;

public class WxLog {

    private static final String PREFIX = "[WxADT] ";

    public static void i(String msg) {
        XposedBridge.log(PREFIX + msg);
    }

    public static void e(String msg, Throwable t) {
        XposedBridge.log(PREFIX + "❌ " + msg + ": " + t.getMessage());
        XposedBridge.log(t);
    }

    public static void dbInsert(String table, String data) {
        if (Config.LOG_DB_INSERT) {
            XposedBridge.log(PREFIX + "[DB-INSERT] table=" + table + " | " + data);
        }
    }

    public static void dbUpdate(String table, String data) {
        if (Config.LOG_DB_UPDATE) {
            XposedBridge.log(PREFIX + "[DB-UPDATE] table=" + table + " | " + data);
        }
    }
}
