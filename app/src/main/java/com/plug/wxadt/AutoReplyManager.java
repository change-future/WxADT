package com.plug.wxadt;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信消息 AI 自动回复管理器。
 *
 * 设计要点：
 *   - 仅处理单聊（跳过 @chatroom 群聊和 gh_ 公众号）。
 *   - 收到消息后等待 AI_WINDOW 毫秒；窗口内若再有新消息则重置计时（批量防抖），
 *     从而将对方连续发送的多条消息合并为一次 AI 请求，而非触发多个并行请求。
 *   - 每个联系人最多同时有一个 AI 请求在途（飞行锁）。
 *   - 对话历史（最多 AI_HISTORY 轮）保存在内存中，每次请求都随消息一起发送。
 *     历史不持久化，微信重启后清空。
 *   - 发送"重置对话"（或 /reset）可清除该联系人的历史记录。
 *   - AI 回复消息做去重处理，防止 DB INSERT isSend=1 时被重复计入历史。
 */
public class AutoReplyManager {

    public interface ReplySender {
        void send(String talker, String text);
    }

    /** 从外部（数据库）加载指定联系人的历史消息，按时间正序返回 [{role, content}]。 */
    public interface HistoryLoader {
        List<Map<String, String>> load(String talker, int limit);
    }

    // ── 常量 ──────────────────────────────────────────────────────────────────
    private static final String[] RESET_KEYWORDS = {"重置对话", "/reset", "清空记忆", "/clear"};
    // 内部缓冲区最大容量（AI_HISTORY 的 2 倍，防止内存无限增长）
    private static final int BUFFER_MULTIPLIER = 2;
    private static final int MIN_BUFFER         = 10;

    // ── 状态 ──────────────────────────────────────────────────────────────────
    private final ReplySender   mSender;
    private final HistoryLoader mHistoryLoader;
    private final Handler       mHandler = new Handler(Looper.getMainLooper());

    // 本次会话已从数据库加载过历史的联系人集合（每个联系人只加载一次）
    private final Set<String> mPreloadedTalkers =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // 每个联系人的对话历史列表 [{role, content}]
    private final Map<String, LinkedList<Map<String, String>>> mHistory =
            new ConcurrentHashMap<>();

    // 每个联系人已调度的防抖任务
    private final Map<String, Runnable> mPendingTasks = new ConcurrentHashMap<>();

    // 每个联系人的飞行锁（防止并行请求）
    private final Map<String, Boolean> mInFlight = new ConcurrentHashMap<>();

    // 发起请求时记录的历史条数，用于识别在途期间到达的新消息
    private final Map<String, Integer> mRequestHistorySize = new ConcurrentHashMap<>();

    // AI 回复去重表：key = "talker\n内容"，value = 发送时间戳
    // 防止 DB INSERT isSend=1 时将 AI 自己的回复再次计入历史
    private final Map<String, Long> mAiSentDedup = new ConcurrentHashMap<>();

    public AutoReplyManager(ReplySender sender, HistoryLoader loader) {
        mSender        = sender;
        mHistoryLoader = loader;
    }

    // ── 对外接口 ──────────────────────────────────────────────────────────────

    /**
     * 每条收到的消息（isSend=0，type=1 或语音识别结果）调用此方法。
     * 将消息加入历史并调度防抖 AI 回复。
     */
    public void onIncomingMessage(String talker, String content) {
        if (!isHandleable(talker) || content == null || content.isEmpty()) return;

        // 重置指令：静默清空历史，不回复
        if (isResetKeyword(content.trim())) {
            clearHistory(talker);
            return;
        }

        // 本次会话首次收到该联系人消息时，先从数据库加载历史记录
        if (mHistoryLoader != null && mPreloadedTalkers.add(talker)) {
            preloadFromDb(talker);
        }

        addToHistory(talker, "user", content);
        scheduleBatchReply(talker);
    }

    /**
     * 每条发出的消息（isSend=1，type=1）调用此方法。
     * 作为 assistant 轮次加入历史，让 AI 知道之前已发过什么。
     * AI 自动回复的消息在此处去重，避免重复计入。
     */
    public void onOutgoingMessage(String talker, String content) {
        if (!isHandleable(talker) || content == null || content.isEmpty()) return;

        String key = talker + '\n' + content;
        if (mAiSentDedup.remove(key) != null) {
            // 这条发出的消息是 AI 自己的回复，已在 onResult 中加入历史，跳过
            return;
        }
        // 用户手动发送的消息，作为 assistant 上下文加入历史
        addToHistory(talker, "assistant", content);
    }

    /** 清除指定联系人的对话历史，并允许下次重新从数据库加载 */
    public void clearHistory(String talker) {
        mHistory.remove(talker);
        mPreloadedTalkers.remove(talker);  // 允许下次重新加载 DB 历史
        WxLog.i("AI: 已清除对话历史 talker=" + talker);
    }

    /** 清除所有联系人的对话历史（例如功能关闭时调用） */
    public void clearAllHistory() {
        mHistory.clear();
        mAiSentDedup.clear();
        mPreloadedTalkers.clear();
    }

    // ── 数据库历史预加载 ──────────────────────────────────────────────────────

    private void preloadFromDb(String talker) {
        try {
            int limit = Math.max(1, GeekConfig.resolvedAiHistory());
            List<Map<String, String>> dbHistory = mHistoryLoader.load(talker, limit);
            if (dbHistory == null || dbHistory.isEmpty()) return;

            LinkedList<Map<String, String>> list =
                    mHistory.computeIfAbsent(talker, k -> new LinkedList<>());
            // DB 历史作为基底插入队列头部（之后实时消息追加到尾部）
            list.addAll(0, dbHistory);

            // 裁剪到缓冲区上限
            int cap = Math.max(MIN_BUFFER, limit * BUFFER_MULTIPLIER);
            while (list.size() > cap) list.removeFirst();

            WxLog.i("AI: 从数据库加载历史 talker=" + talker + " 条数=" + dbHistory.size());
        } catch (Throwable t) {
            WxLog.e("AI: 数据库历史加载失败 talker=" + talker, t);
        }
    }

    // ── 批量窗口与防抖 ────────────────────────────────────────────────────────

    private void scheduleBatchReply(String talker) {
        Runnable old = mPendingTasks.get(talker);
        if (old != null) mHandler.removeCallbacks(old);

        long windowMs = Math.max(500, GeekConfig.resolvedAiWindow());
        Runnable task = () -> {
            mPendingTasks.remove(talker);
            triggerReply(talker);
        };
        mPendingTasks.put(talker, task);
        mHandler.postDelayed(task, windowMs);
    }

    // ── AI 请求 ───────────────────────────────────────────────────────────────

    private void triggerReply(final String talker) {
        // 飞行锁：同一联系人同时只允许一个请求在途
        // 新消息已入历史，待 onResult/onError 检测到历史增长后自动触发后续请求
        if (Boolean.TRUE.equals(mInFlight.get(talker))) {
            WxLog.i("AI: " + talker + " 有请求进行中，新消息将在请求完成后处理");
            return;
        }

        List<Map<String, String>> apiMessages = buildApiMessages(talker);
        if (apiMessages.isEmpty()) return;

        mInFlight.put(talker, true);
        // 记录发起时的历史条数，onResult 用它识别在途期间到达的新消息
        LinkedList<Map<String, String>> histNow = mHistory.get(talker);
        mRequestHistorySize.put(talker, histNow != null ? histNow.size() : 0);

        WxLog.i("AI: 发起请求 talker=" + talker
                + " turns=" + apiMessages.size()
                + " model=" + GeekConfig.resolvedAiModel());

        AiClient.chat(
                GeekConfig.resolvedAiUrl(),
                GeekConfig.resolvedAiKey(),
                GeekConfig.resolvedAiModel(),
                apiMessages,
                new AiClient.Callback() {
                    @Override
                    public void onResult(String reply) {
                        mInFlight.remove(talker);
                        WxLog.i("AI: 收到回复 [" + talker + "] " + clip(reply, 60));

                        // 提取在途期间到达的新消息，将它们重排到 AI 回复之后
                        List<Map<String, String>> arrivedDuringFlight =
                                extractArrivedDuringFlight(talker);

                        // AI 决定不回复时返回 [NO_REPLY]，静默跳过，不发送也不加入历史
                        if ("[NO_REPLY]".equals(reply.trim())) {
                            WxLog.i("AI: [NO_REPLY] 跳过回复 talker=" + talker);
                            // 把在途消息追回历史末尾，让后续窗口能继续处理
                            reappendMessages(talker, arrivedDuringFlight);
                            sweepDedup();
                            if (!arrivedDuringFlight.isEmpty()) {
                                mHandler.postDelayed(() -> triggerReply(talker), 500);
                            }
                            return;
                        }

                        // 必须在加入历史之前注册去重，这样 onOutgoingMessage 才能匹配到
                        mAiSentDedup.put(talker + '\n' + reply, System.currentTimeMillis());
                        addToHistory(talker, "assistant", reply);
                        // 将在途期间到达的消息追加到 AI 回复之后，保证对话顺序正确
                        reappendMessages(talker, arrivedDuringFlight);
                        mHandler.post(() -> mSender.send(talker, reply));
                        sweepDedup();

                        if (!arrivedDuringFlight.isEmpty()) {
                            WxLog.i("AI: 在途期间收到 " + arrivedDuringFlight.size()
                                    + " 条新消息，继续回复 talker=" + talker);
                            mHandler.postDelayed(() -> triggerReply(talker), 500);
                        }
                    }

                    @Override
                    public void onError(String errorMsg) {
                        mInFlight.remove(talker);
                        mRequestHistorySize.remove(talker);
                        WxLog.i("AI: 请求失败 [" + talker + "] " + errorMsg);
                        // 若在途期间有新用户消息，稍后重试
                        LinkedList<Map<String, String>> hist = mHistory.get(talker);
                        if (hist != null && !hist.isEmpty()
                                && "user".equals(hist.getLast().get("role"))) {
                            WxLog.i("AI: 请求失败后发现未回复消息，重试 talker=" + talker);
                            mHandler.postDelayed(() -> triggerReply(talker), 2_000);
                        }
                    }
                });
    }

    // ── 历史管理 ──────────────────────────────────────────────────────────────

    /**
     * 提取在请求发出后、回复到达前加入历史的新消息，并将它们从历史末尾移除。
     * 返回的列表按时间正序排列，供调用方追加到 AI 回复之后。
     */
    private List<Map<String, String>> extractArrivedDuringFlight(String talker) {
        Integer requestSize = mRequestHistorySize.remove(talker);
        if (requestSize == null) return new ArrayList<>();
        LinkedList<Map<String, String>> hist = mHistory.get(talker);
        if (hist == null || hist.size() <= requestSize) return new ArrayList<>();
        List<Map<String, String>> arrived = new ArrayList<>(hist.subList(requestSize, hist.size()));
        while (hist.size() > requestSize) hist.removeLast();
        return arrived;
    }

    /** 将消息列表追加到指定联系人的历史末尾，并裁剪到缓冲区上限。 */
    private void reappendMessages(String talker, List<Map<String, String>> messages) {
        if (messages.isEmpty()) return;
        LinkedList<Map<String, String>> hist = mHistory.get(talker);
        if (hist == null) return;
        hist.addAll(messages);
        int cap = Math.max(MIN_BUFFER, GeekConfig.resolvedAiHistory() * BUFFER_MULTIPLIER);
        while (hist.size() > cap) hist.removeFirst();
    }

    private void addToHistory(String talker, String role, String content) {
        LinkedList<Map<String, String>> list =
                mHistory.computeIfAbsent(talker, k -> new LinkedList<>());

        Map<String, String> msg = new LinkedHashMap<>();
        msg.put("role",    role);
        msg.put("content", content);
        list.addLast(msg);

        // 限制缓冲区大小，防止内存无限增长
        int cap = Math.max(MIN_BUFFER, GeekConfig.resolvedAiHistory() * BUFFER_MULTIPLIER);
        while (list.size() > cap) list.removeFirst();
    }

    /**
     * 构建发给 AI 的消息数组：
     *   [system_prompt?] + [最近 AI_HISTORY 轮对话]
     */
    private List<Map<String, String>> buildApiMessages(String talker) {
        List<Map<String, String>> result = new ArrayList<>();

        String prompt = GeekConfig.resolvedAiPrompt();
        if (!prompt.isEmpty()) {
            result.add(makeMsg("system", prompt));
        }

        LinkedList<Map<String, String>> hist = mHistory.get(talker);
        if (hist != null && !hist.isEmpty()) {
            int maxHist = Math.max(1, GeekConfig.resolvedAiHistory());
            List<Map<String, String>> all = new ArrayList<>(hist);
            int start = Math.max(0, all.size() - maxHist);
            result.addAll(all.subList(start, all.size()));
        }

        // 最后一条必须是 user 角色，否则 AI 无从回复
        if (result.isEmpty() || !"user".equals(result.get(result.size() - 1).get("role"))) {
            return new ArrayList<>();
        }
        return result;
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    /** 只处理单聊，跳过群聊和公众号 */
    private static boolean isHandleable(String talker) {
        if (talker == null || talker.isEmpty()) return false;
        if (talker.contains("@chatroom")) return false;
        if (talker.startsWith("gh_"))      return false;
        return true;
    }

    private static boolean isResetKeyword(String s) {
        for (String kw : RESET_KEYWORDS) {
            if (kw.equalsIgnoreCase(s)) return true;
        }
        return false;
    }

    private static Map<String, String> makeMsg(String role, String content) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("role",    role);
        m.put("content", content);
        return m;
    }

    private void sweepDedup() {
        long now = System.currentTimeMillis();
        mAiSentDedup.entrySet().removeIf(e -> now - e.getValue() > 30_000);
    }

    private static String clip(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
