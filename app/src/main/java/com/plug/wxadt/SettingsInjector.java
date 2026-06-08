package com.plug.wxadt;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 向微信设置界面注入浮动"极客面板"按钮。
 *
 * 面板布局（四个圆角卡片区块）：
 *   💬 消息功能   — 自动回复开关（含语音消息自动识别+回复）+ 可展开的 AI 配置
 *   🛡️ 防护功能   — 防撤回开关
 *   🎵 变声功能   — 变声开关 + 可展开的服务器/音色配置
 *
 * 系统提示词通过 PromptFileManager 独立存储，点击后打开全屏编辑器。
 * 其余配置通过 GeekConfig.persist() 持久化到微信 SharedPreferences。
 */
public class SettingsInjector {

    private static final String BTN_TAG = "geek_panel_btn_v3";

    // 各区块颜色
    private static final int CLR_MSG      = 0xFF1565C0;  // 深蓝
    private static final int CLR_VOICE    = 0xFF6A1B9A;  // 紫色
    private static final int CLR_GUARD    = 0xFF2E7D32;  // 深绿
    private static final int CLR_CHANGER  = 0xFFE65100;  // 深橙
    private static final int CLR_PAYMENT  = 0xFFB8860B;  // 金色
    private static final int CLR_SAVE     = 0xFF07C160;  // 微信绿

    // 提示词保存回调接口
    private interface PromptSaveCallback {
        void onSaved(String newPrompt);
    }

    // ── 安装 ─────────────────────────────────────────────────────────────────

    public void install() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    if (!"com.tencent.mm".equals(activity.getPackageName())) return;
                    if (!activity.getClass().getName().toLowerCase().contains("setting")) return;

                    View deco = activity.getWindow().getDecorView();
                    if (deco.findViewWithTag(BTN_TAG) != null) return;

                    WxLog.i("注入极客面板: " + activity.getClass().getSimpleName());
                    new Handler(Looper.getMainLooper()).post(() -> injectButton(activity));
                }
            });
            WxLog.i("SettingsInjector 已安装");
        } catch (Throwable t) {
            WxLog.e("SettingsInjector 安装失败", t);
        }
    }

    // ── 浮动按钮注入 ──────────────────────────────────────────────────────────

    private void injectButton(Activity activity) {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            View deco = activity.getWindow().getDecorView();
            if (deco.findViewWithTag(BTN_TAG) != null || !(deco instanceof ViewGroup)) return;

            float d = dp(activity);
            Button btn = new Button(activity);
            btn.setText("🛡️ 极客面板");
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(14);
            btn.setTypeface(Typeface.DEFAULT_BOLD);
            btn.setTag(BTN_TAG);
            btn.setPadding(px(18, d), px(10, d), px(18, d), px(10, d));
            btn.setBackground(pill(CLR_SAVE, px(24, d)));
            if (Build.VERSION.SDK_INT >= 21) btn.setElevation(px(5, d));

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.setMargins(0, 0, px(18, d), px(120, d));
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> showPanel(activity));
            ((ViewGroup) deco).addView(btn);
        } catch (Throwable t) {
            WxLog.e("injectButton 失败", t);
        }
    }

    // ── 控制面板主体 ──────────────────────────────────────────────────────────

    private void showPanel(Activity activity) {
        try {
            GeekConfig.loadFromContext(activity);
            float d = dp(activity);

            // 开关
            Switch swAutoReply           = sw(activity, GeekConfig.AUTO_REPLY);
            Switch swAntiRevoke          = sw(activity, GeekConfig.ANTI_REVOKE);
            Switch swVoiceChanger        = sw(activity, GeekConfig.VOICE_CHANGER);
            Switch swAutoAcceptTransfer  = sw(activity, GeekConfig.AUTO_ACCEPT_TRANSFER);
            Switch swAutoAcceptHongBao   = sw(activity, GeekConfig.AUTO_ACCEPT_HONGBAO);

            // AI 配置字段（提示词已单独处理，不在此处创建 EditText）
            EditText etAiUrl     = et1(activity, d,
                    "接口地址（默认：阿里千问）", GeekConfig.AI_URL);
            EditText etAiModel   = et1(activity, d,
                    "模型名称（默认：" + GeekConfig.DEFAULT_AI_MODEL + "）", GeekConfig.AI_MODEL);
            EditText etAiKey     = et1(activity, d,
                    "API Key", GeekConfig.AI_KEY);
            EditText etAiWindow  = etNum(activity, d,
                    "批量等待 ms（默认 " + GeekConfig.DEFAULT_AI_WINDOW + "）",
                    GeekConfig.AI_WINDOW <= 0 ? "" : String.valueOf(GeekConfig.AI_WINDOW));
            EditText etAiHistory = etNum(activity, d,
                    "携带消息条数（默认 " + GeekConfig.DEFAULT_AI_HISTORY + "）",
                    GeekConfig.AI_HISTORY <= 0 ? "" : String.valueOf(GeekConfig.AI_HISTORY));

            // 支付延迟字段
            EditText etTransferDelay = etNum(activity, d,
                    "延迟 ms（默认 " + GeekConfig.DEFAULT_TRANSFER_DELAY + "）",
                    GeekConfig.TRANSFER_DELAY_MS <= 0 ? "" : String.valueOf(GeekConfig.TRANSFER_DELAY_MS));
            EditText etHongBaoDelay = etNum(activity, d,
                    "延迟 ms（默认 " + GeekConfig.DEFAULT_HONGBAO_DELAY + "）",
                    GeekConfig.HONGBAO_DELAY_MS <= 0 ? "" : String.valueOf(GeekConfig.HONGBAO_DELAY_MS));

            // 变声配置字段
            EditText etVoiceUrl     = et1(activity, d,
                    "服务器地址（默认：" + GeekConfig.DEFAULT_VOICE_URL + "）", GeekConfig.VOICE_URL);
            EditText etVoiceSpeaker = et1(activity, d,
                    "音色名称（默认：" + GeekConfig.DEFAULT_VOICE_SPEAKER + "）", GeekConfig.VOICE_SPEAKER);

            // 支付延迟面板（跟随开关展开/折叠）
            View transferDelayPanel = buildDelayPanel(activity, d, "⏱  收款延迟配置",
                    CLR_PAYMENT, "收款前等待时长", etTransferDelay);
            transferDelayPanel.setVisibility(swAutoAcceptTransfer.isChecked() ? View.VISIBLE : View.GONE);
            swAutoAcceptTransfer.setOnCheckedChangeListener((b, on) ->
                    transferDelayPanel.setVisibility(on ? View.VISIBLE : View.GONE));

            View hongbaoDelayPanel = buildDelayPanel(activity, d, "⏱  红包延迟配置",
                    CLR_PAYMENT, "领取前等待时长", etHongBaoDelay);
            hongbaoDelayPanel.setVisibility(swAutoAcceptHongBao.isChecked() ? View.VISIBLE : View.GONE);
            swAutoAcceptHongBao.setOnCheckedChangeListener((b, on) ->
                    hongbaoDelayPanel.setVisibility(on ? View.VISIBLE : View.GONE));

            // 可展开区块
            View aiPanel = buildAiPanel(activity, d,
                    etAiUrl, etAiModel, etAiKey, etAiWindow, etAiHistory);
            aiPanel.setVisibility(swAutoReply.isChecked() ? View.VISIBLE : View.GONE);
            swAutoReply.setOnCheckedChangeListener((b, on) ->
                    aiPanel.setVisibility(on ? View.VISIBLE : View.GONE));

            View voicePanel = buildVoicePanel(activity, d, etVoiceUrl, etVoiceSpeaker);
            voicePanel.setVisibility(swVoiceChanger.isChecked() ? View.VISIBLE : View.GONE);
            swVoiceChanger.setOnCheckedChangeListener((b, on) ->
                    voicePanel.setVisibility(on ? View.VISIBLE : View.GONE));

            // 根布局
            ScrollView scroll = new ScrollView(activity);
            scroll.setBackgroundColor(0xFFF0F2F5);

            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(px(14, d), px(12, d), px(14, d), px(16, d));
            scroll.addView(root);

            // 卡片 1：消息功能
            LinearLayout c1 = card(activity, d);
            c1.addView(header(activity, d, "💬  消息功能", CLR_MSG));
            c1.addView(row(activity, d, "🤖  消息自动回复", "AI 大模型智能回复来信", swAutoReply));
            c1.addView(aiPanel);
            root.addView(c1);

            // 卡片 2：防护功能
            root.addView(gap(activity, d));
            LinearLayout c2 = card(activity, d);
            c2.addView(header(activity, d, "🛡️  防护功能", CLR_GUARD));
            c2.addView(row(activity, d, "🛡️  防撤回护盾", "阻止好友撤回消息，原文永久保留", swAntiRevoke));
            root.addView(c2);

            // 卡片 3：变声功能
            root.addView(gap(activity, d));
            LinearLayout c3 = card(activity, d);
            c3.addView(header(activity, d, "🎵  变声功能", CLR_CHANGER));
            c3.addView(row(activity, d, "🎵  语音变声", "发送语音前 AI 变声（失败时不发送）", swVoiceChanger));
            c3.addView(voicePanel);
            root.addView(c3);

            // 卡片 4：支付功能
            root.addView(gap(activity, d));
            LinearLayout c4 = card(activity, d);
            c4.addView(header(activity, d, "💰  支付功能", CLR_PAYMENT));
            c4.addView(row(activity, d, "💰  自动收款",
                    "收到转账后自动打开收款页并在 2.5 秒内确认收款",
                    swAutoAcceptTransfer));
            c4.addView(transferDelayPanel);
            c4.addView(row(activity, d, "🧧  自动收红包",
                    "收到红包后自动打开并领取",
                    swAutoAcceptHongBao));
            c4.addView(hongbaoDelayPanel);
            root.addView(c4);

            // 对话框
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("⚙️  极客插件控制中心")
                    .setView(scroll)
                    .setPositiveButton("保存", null)
                    .setNegativeButton("取消", null)
                    .create();

            dialog.setOnShowListener(dlg -> {
                Button pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                Button neg = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                if (pos != null) {
                    pos.setTextColor(CLR_SAVE);
                    pos.setOnClickListener(v -> {
                        applyAndSave(activity,
                                swAntiRevoke, swAutoReply, swVoiceChanger,
                                swAutoAcceptTransfer, swAutoAcceptHongBao,
                                etAiUrl, etAiModel, etAiKey, etAiWindow, etAiHistory,
                                etVoiceUrl, etVoiceSpeaker,
                                etTransferDelay, etHongBaoDelay);
                        dialog.dismiss();
                    });
                }
                if (neg != null) neg.setTextColor(0xFF888888);
            });
            dialog.show();

        } catch (Throwable t) {
            WxLog.e("showPanel 失败", t);
        }
    }

    // ── 保存 ─────────────────────────────────────────────────────────────────

    private void applyAndSave(Activity activity,
                               Switch swAntiRevoke, Switch swAutoReply,
                               Switch swVoiceChanger, Switch swAutoAcceptTransfer,
                               Switch swAutoAcceptHongBao,
                               EditText etAiUrl, EditText etAiModel, EditText etAiKey,
                               EditText etAiWindow, EditText etAiHistory,
                               EditText etVoiceUrl, EditText etVoiceSpeaker,
                               EditText etTransferDelay, EditText etHongBaoDelay) {
        GeekConfig.ANTI_REVOKE          = swAntiRevoke.isChecked();
        GeekConfig.AUTO_REPLY           = swAutoReply.isChecked();
        GeekConfig.VOICE_CHANGER        = swVoiceChanger.isChecked();
        GeekConfig.AUTO_ACCEPT_TRANSFER = swAutoAcceptTransfer.isChecked();
        GeekConfig.AUTO_ACCEPT_HONGBAO  = swAutoAcceptHongBao.isChecked();

        GeekConfig.TRANSFER_DELAY_MS = parseIntOr(etTransferDelay.getText().toString(), 0);
        GeekConfig.HONGBAO_DELAY_MS  = parseIntOr(etHongBaoDelay.getText().toString(),  0);

        GeekConfig.VOICE_URL     = etVoiceUrl.getText().toString().trim();
        GeekConfig.VOICE_SPEAKER = etVoiceSpeaker.getText().toString().trim();

        GeekConfig.AI_URL     = etAiUrl.getText().toString().trim();
        GeekConfig.AI_MODEL   = etAiModel.getText().toString().trim();
        GeekConfig.AI_KEY     = etAiKey.getText().toString().trim();
        GeekConfig.AI_WINDOW  = parseIntOr(etAiWindow.getText().toString(),  0);
        GeekConfig.AI_HISTORY = parseIntOr(etAiHistory.getText().toString(), 0);
        // AI_PROMPT 由提示词编辑器单独保存，此处不处理

        GeekConfig.persist(activity.getApplicationContext());
        String keyStatus = GeekConfig.AI_KEY.isEmpty()
                ? "⚠️ API Key 未填写"
                : "🔑 API Key: " + GeekConfig.AI_KEY.length() + " 位";
        WxLog.i("applyAndSave: " + keyStatus + " 模型=" + GeekConfig.resolvedAiModel());
        Toast.makeText(activity.getApplicationContext(),
                "✅ 设置已保存  " + keyStatus, Toast.LENGTH_LONG).show();
    }

    // ── 可展开配置区块 ────────────────────────────────────────────────────────

    /** AI 配置区块（提示词通过全屏编辑器单独管理）。 */
    private View buildAiPanel(Activity ctx, float d,
                               EditText etUrl, EditText etModel, EditText etKey,
                               EditText etWindow, EditText etHistory) {
        LinearLayout p = new LinearLayout(ctx);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(px(16, d), px(2, d), px(16, d), px(14, d));
        p.setBackgroundColor(0xFFF8F9FC);

        p.addView(divider(ctx, d));

        // 小标题
        TextView sub = new TextView(ctx);
        sub.setText("🤖  AI 回复配置");
        sub.setTextSize(12);
        sub.setTypeface(Typeface.DEFAULT_BOLD);
        sub.setTextColor(0xFF1565C0);
        sub.setPadding(0, px(10, d), 0, px(8, d));
        p.addView(sub);

        p.addView(label(ctx, d, "接口地址"));
        p.addView(etUrl);
        p.addView(label(ctx, d, "模型名称"));
        p.addView(etModel);
        p.addView(label(ctx, d, "API Key"));
        p.addView(etKey);

        // ── 系统提示词 ────────────────────────────────────────────────────────
        p.addView(label(ctx, d, "系统提示词"));

        // 预览区（蓝底圆角卡片，最多显示 3 行）
        final TextView tvPreview = new TextView(ctx);
        tvPreview.setText(promptPreview(GeekConfig.resolvedAiPrompt()));
        tvPreview.setTextSize(12);
        tvPreview.setTextColor(0xFF444466);
        tvPreview.setMaxLines(3);
        tvPreview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvPreview.setLineSpacing(0f, 1.3f);
        tvPreview.setPadding(px(12, d), px(10, d), px(12, d), px(10, d));
        GradientDrawable previewBg = new GradientDrawable();
        previewBg.setColor(0xFFEEF2FF);
        previewBg.setCornerRadius(px(6, d));
        previewBg.setStroke(px(1, d), 0xFFBBCCFF);
        tvPreview.setBackground(previewBg);
        tvPreview.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        p.addView(tvPreview);

        // 编辑按钮
        Button btnEdit = new Button(ctx);
        btnEdit.setText("✏️  点击编辑完整提示词");
        btnEdit.setTextSize(13);
        btnEdit.setTextColor(CLR_MSG);
        btnEdit.setAllCaps(false);
        GradientDrawable editBg = new GradientDrawable();
        editBg.setColor(0xFFE3EAFF);
        editBg.setCornerRadius(px(6, d));
        editBg.setStroke(px(1, d), 0xFFAABBFF);
        btnEdit.setBackground(editBg);
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        editLp.setMargins(0, px(6, d), 0, 0);
        btnEdit.setLayoutParams(editLp);
        btnEdit.setOnClickListener(v -> showPromptEditor(ctx, d,
                GeekConfig.resolvedAiPrompt(), newPrompt -> {
                    PromptFileManager.write(ctx.getApplicationContext(), newPrompt);
                    GeekConfig.AI_PROMPT = newPrompt;
                    tvPreview.setText(promptPreview(GeekConfig.resolvedAiPrompt()));
                    Toast.makeText(ctx.getApplicationContext(),
                            "✅ 提示词已保存（" + GeekConfig.resolvedAiPrompt().length() + " 字）",
                            Toast.LENGTH_SHORT).show();
                }));
        p.addView(btnEdit);

        // 批量窗口 + 历史条数（两列）
        LinearLayout twoCol = new LinearLayout(ctx);
        twoCol.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout leftCol  = col(ctx, d, px(8, d), 0);
        LinearLayout rightCol = col(ctx, d, 0, 0);
        leftCol.addView(label(ctx, d, "批量等待 (ms)"));
        leftCol.addView(etWindow);
        rightCol.addView(label(ctx, d, "携带消息条数"));
        rightCol.addView(etHistory);
        twoCol.addView(leftCol);
        twoCol.addView(rightCol);
        p.addView(twoCol);

        // 提示
        TextView tip = new TextView(ctx);
        tip.setText("💡 兼容所有 OpenAI 协议模型（Qwen / GPT / DeepSeek / Ollama 等）\n"
                   + "   发送'重置对话'可清空当前联系人的对话上下文");
        tip.setTextSize(11);
        tip.setTextColor(0xFF999999);
        tip.setLineSpacing(px(2, d), 1f);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipLp.setMargins(0, px(10, d), 0, 0);
        tip.setLayoutParams(tipLp);
        p.addView(tip);

        return p;
    }

    private View buildVoicePanel(Activity ctx, float d,
                                  EditText etUrl, EditText etSpeaker) {
        LinearLayout p = new LinearLayout(ctx);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(px(16, d), px(2, d), px(16, d), px(14, d));
        p.setBackgroundColor(0xFFFFF8F5);

        p.addView(divider(ctx, d));

        TextView sub = new TextView(ctx);
        sub.setText("🎵  变声服务配置");
        sub.setTextSize(12);
        sub.setTypeface(Typeface.DEFAULT_BOLD);
        sub.setTextColor(0xFFE65100);
        sub.setPadding(0, px(10, d), 0, px(8, d));
        p.addView(sub);

        p.addView(label(ctx, d, "服务器地址"));
        p.addView(etUrl);
        p.addView(label(ctx, d, "音色名称"));
        p.addView(etSpeaker);

        return p;
    }

    /** 延迟配置区块（折叠在对应开关下方）。 */
    private View buildDelayPanel(Activity ctx, float d, String title, int color,
                                  String fieldLabel, EditText etDelay) {
        LinearLayout p = new LinearLayout(ctx);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(px(16, d), px(2, d), px(16, d), px(14, d));
        p.setBackgroundColor(0xFFFFFBF0);

        p.addView(divider(ctx, d));

        TextView sub = new TextView(ctx);
        sub.setText(title);
        sub.setTextSize(12);
        sub.setTypeface(Typeface.DEFAULT_BOLD);
        sub.setTextColor(color);
        sub.setPadding(0, px(10, d), 0, px(8, d));
        p.addView(sub);

        p.addView(label(ctx, d, fieldLabel));
        p.addView(etDelay);

        return p;
    }

    // ── 全屏提示词编辑器 ──────────────────────────────────────────────────────

    private void showPromptEditor(Activity activity, float d, String current,
                                   PromptSaveCallback callback) {
        // ── 编辑器 ─────────────────────────────────────────────────────────────
        EditText editor = new EditText(activity);
        editor.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setTextColor(0xFF212121);
        editor.setTextSize(13);
        editor.setLineSpacing(0f, 1.5f);
        editor.setBackgroundColor(Color.WHITE);
        int ep = px(16, d);
        editor.setPadding(ep, ep, ep, ep);
        editor.setHint("在此输入系统提示词，留空则使用内置默认提示词…");
        editor.setHintTextColor(0xFFBBBBBB);
        editor.setText(current);

        // ── 字数统计 ────────────────────────────────────────────────────────────
        final TextView tvCount = new TextView(activity);
        tvCount.setTextSize(12);
        tvCount.setTextColor(0xDDFFFFFF);
        tvCount.setText(current.length() + " 字");
        editor.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {}
            public void afterTextChanged(Editable s) {
                tvCount.setText(s.length() + " 字");
            }
        });

        // ── 标题栏 ───────────────────────────────────────────────────────────────
        LinearLayout headerBar = new LinearLayout(activity);
        headerBar.setOrientation(LinearLayout.HORIZONTAL);
        headerBar.setGravity(Gravity.CENTER_VERTICAL);
        headerBar.setBackgroundColor(CLR_MSG);
        headerBar.setPadding(px(16, d), px(14, d), px(16, d), px(14, d));

        TextView tvTitle = new TextView(activity);
        tvTitle.setText("📝  系统提示词编辑");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(15);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvTitle.setLayoutParams(titleLp);
        headerBar.addView(tvTitle);
        headerBar.addView(tvCount);

        // ── 提示信息条 ────────────────────────────────────────────────────────────
        TextView tvTip = new TextView(activity);
        tvTip.setText("💡 支持长篇角色扮演提示词  ·  留空恢复内置默认  ·  点击'恢复默认'可重置");
        tvTip.setTextSize(11);
        tvTip.setTextColor(0xFF555500);
        tvTip.setBackgroundColor(0xFFFFFDE7);
        tvTip.setPadding(px(16, d), px(10, d), px(16, d), px(10, d));
        tvTip.setLineSpacing(0f, 1.3f);

        // ── 编辑区滚动容器 ────────────────────────────────────────────────────────
        ScrollView sv = new ScrollView(activity);
        sv.setBackgroundColor(Color.WHITE);
        sv.addView(editor);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sv.setLayoutParams(svLp);

        // ── 底部按钮栏 ────────────────────────────────────────────────────────────
        View footerDivider = new View(activity);
        footerDivider.setBackgroundColor(0xFFDDDDDD);
        footerDivider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setBackgroundColor(0xFFFAFAFA);
        footer.setPadding(px(12, d), px(10, d), px(12, d), px(10, d));

        Button btnReset  = editorBtn(activity, d, "🔄 恢复默认", 0xFF888888);
        Button btnCancel = editorBtn(activity, d, "取消",         0xFF888888);
        Button btnSave   = editorBtn(activity, d, "保存",         CLR_SAVE);
        btnSave.setTypeface(Typeface.DEFAULT_BOLD);

        // 弹性间距把 [恢复默认] 推到左边，[取消][保存] 在右边
        View spacer = new View(activity);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));

        footer.addView(btnReset);
        footer.addView(spacer);
        footer.addView(btnCancel);
        footer.addView(btnSave);

        // ── 根布局 ────────────────────────────────────────────────────────────────
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(headerBar);
        root.addView(tvTip);
        root.addView(sv);
        root.addView(footerDivider);
        root.addView(footer);

        // ── 显示全屏对话框 ─────────────────────────────────────────────────────────
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(root)
                .create();
        dialog.show();
        Window win = dialog.getWindow();
        if (win != null) {
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                          ViewGroup.LayoutParams.MATCH_PARENT);
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        btnReset.setOnClickListener(v -> editor.setText(GeekConfig.resolvedDefaultPrompt()));
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnSave.setOnClickListener(v -> {
            callback.onSaved(editor.getText().toString().trim());
            dialog.dismiss();
        });
    }

    // ── 视图工厂方法 ──────────────────────────────────────────────────────────

    /** 白色圆角卡片。 */
    private LinearLayout card(Activity ctx, float d) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(px(12, d));
        c.setBackground(bg);
        if (Build.VERSION.SDK_INT >= 21) c.setElevation(px(2, d));
        c.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return c;
    }

    /** 有色区块标题行（顶部圆角与卡片匹配）。 */
    private View header(Activity ctx, float d, String title, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(px(16, d), px(10, d), px(16, d), px(10, d));
        float r = px(12, d);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        tv.setBackground(bg);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return tv;
    }

    /** 功能行（左侧标题+描述，右侧开关）。 */
    private View row(Activity ctx, float d, String title, String desc, Switch sw) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(Color.WHITE);
        row.setPadding(px(16, d), px(14, d), px(12, d), px(14, d));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginEnd(px(8, d));
        texts.setLayoutParams(tlp);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextSize(15);
        tvTitle.setTextColor(0xFF1A1A1A);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(tvTitle);

        TextView tvDesc = new TextView(ctx);
        tvDesc.setText(desc);
        tvDesc.setTextSize(11);
        tvDesc.setTextColor(0xFF999999);
        tvDesc.setPadding(0, px(2, d), 0, 0);
        texts.addView(tvDesc);

        row.addView(texts);
        row.addView(sw);
        return row;
    }

    /** 功能行之间的细分隔线。 */
    private View divRow(Activity ctx, float d) {
        View v = new View(ctx);
        v.setBackgroundColor(0xFFEEEEEE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    /** 可展开面板内部的较粗分隔线。 */
    private View divider(Activity ctx, float d) {
        View v = new View(ctx);
        v.setBackgroundColor(0xFFE0E0E0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(1, d));
        lp.setMargins(0, 0, 0, px(4, d));
        v.setLayoutParams(lp);
        return v;
    }

    /** 卡片之间的透明间距。 */
    private View gap(Activity ctx, float d) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(8, d)));
        return v;
    }

    /** EditText 上方的小灰色标签。 */
    private TextView label(Activity ctx, float d, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(0xFF777777);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, px(8, d), 0, px(3, d));
        tv.setLayoutParams(lp);
        return tv;
    }

    /** 单行文本输入框（URL / 模型名 / Key / 音色）。
     *  注意：setInputType 必须在 setText 之前调用，否则部分 Android 版本会清除文本。 */
    private EditText et1(Activity ctx, float d, String hint, String value) {
        EditText et = styledEt(ctx, d, hint);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        et.setText(value);
        return et;
    }

    /** 数字输入框（批量窗口 / 历史条数）。 */
    private EditText etNum(Activity ctx, float d, String hint, String value) {
        EditText et = styledEt(ctx, d, hint);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(value);
        return et;
    }

    /** 统一的 EditText 基础样式（不调用 setInputType / setText，由调用方负责）。 */
    private EditText styledEt(Activity ctx, float d, String hint) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setHintTextColor(0xFFBBBBBB);
        et.setTextColor(0xFF1A1A1A);
        et.setTextSize(13);
        et.setPadding(px(12, d), px(10, d), px(12, d), px(10, d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF5F5F5);
        bg.setCornerRadius(px(6, d));
        bg.setStroke(px(1, d), 0xFFDDDDDD);
        et.setBackground(bg);
        et.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return et;
    }

    /** 开关控件。 */
    private Switch sw(Activity ctx, boolean checked) {
        Switch s = new Switch(ctx);
        s.setChecked(checked);
        s.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    /** 全屏编辑器底部的文字按钮。 */
    private Button editorBtn(Activity ctx, float d, String text, int textColor) {
        Button btn = new Button(ctx);
        btn.setText(text);
        btn.setTextSize(14);
        btn.setTextColor(textColor);
        btn.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(px(4, d));
        btn.setBackground(bg);
        btn.setPadding(px(14, d), px(8, d), px(14, d), px(8, d));
        btn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return btn;
    }

    /** 两列数字输入的列容器。 */
    private LinearLayout col(Activity ctx, float d, int marginEnd, int marginStart) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(marginEnd);
        lp.setMarginStart(marginStart);
        col.setLayoutParams(lp);
        return col;
    }

    /** 胶囊形 GradientDrawable（用于注入按钮）。 */
    private GradientDrawable pill(int color, int radius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radius);
        return gd;
    }

    // ── 工具方法 ─────────────────────────────────────────────────────────────

    /** 生成提示词预览文本（换行替换为空格，超过 120 字截断）。 */
    private static String promptPreview(String prompt) {
        if (prompt == null || prompt.isEmpty()) return "(使用内置默认提示词)";
        String preview = prompt.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return preview.length() > 120 ? preview.substring(0, 120) + "…" : preview;
    }

    private static int px(int dp, float density) { return Math.round(dp * density); }
    private static float dp(Activity ctx) { return ctx.getResources().getDisplayMetrics().density; }

    private static int parseIntOr(String s, int def) {
        try {
            int v = Integer.parseInt(s.trim());
            return v > 0 ? v : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
