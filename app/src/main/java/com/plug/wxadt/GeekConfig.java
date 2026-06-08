package com.plug.wxadt;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 运行时功能开关与配置，持久化在微信自己的 SharedPreferences（"GeekPluginConfig"）中。
 * 所有 volatile 字段无需重启微信即可立即生效——极客面板保存后直接写入这些字段。
 *
 * 设计规范：
 *   String 字段：空字符串 = 使用对应的 DEFAULT_* 常量
 *   int 字段：0 = 使用对应的 DEFAULT_* 常量
 *   在 Hook 中请使用 resolved*() 方法，而非直接读取字段。
 */
public class GeekConfig {

    static final String PREF_NAME = "GeekPluginConfig";

    // ── 变声默认值 ────────────────────────────────────────────────────────────
    // debug 构建从 local.properties 读取；release 构建为空字符串（用户在面板填写）
    public static final String DEFAULT_VOICE_URL     = BuildConfig.DEFAULT_VOICE_URL;
    public static final String DEFAULT_VOICE_SPEAKER = "speaker1";

    // ── AI 默认值 ─────────────────────────────────────────────────────────────
    public static final String DEFAULT_AI_URL     = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    public static final String DEFAULT_AI_MODEL   = "qwen3.6-plus";
    // debug 构建从 local.properties 读取；release 构建为空字符串（用户在面板填写）
    public static final String DEFAULT_AI_KEY     = BuildConfig.DEFAULT_AI_KEY;
    // 内置默认提示词从 assets/default_prompt.txt 加载，不再硬编码在此处
    // 读取失败时降级使用下方简短兜底提示词
    static final String FALLBACK_PROMPT = "你是一个智能助手，请用简洁、自然的微信聊天语气回复消息，不要使用 Markdown 格式。";
    // 启动时由 loadFromContext 填充，之后保持不变
    public static volatile String sDefaultPrompt = "";
    public static final int    DEFAULT_AI_WINDOW  = 5_000;  // 毫秒
    public static final int    DEFAULT_AI_HISTORY = 20;     // 轮次

    // ── 支付延迟默认值 ────────────────────────────────────────────────────────
    public static final int DEFAULT_TRANSFER_DELAY = 1_000;  // 毫秒，收到转账后等待多久再确认
    public static final int DEFAULT_HONGBAO_DELAY  = 1_000;  // 毫秒，收到红包后等待多久再领取

    // ── 功能开关 ──────────────────────────────────────────────────────────────
    public static volatile boolean ANTI_REVOKE           = true;
    public static volatile boolean AUTO_REPLY            = false;  // 同时覆盖语音消息（自动识别后回复）
    public static volatile boolean VOICE_CHANGER         = false;
    public static volatile boolean AUTO_ACCEPT_TRANSFER  = false;  // 收到转账自动启动收款页并确认
    public static volatile boolean AUTO_ACCEPT_HONGBAO   = false;  // 收到红包自动打开并领取

    // ── 支付延迟（0 → 使用 DEFAULT） ─────────────────────────────────────────
    public static volatile int TRANSFER_DELAY_MS = 0;
    public static volatile int HONGBAO_DELAY_MS  = 0;

    // ── 变声配置（空字符串 → 使用 DEFAULT） ───────────────────────────────────
    public static volatile String  VOICE_URL     = "";
    public static volatile String  VOICE_SPEAKER = "";

    // ── AI 配置（空字符串/0 → 使用 DEFAULT） ─────────────────────────────────
    public static volatile String  AI_URL     = "";
    public static volatile String  AI_MODEL   = "";
    public static volatile String  AI_KEY     = "";  // 空 → 使用 DEFAULT_AI_KEY
    public static volatile String  AI_PROMPT  = "";
    public static volatile int     AI_WINDOW  = 0;   // 0 → 使用 DEFAULT_AI_WINDOW
    public static volatile int     AI_HISTORY = 0;   // 0 → 使用 DEFAULT_AI_HISTORY

    // ── resolved 解析方法（始终返回可用值） ───────────────────────────────────

    public static int    resolvedTransferDelay() { return TRANSFER_DELAY_MS <= 0 ? DEFAULT_TRANSFER_DELAY : TRANSFER_DELAY_MS; }
    public static int    resolvedHongBaoDelay()  { return HONGBAO_DELAY_MS  <= 0 ? DEFAULT_HONGBAO_DELAY  : HONGBAO_DELAY_MS; }

    public static String resolvedVoiceUrl()     { return VOICE_URL.isEmpty()     ? DEFAULT_VOICE_URL     : VOICE_URL; }
    public static String resolvedVoiceSpeaker() { return VOICE_SPEAKER.isEmpty() ? DEFAULT_VOICE_SPEAKER : VOICE_SPEAKER; }

    public static String resolvedAiUrl()     { return AI_URL.isEmpty()    ? DEFAULT_AI_URL    : AI_URL; }
    public static String resolvedAiModel()   { return AI_MODEL.isEmpty()  ? DEFAULT_AI_MODEL  : AI_MODEL; }
    public static String resolvedAiKey()     { return AI_KEY.isEmpty()    ? DEFAULT_AI_KEY    : AI_KEY; }
    public static String resolvedAiPrompt()  {
        if (!AI_PROMPT.isEmpty()) return AI_PROMPT;
        return sDefaultPrompt.isEmpty() ? FALLBACK_PROMPT : sDefaultPrompt;
    }
    public static String resolvedDefaultPrompt() {
        return sDefaultPrompt.isEmpty() ? FALLBACK_PROMPT : sDefaultPrompt;
    }
    public static int    resolvedAiWindow()  { return AI_WINDOW  <= 0     ? DEFAULT_AI_WINDOW  : AI_WINDOW; }
    public static int    resolvedAiHistory() { return AI_HISTORY <= 0     ? DEFAULT_AI_HISTORY : AI_HISTORY; }

    // ── 持久化 ────────────────────────────────────────────────────────────────

    /** 从微信 SharedPreferences 读取所有配置到静态字段。 */
    public static void loadFromContext(Context ctx) {
        try {
            // 首次加载时从 assets 读取内置默认提示词
            if (sDefaultPrompt.isEmpty()) {
                String assetDefault = PromptFileManager.readDefault(ctx);
                if (assetDefault != null) sDefaultPrompt = assetDefault;
                else sDefaultPrompt = FALLBACK_PROMPT;
            }

            SharedPreferences p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

            ANTI_REVOKE          = p.getBoolean("enable_antirevoke",           true);
            AUTO_REPLY           = p.getBoolean("enable_autoreply",           false);
            VOICE_CHANGER        = p.getBoolean("enable_voice_changer",       false);
            AUTO_ACCEPT_TRANSFER = p.getBoolean("enable_auto_accept_transfer", false);
            AUTO_ACCEPT_HONGBAO  = p.getBoolean("enable_auto_accept_hongbao",  false);

            TRANSFER_DELAY_MS = p.getInt("transfer_delay_ms", 0);
            HONGBAO_DELAY_MS  = p.getInt("hongbao_delay_ms",  0);

            VOICE_URL     = p.getString("voice_url",     "");
            VOICE_SPEAKER = p.getString("voice_speaker", "");

            AI_URL     = p.getString("ai_url",   "");
            AI_MODEL   = p.getString("ai_model", "");
            AI_KEY     = p.getString("ai_key",   "");
            AI_WINDOW  = p.getInt(   "ai_window",  0);
            AI_HISTORY = p.getInt(   "ai_history", 0);

            // 系统提示词优先从文件加载；旧版保存在 SharedPreferences 中的值迁移到文件后清除
            String filePrompt = PromptFileManager.read(ctx);
            if (filePrompt != null) {
                AI_PROMPT = filePrompt;
            } else {
                String legacyPrompt = p.getString("ai_prompt", "");
                if (!legacyPrompt.isEmpty()) {
                    // 将旧版 SharedPreferences 提示词迁移到文件，并清除旧值
                    AI_PROMPT = legacyPrompt;
                    PromptFileManager.write(ctx, legacyPrompt);
                    p.edit().remove("ai_prompt").apply();
                    WxLog.i("提示词已从 SharedPreferences 迁移到文件");
                } else {
                    AI_PROMPT = ""; // resolvedAiPrompt() 将返回内置默认提示词
                }
            }

            WxLog.i("GeekConfig 已加载:"
                    + " 防撤回="     + ANTI_REVOKE
                    + " 自动回复="   + AUTO_REPLY
                    + " 变声="       + VOICE_CHANGER
                    + " 自动收款="   + AUTO_ACCEPT_TRANSFER
                    + " 自动收红包=" + AUTO_ACCEPT_HONGBAO
                    + " AI模型="    + resolvedAiModel()
                    + " API密钥="   + (resolvedAiKey().equals(DEFAULT_AI_KEY) ? "使用内置默认" : "已自定义(" + AI_KEY.length() + "位)")
                    + " 提示词="    + (AI_PROMPT.isEmpty() ? "使用内置默认" : "文件(" + AI_PROMPT.length() + "字)")
                    + " 批量窗口="  + resolvedAiWindow() + "ms"
                    + " 历史条数="  + resolvedAiHistory());
        } catch (Throwable t) {
            WxLog.e("GeekConfig.loadFromContext 失败", t);
        }
    }

    /** 将所有当前静态字段值写入微信 SharedPreferences。极客面板保存后调用此方法。 */
    public static void persist(Context ctx) {
        try {
            ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                    .putBoolean("enable_antirevoke",            ANTI_REVOKE)
                    .putBoolean("enable_autoreply",             AUTO_REPLY)
                    .putBoolean("enable_voice_changer",         VOICE_CHANGER)
                    .putBoolean("enable_auto_accept_transfer",  AUTO_ACCEPT_TRANSFER)
                    .putBoolean("enable_auto_accept_hongbao",   AUTO_ACCEPT_HONGBAO)
                    .putInt(    "transfer_delay_ms",    TRANSFER_DELAY_MS)
                    .putInt(    "hongbao_delay_ms",     HONGBAO_DELAY_MS)
                    .putString( "voice_url",            VOICE_URL)
                    .putString( "voice_speaker",        VOICE_SPEAKER)
                    .putString( "ai_url",               AI_URL)
                    .putString( "ai_model",             AI_MODEL)
                    .putString( "ai_key",               AI_KEY)
                    // ai_prompt 已改为文件存储，此处不再写入 SharedPreferences
                    .putInt(    "ai_window",             AI_WINDOW)
                    .putInt(    "ai_history",            AI_HISTORY)
                    .apply();
            WxLog.i("GeekConfig 已持久化");
        } catch (Throwable t) {
            WxLog.e("GeekConfig.persist 失败", t);
        }
    }
}
