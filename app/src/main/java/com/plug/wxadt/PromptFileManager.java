package com.plug.wxadt;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

/**
 * 系统提示词文件管理器。
 * 提示词存储在微信私有存储中：/data/data/com.tencent.mm/files/wxadt_prompt.txt
 * 文件不存在时 read() 返回 null，调用方降级使用 GeekConfig.DEFAULT_AI_PROMPT。
 */
public class PromptFileManager {

    private static final String FILE_NAME = "wxadt_prompt.txt";

    public static File getFile(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    /**
     * 读取提示词文件内容。
     * 文件不存在或为空时返回 null（调用方应使用内置默认提示词）。
     */
    public static String read(Context ctx) {
        try {
            File f = getFile(ctx);
            if (!f.exists() || f.length() == 0) return null;
            FileInputStream fis = new FileInputStream(f);
            InputStreamReader isr = new InputStreamReader(fis, "UTF-8");
            char[] buf = new char[(int) f.length()];
            int len = isr.read(buf);
            isr.close();
            String content = (len > 0) ? new String(buf, 0, len).trim() : null;
            return (content != null && !content.isEmpty()) ? content : null;
        } catch (Throwable t) {
            WxLog.e("PromptFileManager.read 失败", t);
            return null;
        }
    }

    /**
     * 从模块 APK 的 assets/default_prompt.txt 读取内置默认提示词。
     * 通过 createPackageContext 访问模块自身资源，在微信进程中也可正常工作。
     * 读取失败时返回 null，调用方应保留空字符串降级到简短兜底提示词。
     */
    public static String readDefault(Context ctx) {
        try {
            Context moduleCtx = ctx.createPackageContext(
                    "com.plug.wxadt",
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            InputStream is = moduleCtx.getAssets().open("default_prompt.txt");
            InputStreamReader isr = new InputStreamReader(is, "UTF-8");
            char[] buf = new char[65536];
            int len = isr.read(buf);
            isr.close();
            String content = (len > 0) ? new String(buf, 0, len).trim() : null;
            return (content != null && !content.isEmpty()) ? content : null;
        } catch (Throwable t) {
            WxLog.e("PromptFileManager.readDefault 失败", t);
            return null;
        }
    }

    /**
     * 将提示词写入文件。
     * content 为 null 或空字符串时删除文件，下次将回退到内置默认提示词。
     */
    public static void write(Context ctx, String content) {
        try {
            File f = getFile(ctx);
            if (content == null || content.trim().isEmpty()) {
                if (f.exists()) f.delete();
                WxLog.i("提示词文件已删除，将使用内置默认提示词");
                return;
            }
            FileOutputStream fos = new FileOutputStream(f, false);
            OutputStreamWriter osw = new OutputStreamWriter(fos, "UTF-8");
            osw.write(content);
            osw.flush();
            osw.close();
            WxLog.i("提示词文件已保存：" + f.getAbsolutePath()
                    + "（" + content.length() + " 字符）");
        } catch (Throwable t) {
            WxLog.e("PromptFileManager.write 失败", t);
        }
    }
}
