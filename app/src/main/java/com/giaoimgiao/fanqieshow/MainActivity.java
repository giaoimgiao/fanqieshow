package com.giaoimgiao.fanqieshow;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;

/**
 * FanqieShow 配置界面
 * 可调整: 作家等级(LV/等级名)、阅读人数、在读人数、每日收益、月度稿费
 * 配置写入 /sdcard/Download/fanqieshow.conf，注入进程读取
 *
 * 作者: giaoimgiao
 * 仓库: https://github.com/giaoimgiao/fanqieshow
 */
public class MainActivity extends Activity {

    private static final String CONFIG_FILE = "fanqieshow.conf";
    private static final String REPO_URL = "https://github.com/giaoimgiao/fanqieshow";

    private Switch swEnabled;
    private EditText etLevel;
    private EditText etLevelName;
    private EditText etReaders;
    private EditText etReading;
    private EditText etIncome;
    private EditText etMonthly;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 检查存储权限（写 /sdcard/Download 需要）
        if (!Environment.isExternalStorageManager()) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        }

        buildUi();
        loadConfig();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("🍅 FanqieShow 番茄装逼");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(6));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("本机显示层修改 · v1.0 监听版\n(修改值将在 v2 生效，当前版本记录接口日志)");
        sub.setTextSize(12);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 0, 0, dp(16));
        root.addView(sub);

        // ---- 总开关 ----
        LinearLayout rowSw = new LinearLayout(this);
        rowSw.setOrientation(LinearLayout.HORIZONTAL);
        rowSw.setGravity(Gravity.CENTER_VERTICAL);
        TextView tvSw = new TextView(this);
        tvSw.setText("模块总开关");
        tvSw.setTextSize(16);
        tvSw.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1f));
        swEnabled = new Switch(this);
        rowSw.addView(tvSw);
        rowSw.addView(swEnabled);
        root.addView(rowSw);

        // ---- 输入项 ----
        etLevel = addInput(root, "作家等级 (LV数字，如 1/2/12)", InputType.TYPE_CLASS_NUMBER);
        etLevelName = addInput(root, "等级名 (金番/殿堂/白金等)", InputType.TYPE_CLASS_TEXT);
        etReaders = addInput(root, "每日阅读人数", InputType.TYPE_CLASS_NUMBER);
        etReading = addInput(root, "每日在读人数", InputType.TYPE_CLASS_NUMBER);
        etIncome = addInput(root, "每日收益 (元)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etMonthly = addInput(root, "每月稿费 (元)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        // ---- 保存按钮 ----
        Button btnSave = new Button(this);
        btnSave.setText("💾 保存配置");
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveConfig();
            }
        });
        root.addView(btnSave);

        // ---- 作者信息 ----
        TextView author = new TextView(this);
        author.setText("\n作者: giaoimgiao\n开源发布 · 仅供学习交流");
        author.setTextSize(13);
        author.setGravity(Gravity.CENTER);
        author.setPadding(0, dp(16), 0, dp(8));
        root.addView(author);

        // ---- 仓库链接 ----
        Button btnRepo = new Button(this);
        btnRepo.setText("⭐ GitHub 仓库");
        btnRepo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法打开浏览器: " + e, Toast.LENGTH_SHORT).show();
                }
            }
        });
        root.addView(btnRepo);

        TextView tip = new TextView(this);
        tip.setText("\n提示:\n· 配置保存在 " + Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS) + "/" + CONFIG_FILE + "\n"
                + "· 修改后重启番茄作家助手生效\n"
                + "· 网络日志: /data/data/com.bytedance.writer_assistant_flutter/files/fanqieshow.log");
        tip.setTextSize(11);
        tip.setPadding(0, dp(12), 0, 0);
        root.addView(tip);

        setContentView(scroll);
    }

    private EditText addInput(LinearLayout root, String hint, int inputType) {
        TextView label = new TextView(this);
        label.setText(hint);
        label.setTextSize(13);
        label.setPadding(0, dp(10), 0, dp(2));
        root.addView(label);

        EditText et = new EditText(this);
        et.setInputType(inputType);
        et.setSingleLine(true);
        et.setHint("留空 = 不修改该项");
        root.addView(et);
        return et;
    }

    private void loadConfig() {
        try {
            File conf = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), CONFIG_FILE);
            if (!conf.exists()) return;
            java.util.Map<String, String> kv = new java.util.HashMap<>();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(conf), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) kv.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
            br.close();
            swEnabled.setChecked(!"0".equals(kv.get("enabled")));
            etLevel.setText(kv.get("level"));
            etLevelName.setText(kv.get("level_name"));
            etReaders.setText(kv.get("readers"));
            etReading.setText(kv.get("reading"));
            etIncome.setText(kv.get("income"));
            etMonthly.setText(kv.get("monthly"));
        } catch (Exception ignored) {
        }
    }

    private void saveConfig() {
        StringBuilder sb = new StringBuilder();
        sb.append("# FanqieShow config by giaoimgiao\n");
        sb.append("# 仓库: https://github.com/giaoimgiao/fanqieshow\n");
        sb.append("enabled=").append(swEnabled.isChecked() ? "1" : "0").append("\n");
        sb.append("level=").append(etLevel.getText().toString().trim()).append("\n");
        sb.append("level_name=").append(etLevelName.getText().toString().trim()).append("\n");
        sb.append("readers=").append(etReaders.getText().toString().trim()).append("\n");
        sb.append("reading=").append(etReading.getText().toString().trim()).append("\n");
        sb.append("income=").append(etIncome.getText().toString().trim()).append("\n");
        sb.append("monthly=").append(etMonthly.getText().toString().trim()).append("\n");

        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            File conf = new File(dir, CONFIG_FILE);
            FileOutputStream fos = new FileOutputStream(conf);
            OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
            w.write(sb.toString());
            w.flush();
            w.close();
            Toast.makeText(this, "✅ 配置已保存到 " + conf.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "❌ 保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}