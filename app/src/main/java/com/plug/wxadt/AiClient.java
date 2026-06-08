package com.plug.wxadt;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;

/**
 * 极简 OpenAI 兼容协议聊天客户端。
 * 使用 HttpURLConnection 而非 OkHttp，避免污染微信的全局 Authorization 头或触发超时冲突。
 * 兼容：阿里千问、OpenAI、Azure OpenAI、Ollama、LM Studio、DeepSeek、Moonshot
 * 以及所有兼容 OpenAI 协议的本地/云端模型。
 */
public class AiClient {

    public interface Callback {
        void onResult(String reply);
        void onError(String errorMsg);
    }

    /**
     * 向 {baseUrl}/chat/completions 发起 POST 请求，解析并返回第一个 choice 的内容。
     * 在后台线程执行；回调也在同一后台线程触发——如需更新 UI，调用方需自行 post 到主线程。
     *
     * @param baseUrl  接口地址，例如 "https://dashscope.aliyuncs.com/compatible-mode/v1"
     * @param apiKey   Bearer 鉴权密钥（本地模型可传空字符串）
     * @param model    模型名称，例如 "qwen-plus"、"gpt-4o"、"deepseek-chat"
     * @param messages 消息列表，格式：[{role:"system"|"user"|"assistant", content:"..."}, ...]
     */
    public static void chat(String baseUrl,
                             String apiKey,
                             String model,
                             List<Map<String, String>> messages,
                             Callback callback) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String endpoint = resolveEndpoint(baseUrl);
                WxLog.i("AiClient → " + clip(endpoint, 80)
                        + " 模型=" + model
                        + " 密钥=" + (apiKey != null && !apiKey.isEmpty() ? "已设置" : "未设置❌")
                        + " 消息数=" + messages.size());

                // 打印完整请求消息列表，便于调试
                StringBuilder reqLog = new StringBuilder("━━ AI请求消息列表 ━━\n");
                for (int idx = 0; idx < messages.size(); idx++) {
                    Map<String, String> m = messages.get(idx);
                    String role    = m.get("role");
                    String content = m.get("content");
                    String preview = content == null ? "null"
                            : content.length() <= 200 ? content
                            : content.substring(0, 200) + "…(共" + content.length() + "字)";
                    reqLog.append("[").append(idx + 1).append("] ")
                          .append(role).append(": ")
                          .append(preview).append('\n');
                }
                reqLog.append("━━━━━━━━━━━━━━━━━━━━");
                WxLog.i(reqLog.toString());

                // 构建请求体 JSON
                JSONArray msgArr = new JSONArray();
                for (Map<String, String> m : messages) {
                    JSONObject o = new JSONObject();
                    o.put("role",    m.get("role"));
                    o.put("content", m.get("content"));
                    msgArr.put(o);
                }
                JSONObject body = new JSONObject();
                body.put("model",    model);
                body.put("messages", msgArr);
                body.put("stream",   false);

                byte[] bodyBytes = body.toString().getBytes("UTF-8");

                conn = (HttpURLConnection) new URL(endpoint).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type",  "application/json; charset=utf-8");
                conn.setRequestProperty("Accept",        "application/json");
                if (apiKey != null && !apiKey.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                }
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(60_000);
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bodyBytes);
                }

                int code = conn.getResponseCode();
                InputStream stream = code < 400
                        ? conn.getInputStream()
                        : conn.getErrorStream();
                String response = readFully(stream);
                WxLog.i("AiClient HTTP " + code + " (响应=" + clip(response, 120) + ")");

                if (code == 200) {
                    JSONObject resp = new JSONObject(response);
                    String content  = resp.getJSONArray("choices")
                                         .getJSONObject(0)
                                         .getJSONObject("message")
                                         .getString("content");
                    callback.onResult(content.trim());
                } else {
                    callback.onError("HTTP " + code + ": " + clip(response, 200));
                }

            } catch (Throwable t) {
                callback.onError(t.getClass().getSimpleName() + ": " + t.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "AiClient").start();
    }

    /**
     * 将 baseUrl 规范化为完整的 chat/completions 端点地址。
     * 自动处理末尾斜杠，以及已包含完整路径的情况。
     */
    static String resolveEndpoint(String base) {
        if (base == null || base.isEmpty()) base = GeekConfig.DEFAULT_AI_URL;
        base = base.trim();
        if (base.endsWith("/chat/completions") || base.endsWith("/chat/completions/")) {
            return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        }
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/chat/completions";
    }

    private static String readFully(InputStream in) throws Exception {
        if (in == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString().trim();
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
