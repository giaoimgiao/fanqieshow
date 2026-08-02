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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * FanqieShow 配置界面 v2.9
 * 分组折叠: 作家等级 / 作品数据 / 质量分析 / 章节逐章编辑
 * 配置写入 /sdcard/Download/fanqieshow.conf，注入进程实时读取
 *
 * 章节逐章编辑: 读取 hook 导出的 fanqieshow_chapters.json(打开质量分析页自动生成)
 * 每章可单独设置 章节读完率/章节跟读率, 保存生成 "0:93.5,1:92.1" 逐章覆盖配置
 * 范围快捷填充: 输入 "92-99" 一键按确定性伪随机填充所有章节
 *
 * 作者: giaoimgiao
 * 仓库: https://github.com/giaoimgiao/fanqieshow
 */
public class MainActivity extends Activity {

    private static final String CONFIG_FILE = "fanqieshow.conf";
    private static final String CHAPTERS_FILE = "fanqieshow_chapters.json";
    private static final String REPO_URL = "https://github.com/giaoimgiao/fanqieshow";

    // ---- 总开关 ----
    private Switch swEnabled;

    // ---- 作家等级 ----
    private EditText etLevel;
    private EditText etLevelName;

    // ---- 作品数据 ----
    private EditText etReaders;
    private EditText etReading;
    private EditText etIncome;
    private EditText etMonthly;
    private EditText etReadIncome;   // 每日阅读收益
    private EditText etTomatoIncome; // X月番茄收益

    // ---- 质量分析 ----
    private EditText etCompleteRate; // 完读率(章节读完率)
    private EditText etPursueRate;   // 追读率(章节跟读率)

    // ---- 章节逐章编辑 ----
    private LinearLayout chapterListContainer;
    private TextView chapterStatus;
    private EditText etRangeComplete;
    private EditText etRangeFollow;
    private List<EditText> chCompleteEds = new ArrayList<>();
    private List<EditText> chFollowEds = new ArrayList<>();
    private int chapterTotal = 0;

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
        loadChapters();
    }

    // ==================== UI 构建 ====================

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("🍅 FanqieShow 番茄装逼 v2.9");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(4));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("本机显示层修改 · 等级/数据/质量分析/逐章编辑\n保存后重启番茄生效(数据页可实时)");
        sub.setTextSize(12);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 0, 0, dp(12));
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

        // ===== 分组1: 作家等级 =====
        LinearLayout gLevel = addSection(root, "🏅 作家等级", true);
        etLevel = addInput(gLevel, "等级数值 (0-7, 如 5=Lv.5 / 6=金番 / 7=殿堂)", InputType.TYPE_CLASS_NUMBER);
        etLevelName = addInput(gLevel, "等级名 (留空=自动匹配, 或填 殿堂/金番/白金)", InputType.TYPE_CLASS_TEXT);

        // ===== 分组2: 作品数据 =====
        LinearLayout gData = addSection(root, "📊 作品数据 (每日)", true);
        etReaders = addInput(gData, "阅读人数", InputType.TYPE_CLASS_NUMBER);
        etReading = addInput(gData, "在读人数", InputType.TYPE_CLASS_NUMBER);
        etIncome = addInput(gData, "每日收益 (元)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etMonthly = addInput(gData, "每月稿费 (元)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etReadIncome = addInput(gData, "每日阅读收益 (元)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etTomatoIncome = addInput(gData, "X月番茄收益 (元)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        // ===== 分组3: 质量分析 =====
        LinearLayout gQuality = addSection(root, "📈 质量分析", true);
        etCompleteRate = addInput(gQuality, "章节读完率 (单值如 95 或范围如 92-99)", InputType.TYPE_CLASS_TEXT);
        etPursueRate = addInput(gQuality, "章节跟读率 (单值如 90 或范围如 88-96)", InputType.TYPE_CLASS_TEXT);

        // ===== 分组4: 章节逐章编辑 =====
        LinearLayout gChapter = addSection(root, "📚 章节逐章编辑", true);

        chapterStatus = new TextView(this);
        chapterStatus.setText("章节列表未加载。\n请先在番茄App打开「质量分析」页(触发 hook 导出章节), 再点下方按钮加载。");
        chapterStatus.setTextSize(12);
        chapterStatus.setPadding(0, dp(6), 0, dp(6));
        gChapter.addView(chapterStatus);

        // 范围快捷填充行
        LinearLayout rangeRow1 = new LinearLayout(this);
        rangeRow1.setOrientation(LinearLayout.HORIZONTAL);
        rangeRow1.setGravity(Gravity.CENTER_VERTICAL);
        etRangeComplete = new EditText(this);
        etRangeComplete.setHint("范围如 92-99");
        etRangeComplete.setInputType(InputType.TYPE_CLASS_TEXT);
        etRangeComplete.setSingleLine(true);
        etRangeComplete.setLayoutParams(new LinearLayout.LayoutParams(0, dp(44), 1f));
        Button btnFillComplete = new Button(this);
        btnFillComplete.setText("填充完读");
        rangeRow1.addView(etRangeComplete);
        rangeRow1.addView(btnFillComplete);
        gChapter.addView(rangeRow1);

        LinearLayout rangeRow2 = new LinearLayout(this);
        rangeRow2.setOrientation(LinearLayout.HORIZONTAL);
        rangeRow2.setGravity(Gravity.CENTER_VERTICAL);
        etRangeFollow = new EditText(this);
        etRangeFollow.setHint("范围如 88-96");
        etRangeFollow.setInputType(InputType.TYPE_CLASS_TEXT);
        etRangeFollow.setSingleLine(true);
        etRangeFollow.setLayoutParams(new LinearLayout.LayoutParams(0, dp(44), 1f));
        Button btnFillFollow = new Button(this);
        btnFillFollow.setText("填充跟读");
        rangeRow2.addView(etRangeFollow);
        rangeRow2.addView(btnFillFollow);
        gChapter.addView(rangeRow2);

        btnFillComplete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fillRange(etRangeComplete, chCompleteEds);
            }
        });
        btnFillFollow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fillRange(etRangeFollow, chFollowEds);
            }
        });

        // 加载/清空按钮
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnReload = new Button(this);
        btnReload.setText("🔄 重新加载章节");
        Button btnClear = new Button(this);
        btnClear.setText("🗑 清空逐章");
        btnRow.addView(btnReload);
        btnRow.addView(btnClear);
        gChapter.addView(btnRow);

        btnReload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadChapters();
            }
        });
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (EditText ed : chCompleteEds) ed.setText("");
                for (EditText ed : chFollowEds) ed.setText("");
                Toast.makeText(MainActivity.this, "已清空全部逐章输入(保存后即回退到范围/单值)", Toast.LENGTH_SHORT).show();
            }
        });

        chapterListContainer = new LinearLayout(this);
        chapterListContainer.setOrientation(LinearLayout.VERTICAL);
        gChapter.addView(chapterListContainer);

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
        tip.setText("\n提示:\n· 章节逐章格式: 每章两个输入框(完读/跟读), 留空=该章走范围/单值\n"
                + "· 逐章保存后生成 chapter_complete_rate / chapter_follow_rate\n"
                + "· 配置保存在 " + Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS) + "/" + CONFIG_FILE + "\n"
                + "· 修改后重启番茄作家助手生效\n"
                + "· 网络日志: /data/data/com.bytedance.writer_assistant_flutter/files/fanqieshow.log");
        tip.setTextSize(11);
        tip.setPadding(0, dp(12), 0, 0);
        root.addView(tip);

        setContentView(scroll);
    }

    /** 分组折叠: header 点击展开/收起, 返回内容容器 */
    private LinearLayout addSection(LinearLayout root, String title, boolean expanded) {
        TextView header = new TextView(this);
        header.setText((expanded ? "▾ " : "▸ ") + title);
        header.setTextSize(16);
        header.setPadding(dp(12), dp(10), dp(12), dp(10));
        header.setBackgroundColor(0xFFE8E8E8);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(6), dp(2), dp(6), dp(2));
        content.setVisibility(expanded ? View.VISIBLE : View.GONE);

        header.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean show = content.getVisibility() != View.VISIBLE;
                content.setVisibility(show ? View.VISIBLE : View.GONE);
                header.setText((show ? "▾ " : "▸ ") + title);
            }
        });

        root.addView(header);
        root.addView(content);
        return content;
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

    // ==================== 章节列表加载/渲染 ====================

    private void loadChapters() {
        chCompleteEds.clear();
        chFollowEds.clear();
        chapterListContainer.removeAllViews();
        chapterTotal = 0;

        // 已保存的逐章覆盖值(用于回填)
        Map<Integer, String> savedComplete = new HashMap<>();
        Map<Integer, String> savedFollow = new HashMap<>();
        parseChapterCfg(savedComplete, savedFollow);

        File f = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), CHAPTERS_FILE);
        if (!f.exists()) {
            chapterStatus.setText("未找到 " + CHAPTERS_FILE + "\n请先在番茄App打开「质量分析」页触发导出, 再点「重新加载章节」");
            return;
        }
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.optJSONArray("chapters");
            chapterTotal = root.optInt("total", arr != null ? arr.length() : 0);
            if (arr == null || arr.length() == 0) {
                chapterStatus.setText("章节列表为空(共 " + chapterTotal + " 章)");
                return;
            }

            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.optJSONObject(i);
                int idx = c != null ? c.optInt("idx", i) : i;
                String t = c != null ? c.optString("title", "第" + (i + 1) + "章") : "第" + (i + 1) + "章";

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(3), 0, dp(3));

                TextView label = new TextView(this);
                label.setText((i + 1) + ".");
                label.setTextSize(12);
                label.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(40)));
                label.setGravity(Gravity.CENTER_VERTICAL);

                TextView tvTitle = new TextView(this);
                tvTitle.setText(t.length() > 8 ? t.substring(0, 8) : t);
                tvTitle.setTextSize(11);
                tvTitle.setSingleLine(true);
                tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, dp(40), 1f));
                tvTitle.setGravity(Gravity.CENTER_VERTICAL);

                EditText etC = new EditText(this);
                etC.setHint("完读");
                etC.setTextSize(12);
                etC.setSingleLine(true);
                etC.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                etC.setLayoutParams(new LinearLayout.LayoutParams(dp(78), dp(40)));
                String vc = savedComplete.get(idx);
                if (vc != null) etC.setText(vc);

                EditText etF = new EditText(this);
                etF.setHint("跟读");
                etF.setTextSize(12);
                etF.setSingleLine(true);
                etF.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                etF.setLayoutParams(new LinearLayout.LayoutParams(dp(78), dp(40)));
                String vf = savedFollow.get(idx);
                if (vf != null) etF.setText(vf);

                row.addView(label);
                row.addView(tvTitle);
                row.addView(etC);
                row.addView(etF);
                chapterListContainer.addView(row);
                chCompleteEds.add(etC);
                chFollowEds.add(etF);
            }
            chapterStatus.setText("✅ 已加载 " + chapterTotal + " 章 (留空=走范围/单值, 数字=逐章覆盖)");
        } catch (Exception e) {
            chapterStatus.setText("加载失败: " + e.getMessage() + "\n请打开番茄质量分析页后重试");
        }
    }

    /** 解析已保存逐章配置 "0:93.5,1:92.1" 回填 */
    private void parseChapterCfg(Map<Integer, String> complete, Map<Integer, String> follow) {
        File f = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), CONFIG_FILE);
        if (!f.exists()) return;
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if ("chapter_complete_rate".equals(k)) parseInto(v, complete);
                else if ("chapter_follow_rate".equals(k)) parseInto(v, follow);
            }
            br.close();
        } catch (Exception ignored) {
        }
    }

    private void parseInto(String cfg, Map<Integer, String> map) {
        if (cfg == null || cfg.isEmpty()) return;
        try {
            for (String pair : cfg.split(",")) {
                int colon = pair.indexOf(':');
                if (colon <= 0) continue;
                map.put(Integer.parseInt(pair.substring(0, colon).trim()),
                        pair.substring(colon + 1).trim());
            }
        } catch (Exception ignored) {
        }
    }

    /** 范围快捷填充: 与 hook 侧 varyRate 同一确定性算法(seed=idx*1000003+0x9E3779B9) */
    private void fillRange(EditText rangeEd, List<EditText> eds) {
        String cfg = rangeEd.getText().toString().trim();
        if (cfg.isEmpty()) {
            Toast.makeText(this, "请先输入范围, 如 92-99", Toast.LENGTH_SHORT).show();
            return;
        }
        if (eds.isEmpty()) {
            Toast.makeText(this, "章节列表为空, 请先加载章节", Toast.LENGTH_SHORT).show();
            return;
        }
        int n = 0;
        for (int i = 0; i < eds.size(); i++) {
            eds.get(i).setText(varyRate(cfg, i));
            n++;
        }
        Toast.makeText(this, "已按范围 " + cfg + " 填充 " + n + " 章", Toast.LENGTH_SHORT).show();
    }

    /** 与 hook 侧一致: 范围 "92-99" -> 确定性伪随机 */
    private String varyRate(String cfg, int idx) {
        int dash = cfg.indexOf('-');
        if (dash <= 0) return cfg;
        try {
            double lo = Double.parseDouble(cfg.substring(0, dash).trim());
            double hi = Double.parseDouble(cfg.substring(dash + 1).trim());
            long seed = idx * 1000003L + 0x9E3779B9L;
            Random r = new Random(seed);
            double v = lo + r.nextDouble() * (hi - lo);
            return String.format(Locale.US, "%.1f", v);
        } catch (Exception e) {
            return cfg;
        }
    }

    // ==================== 配置读写 ====================

    private void loadConfig() {
        try {
            File conf = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), CONFIG_FILE);
            if (!conf.exists()) return;
            Map<String, String> kv = new HashMap<>();
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(conf), "UTF-8"));
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
            etReadIncome.setText(kv.get("read_income"));
            etTomatoIncome.setText(kv.get("tomato_income"));
            etCompleteRate.setText(kv.get("complete_rate"));
            etPursueRate.setText(kv.get("pursue_rate"));
        } catch (Exception ignored) {
        }
    }

    private void saveConfig() {
        StringBuilder sb = new StringBuilder();
        sb.append("# FanqieShow config by giaoimgiao v2.9\n");
        sb.append("# 仓库: https://github.com/giaoimgiao/fanqieshow\n");
        sb.append("enabled=").append(swEnabled.isChecked() ? "1" : "0").append("\n");
        sb.append("level=").append(etLevel.getText().toString().trim()).append("\n");
        sb.append("level_name=").append(etLevelName.getText().toString().trim()).append("\n");
        sb.append("readers=").append(etReaders.getText().toString().trim()).append("\n");
        sb.append("reading=").append(etReading.getText().toString().trim()).append("\n");
        sb.append("income=").append(etIncome.getText().toString().trim()).append("\n");
        sb.append("monthly=").append(etMonthly.getText().toString().trim()).append("\n");
        sb.append("read_income=").append(etReadIncome.getText().toString().trim()).append("\n");
        sb.append("tomato_income=").append(etTomatoIncome.getText().toString().trim()).append("\n");
        sb.append("complete_rate=").append(etCompleteRate.getText().toString().trim()).append("\n");
        sb.append("pursue_rate=").append(etPursueRate.getText().toString().trim()).append("\n");

        // 逐章覆盖: 只收集非空输入
        sb.append("chapter_complete_rate=").append(buildChapterCfg(chCompleteEds)).append("\n");
        sb.append("chapter_follow_rate=").append(buildChapterCfg(chFollowEds)).append("\n");

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

    /** 收集非空逐章输入 -> "0:93.5,1:92.1" */
    private String buildChapterCfg(List<EditText> eds) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < eds.size(); i++) {
            String v = eds.get(i).getText().toString().trim();
            if (v.isEmpty()) continue;
            if (sb.length() > 0) sb.append(",");
            sb.append(i).append(":").append(v);
        }
        return sb.toString();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}