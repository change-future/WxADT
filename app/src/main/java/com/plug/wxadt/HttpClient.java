package com.plug.wxadt;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.Body;
import retrofit2.http.FieldMap;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.QueryMap;
import retrofit2.http.Url;

/**
 * 零配置 HTTP 工具类，基于 Retrofit + OkHttp。
 *
 * 快速上手：
 *   // GET
 *   HttpClient.get("https://api.example.com/info", (res, err) -> { ... });
 *
 *   // POST JSON（body 可传 Map / 对象 / JSON 字符串）
 *   HttpClient.postJson("https://api.example.com/chat", body, (res, err) -> { ... });
 *
 *   // POST Form
 *   HttpClient.postForm("https://api.example.com/login", fields, (res, err) -> { ... });
 *
 * 全局鉴权 Header（调用一次即可）：
 *   HttpClient.setDefaultHeader("Authorization", "Bearer YOUR_KEY");
 *
 * 所有回调均在主线程执行。成功时 err 为 null，失败时 res 为 null。
 */
public class HttpClient {

    // ------------------------------------------------------------------ 回调
    /**
     * 统一回调接口，主线程触发。
     * json 格式固定为：{"code":int,"data":String|null,"msg":String}
     *   code  200  = 成功；-1 = 网络/本地异常；其他 = HTTP 错误码
     *   data       = 成功时的响应体（文件保存路径 / 响应字符串），失败时为 null
     *   msg        = 成功时为 "ok"，失败时为错误描述
     */
    public interface ApiCallback {
        void onResult(String json);
    }

    // ------------------------------------------------------------------ 私有状态
    private static final Gson GSON = new Gson();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, String> sHeaders = new HashMap<>();

    private static volatile OkHttpClient sOkClient;
    private static volatile RawApi sApi;

    // ------------------------------------------------------------------ 内部 Retrofit 接口
    interface RawApi {
        @GET
        Call<ResponseBody> get(
            @Url String fullUrl,
            @QueryMap Map<String, String> params
        );

        @POST
        Call<ResponseBody> postJson(
            @Url String fullUrl,
            @Body RequestBody body
        );

        @FormUrlEncoded
        @POST
        Call<ResponseBody> postForm(
            @Url String fullUrl,
            @FieldMap Map<String, String> fields
        );
    }

    // ================================================================== 公开配置

    /**
     * 设置全局默认 Header（例如 Authorization），所有请求都会自动携带。
     * 重复调用会覆盖同名 key。调用后已有客户端会重建。
     */
    public static void setDefaultHeader(String key, String value) {
        sHeaders.put(key, value);
        sOkClient = null;
        sApi = null;
    }

    /** 移除指定默认 Header */
    public static void removeDefaultHeader(String key) {
        sHeaders.remove(key);
        sOkClient = null;
        sApi = null;
    }

    // ================================================================== GET

    /** GET 请求，无额外参数 */
    public static void get(String url, ApiCallback cb) {
        get(url, Collections.emptyMap(), cb);
    }

    /** GET 请求，附带 Query 参数 */
    public static void get(String url, Map<String, String> params, ApiCallback cb) {
        api().get(url, params).enqueue(wrap(cb));
    }

    // ================================================================== POST JSON

    /**
     * POST JSON 请求。
     * body 支持：Map、任意 POJO 对象、或直接传 JSON 字符串。
     */
    public static void postJson(String url, Object body, ApiCallback cb) {
        String json = (body instanceof String) ? (String) body : GSON.toJson(body);
        RequestBody reqBody = RequestBody.create(MediaType.get("application/json; charset=utf-8"), json);
        api().postJson(url, reqBody).enqueue(wrap(cb));
    }

    /**
     * POST JSON 请求，并将响应中 data 字段的内容自动解析为指定类型。
     * 解析失败时走 ParsedCallback.onResult(null, errorMsg)。
     */
    public static <T> void postJsonAs(String url, Object body, Class<T> clz, ParsedCallback<T> cb) {
        postJson(url, body, json -> {
            try {
                ApiResult r = GSON.fromJson(json, ApiResult.class);
                if (r.code == 200 && r.data != null) {
                    cb.onResult(GSON.fromJson(r.data, clz), null);
                } else {
                    cb.onResult(null, r.msg);
                }
            } catch (Exception e) {
                cb.onResult(null, "JSON 解析失败: " + e.getMessage());
            }
        });
    }

    /** 内部用于解析标准 JSON 响应的简单 POJO */
    private static class ApiResult {
        int code;
        String data;
        String msg;
    }

    /** 带泛型的回调接口，配合 postJsonAs 使用 */
    public interface ParsedCallback<T> {
        void onResult(T result, String error);
    }

    // ================================================================== POST Form

    /** POST 表单请求（application/x-www-form-urlencoded） */
    public static void postForm(String url, Map<String, String> fields, ApiCallback cb) {
        api().postForm(url, fields).enqueue(wrap(cb));
    }

    // ================================================================== 内部工具

    private static OkHttpClient okClient() {
        if (sOkClient == null) {
            synchronized (HttpClient.class) {
                if (sOkClient == null) {
                    OkHttpClient.Builder okb = new OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(120, TimeUnit.SECONDS) // 变声等耗时操作需要更长超时
                        .writeTimeout(30, TimeUnit.SECONDS);
                    if (!sHeaders.isEmpty()) {
                        Map<String, String> copy = new HashMap<>(sHeaders);
                        okb.addInterceptor(chain -> {
                            Request.Builder rb = chain.request().newBuilder();
                            for (Map.Entry<String, String> e : copy.entrySet()) {
                                rb.header(e.getKey(), e.getValue());
                            }
                            return chain.proceed(rb.build());
                        });
                    }
                    sOkClient = okb.build();
                }
            }
        }
        return sOkClient;
    }

    private static RawApi api() {
        if (sApi == null) {
            synchronized (HttpClient.class) {
                if (sApi == null) {
                    // baseUrl 在使用 @Url 时不起作用，随便填一个合法占位即可
                    sApi = new Retrofit.Builder()
                        .baseUrl("https://placeholder.invalid/")
                        .client(okClient())
                        .addConverterFactory(GsonConverterFactory.create(GSON))
                        .build()
                        .create(RawApi.class);
                }
            }
        }
        return sApi;
    }

    // ================================================================== Multipart 文件上传

    /**
     * 二进制响应回调，适用于音频 / 图片等文件返回，主线程触发。
     * 成功：data != null, contentType 为响应 Content-Type；失败：data == null
     */
    public interface BinaryCallback {
        void onSuccess(byte[] data, String contentType);
        void onError(String error);
    }

    /**
     * 通用 multipart/form-data 文件上传，响应以原始字节返回。
     *
     * @param url       接口地址
     * @param fileParts 文件字段：fieldName -> File
     * @param textParts 普通文本字段（可为 null）
     * @param cb        BinaryCallback（主线程触发）
     */
    public static void postMultipart(String url,
                                     Map<String, File> fileParts,
                                     Map<String, String> textParts,
                                     BinaryCallback cb) {
        new Thread(() -> {
            try {
                MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);

                if (textParts != null) {
                    for (Map.Entry<String, String> e : textParts.entrySet()) {
                        builder.addFormDataPart(e.getKey(), e.getValue());
                    }
                }

                for (Map.Entry<String, File> e : fileParts.entrySet()) {
                    File file = e.getValue();
                    builder.addFormDataPart(
                        e.getKey(),
                        file.getName(),
                        RequestBody.create(MediaType.parse(guessMime(file.getName())), file)
                    );
                }

                Request request = new Request.Builder().url(url).post(builder.build()).build();

                try (okhttp3.Response resp = okClient().newCall(request).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        byte[] bytes = resp.body().bytes();
                        String ct = resp.header("Content-Type", "application/octet-stream");
                        MAIN.post(() -> cb.onSuccess(bytes, ct));
                    } else {
                        String errBody = resp.body() != null ? resp.body().string() : "";
                        MAIN.post(() -> cb.onError("HTTP " + resp.code() + ": " + errBody));
                    }
                }
            } catch (Exception e) {
                MAIN.post(() -> cb.onError(e.getMessage()));
            }
        }).start();
    }

    /**
     * 变声接口专用方法：上传 source 音频 + target 音色名，把返回的音频流覆盖写入 source。
     *
     * @param url    变声接口地址（如 http://192.168.x.x:8000/convert）
     * @param source 原声音频文件（上传后结果也写回此文件）
     * @param target 目标音色名（服务端 TARGET_AUDIO_MAP 的 key）
     * @param cb     onResult(json)，json 格式：{"code":int,"data":String|null,"msg":String,"duration":long}
     */
    public static void convertVoice(String url, File source, String target, ApiCallback cb) {
        convertVoice(url, source, target, source, cb);
    }

    /**
     * 变声接口专用方法（指定输出路径版）：上传 source，把返回的音频流写入 outputFile。
     * VoiceHook 使用此重载：source 为待转换音频，outputFile 为微信期望读取的 silk 路径。
     *
     * @param url        变声接口地址
     * @param source     上传给服务器的原声音频文件
     * @param target     目标音色名
     * @param outputFile 服务器返回的音频写入此文件（可与 source 不同）
     * @param cb         onResult(json)，json 含 "duration"（毫秒，从响应头 X-Audio-Duration 读取）
     */
    public static void convertVoice(String url, File source, String target, File outputFile, ApiCallback cb) {
        new Thread(() -> {
            try {
                MultipartBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("target", target)
                    .addFormDataPart(
                        "source",
                        source.getName(),
                        RequestBody.create(MediaType.parse(guessMime(source.getName())), source)
                    )
                    .build();

                Request request = new Request.Builder().url(url).post(body).build();

                try (okhttp3.Response resp = okClient().newCall(request).execute()) {
                    String ct = resp.header("Content-Type", "");
                    if (resp.isSuccessful() && resp.body() != null) {
                        if (ct != null && ct.contains("application/json")) {
                            String errBody = resp.body().string();
                            MAIN.post(() -> cb.onResult(makeJson(resp.code(), null, errBody)));
                        } else {
                            byte[] audio = resp.body().bytes();
                            long durationMs = 0;
                            try {
                                durationMs = Long.parseLong(resp.header("X-Audio-Duration", "0"));
                            } catch (NumberFormatException ignored) {}
                            final long finalDuration = durationMs;
                            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                                fos.write(audio);
                            }
                            Map<String, Object> ok = new HashMap<>();
                            ok.put("code", 200);
                            ok.put("data", outputFile.getAbsolutePath());
                            ok.put("msg", "success");
                            ok.put("duration", finalDuration);
                            MAIN.post(() -> cb.onResult(GSON.toJson(ok)));
                        }
                    } else {
                        String errBody = resp.body() != null ? resp.body().string() : "";
                        MAIN.post(() -> cb.onResult(makeJson(resp.code(), null, errBody)));
                    }
                }
            } catch (Exception e) {
                MAIN.post(() -> cb.onResult(makeJson(-1, null, e.getMessage())));
            }
        }).start();
    }

    private static String makeJson(int code, String data, String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", code);
        m.put("data", data);
        m.put("msg", msg);
        return GSON.toJson(m);
    }

    private static String guessMime(String fileName) {
        String n = fileName.toLowerCase();
        if (n.endsWith(".mp3"))  return "audio/mpeg";
        if (n.endsWith(".wav"))  return "audio/wav";
        if (n.endsWith(".ogg"))  return "audio/ogg";
        if (n.endsWith(".m4a"))  return "audio/mp4";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".silk")) return "audio/silk";
        return "application/octet-stream";
    }

    private static retrofit2.Callback<ResponseBody> wrap(ApiCallback cb) {
        return new retrofit2.Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> resp) {
                try {
                    if (resp.isSuccessful()) {
                        String body = resp.body() != null ? resp.body().string() : "";
                        MAIN.post(() -> cb.onResult(makeJson(200, body, "ok")));
                    } else {
                        String errBody = resp.errorBody() != null ? resp.errorBody().string() : "";
                        MAIN.post(() -> cb.onResult(makeJson(resp.code(), null, errBody)));
                    }
                } catch (IOException e) {
                    MAIN.post(() -> cb.onResult(makeJson(-1, null, "读取响应失败: " + e.getMessage())));
                }
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                MAIN.post(() -> cb.onResult(makeJson(-1, null, t.getMessage())));
            }
        };
    }
}
