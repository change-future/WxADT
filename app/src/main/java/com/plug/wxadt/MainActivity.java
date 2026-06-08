package com.plug.wxadt;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    /** WeChatHook 会 hook 此方法，使其返回 true */
    public static boolean isModuleActive() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // LSPosed 1.8+ 的 XSharedPreferences 可直接读取 MODE_PRIVATE 文件，无需 WORLD_READABLE
        SharedPreferences prefs = getSharedPreferences(Config.PREF_NAME, MODE_PRIVATE);

        TextView tvStatus = findViewById(R.id.tv_status);
        if (isModuleActive()) {
            tvStatus.setText("模块已激活 ✓");
            tvStatus.setTextColor(Color.parseColor("#4CAF50"));
        } else {
            tvStatus.setText("模块未激活，请在 LSPosed 管理器中启用");
            tvStatus.setTextColor(Color.parseColor("#F44336"));
        }

        Switch swDbInsert = findViewById(R.id.sw_log_db_insert);
        Switch swDbUpdate = findViewById(R.id.sw_log_db_update);

        swDbInsert.setChecked(prefs.getBoolean(Config.KEY_LOG_DB_INSERT, true));
        swDbUpdate.setChecked(prefs.getBoolean(Config.KEY_LOG_DB_UPDATE, true));

        swDbInsert.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean(Config.KEY_LOG_DB_INSERT, checked).apply());

        swDbUpdate.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean(Config.KEY_LOG_DB_UPDATE, checked).apply());
    }
}
