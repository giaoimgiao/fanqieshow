package com.giaoimgiao.fanqieshow;

import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * FanqieShow - 番茄作家助手显示层修改模块
 * v1: 监听 ttnet Cronet 网络层，记录所有请求 URL + 响应类型，写日志文件；
 *     读取用户配置（等级/在读/收益），为 v2 改写打基础。
 *
 * 作者: giaoimgiao
 * 仓库: https://github.com/giaoimgiao/fanqieshow
 */
public class Main implements IXposedHookLoadPackage {

    private static final String TARGET_PKG = "com.bytedance.writer_assistant_flutter";
    private static final String CRONET_IMPL = "com.ttnet.org.chromium.net.impl.CronetUrlRequest";
    private static final String CONFIG_FILE = "fanqieshow.conf";

    // ---- 配置缓存 ----
    private static volatile boolean cfgEnabled = true;
    private static volatile String cfgLevel = "1";
    private static volatile String cfgLevelName = "";
    private static volatile String cfgReaders = "";
    private static volatile String cfgReading = "";
    private static volatile String cfgIncome = "";
    private static volatile String cfgMonthly = "";
    // v2.7 新增
    private static volatile String cfgReadIncome = "";   // 每日阅读收益
    private static volatile String cfgTomatoIncome = ""; // X月番茄收益
    private static volatile String cfgCompleteRate = ""; // 完读率
    private static volatile String cfgPursueRate = "";   // 追读率
    // v2.9 新增: 逐章覆盖 "0:93.5,1:92.1,..." (index:值)
    private static volatile String cfgChapterComplete = "";
    private static volatile String cfgChapterFollow = "";

    // ---- 日志 ----
    private static String logPath = null;
    private static final Object LOG_LOCK = new Object();

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }

        // 日志写在目标应用私有目录（注入进程可写，无需额外权限）
        logPath = "/data/data/" + TARGET_PKG + "/files/fanqieshow.log";
        log("========== FanqieShow v1 注入成功 ==========");
        log("target=" + TARGET_PKG + " ver=" + lpparam.processName);

        // 读取用户配置
        loadConfig();
        log("配置: enabled=" + cfgEnabled + " level=" + cfgLevel + "/" + cfgLevelName
                + " 阅读=" + cfgReaders + " 在读=" + cfgReading
                + " 日收益=" + cfgIncome + " 月稿费=" + cfgMonthly);

        // 尝试 hook ttnet CronetUrlRequest
        try {
            final Class<?> clazz = XposedHelpers.findClass(CRONET_IMPL, lpparam.classLoader);
            log("找到 CronetUrlRequest: " + clazz.getName());

            // 1) 构造器: 捕获请求 URL
            XposedBridge.hookAllConstructors(clazz, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!cfgEnabled) return;
                    String url = findUrlField(param.thisObject);
                    if (url != null) {
                        log(">> REQUEST: " + url);
                    }
                    ByteBuffer buf = findByteBufferField(param.thisObject);
                    if (buf != null) {
                        log("   响应缓冲: " + buf.remaining() + " bytes");
                    }
                }
            });

            // 2) 回调方法: 记录响应阶段
            hookCallback(clazz, "onResponseStarted");
            hookCallback(clazz, "onReadCompleted");
            hookCallback(clazz, "onSucceeded");
            hookCallback(clazz, "onFailed");

            log("hook 安装完成 (v1 监听模式)");
        } catch (Throwable t) {
            log("hook 失败: " + t);
            XposedBridge.log("FanqieShow hook error: " + t);
        }

        // ===== v2: hook 应用层最终回调 VersionSafeCallbacks$UrlRequestCallback =====
        // 此处 ByteBuffer 是 App 即将读取的响应体: dump 完整数据 + 改写装逼数值
        try {
            final Class<?> cb = XposedHelpers.findClass(
                    "com.ttnet.org.chromium.net.impl.VersionSafeCallbacks$UrlRequestCallback",
                    lpparam.classLoader);
            log("找到 VersionSafeCallbacks$UrlRequestCallback: " + cb.getName());

            XposedBridge.hookAllMethods(cb, "onReadCompleted", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!cfgEnabled) return;
                    loadConfig(); // 每次响应前重载配置, 改配置实时生效
                    String url = null;
                    try {
                        // args[1] = UrlResponseInfo, 标准 getUrl()
                        url = (String) param.args[1].getClass().getMethod("getUrl").invoke(param.args[1]);
                    } catch (Throwable ignored) {
                    }
                    if (url == null || !isInteresting(url)) return;
                    ByteBuffer bb = (ByteBuffer) param.args[2];
                    String body = dumpBuffer(bb);
                    if (body == null) return;
                    log("CALLBACK << " + shortUrl(url) + " BODY(" + body.length() + "): " + body);

                    // v2: 改写响应
                    String modified = modifyResponse(url, body);
            if (modified != null && !modified.equals(body)) {
                try {
                    byte[] nb = modified.getBytes("UTF-8");
                    // v2.5 溢出保护: Cronet分块buffer容量有限(如level_config第一块≈29916B),
                    // 替换后若超出容量直接put会抛BufferOverflowException并冒泡给App → "网络错误".
                    // 超容量时放弃本次改写, 保证App正常展示(宁可不改也不崩).
                    if (nb.length > bb.capacity()) {
                        log("    ⚠️ 跳过改写(溢出保护): " + shortUrl(url) + " 新" + nb.length
                                + "B > 缓冲" + bb.capacity() + "B, 原" + body.length() + "B");
                    } else {
                        bb.clear();
                        bb.put(nb);
                        // 关键: 不调用flip()! Cronet回调时buffer语义为 position=写入量/limit=capacity,
                        // 应用会自行flip()后读取; 若这里flip则应用再flip会读到limit=0 → 显示"--"
                        log("    ✏️ 已改写: " + shortUrl(url) + " (" + body.length() + " -> " + nb.length + "B)");
                    }
                } catch (Throwable t) {
                    log("    改写失败: " + t);
                }
            }
            }
            });

            XposedBridge.hookAllMethods(cb, "onSucceeded", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!cfgEnabled) return;
                    String url = null;
                    try {
                        url = (String) param.args[1].getClass().getMethod("getUrl").invoke(param.args[1]);
                    } catch (Throwable ignored) {
                    }
                    if (url != null && isInteresting(url)) {
                        log("CALLBACK DONE << " + shortUrl(url));
                    }
                }
            });

            log("v2 改写 hook 安装完成");
        } catch (Throwable t) {
            log("v2 hook 失败: " + t);
            XposedBridge.log("FanqieShow v2 hook error: " + t);
        }
    }

    /** v2: 按接口 URL 改写响应 JSON (仅本机显示层, 不改服务器数据) */
    private static String modifyResponse(String url, String body) {
        if (body == null || !body.startsWith("{")) return null;
        try {
            if (url.contains("statistic/overview/book_common")) {
                org.json.JSONObject root = new org.json.JSONObject(body);
                org.json.JSONObject data = root.optJSONObject("data");
                if (data == null) return null;
                if (cfgIncome != null && !cfgIncome.isEmpty())
                    data.put("last_daily_income", cfgIncome);          // 每日收益
                if (cfgReading != null && !cfgReading.isEmpty())
                    data.put("last_reader_uv_14day_count", cfgReading); // 在读UV
                if (cfgReaders != null && !cfgReaders.isEmpty())
                    data.put("last_read_count", cfgReaders);            // 阅读人数
                if (cfgMonthly != null && !cfgMonthly.isEmpty())
                    data.put("last_monthly_income", cfgMonthly);        // 月度收益
                // v2.7 新增: 每日阅读收益 / X月番茄收益 / 完读率
                if (cfgReadIncome != null && !cfgReadIncome.isEmpty())
                    data.put("last_daily_read_income", cfgReadIncome);  // 每日阅读收益
                if (cfgTomatoIncome != null && !cfgTomatoIncome.isEmpty())
                    data.put("last_monthly_novel_income", cfgTomatoIncome); // X月番茄收益
                if (cfgCompleteRate != null && !cfgCompleteRate.isEmpty())
                    data.put("last_read_complete_rate", cfgCompleteRate);   // 完读率
                return root.toString();
            }
            // ===== v2.7: 质量分析/概览新接口 stats/book_common/v1 =====
            // 完读率 read_completion_rate / 追读率 pursue_read_rate / 在读UV
            if (url.contains("statistic/stats/book_common")) {
                org.json.JSONObject root = new org.json.JSONObject(body);
                org.json.JSONObject data = root.optJSONObject("data");
                if (data == null) return null;
                if (cfgCompleteRate != null && !cfgCompleteRate.isEmpty())
                    data.put("read_completion_rate", cfgCompleteRate); // 完读率
                if (cfgPursueRate != null && !cfgPursueRate.isEmpty())
                    data.put("pursue_read_rate", cfgPursueRate);       // 追读率
                if (cfgReading != null && !cfgReading.isEmpty())
                    data.put("reader_uv_14day_count", cfgReading);     // 在读UV
                return root.toString();
            }
            // ===== v2.7/v2.8/v2.9: 质量分析-章节列表 stats/chapter_list/v0 =====
            // 两个模式: 章节读完率 read_completion_rate / 章节跟读率 follow_read_rate
            // v2.8: 范围配置"90-99" -> 每章确定性伪随机; v2.9: 逐章覆盖(优先) + 章节列表导出给前端
            if (url.contains("statistic/stats/chapter_list")) {
                org.json.JSONObject root = new org.json.JSONObject(body);
                org.json.JSONObject data = root.optJSONObject("data");
                if (data == null) return null;
                org.json.JSONArray arr = data.optJSONArray("chapter_stats_list");
                if (arr != null && (cfgCompleteRate != null && !cfgCompleteRate.isEmpty()
                        || cfgPursueRate != null && !cfgPursueRate.isEmpty()
                        || cfgChapterComplete != null && !cfgChapterComplete.isEmpty()
                        || cfgChapterFollow != null && !cfgChapterFollow.isEmpty())) {
                    exportChapters(arr); // v2.9: 导出章节列表给前端UI
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject c = arr.optJSONObject(i);
                        if (c == null) continue;
                        if (cfgCompleteRate != null && !cfgCompleteRate.isEmpty())
                            c.put("read_completion_rate", chapterValue(cfgCompleteRate, cfgChapterComplete, i));
                        if (cfgPursueRate != null && !cfgPursueRate.isEmpty())
                            c.put("follow_read_rate", chapterValue(cfgPursueRate, cfgChapterFollow, i));
                    }
                    return root.toString();
                }
            }
            // ===== v2.6: account/info 等级数据源改写 —— 真正的等级切换 =====
            // 该接口下发: author_level_id(当前等级ID) + point(当前等级分) + point_detail(明细)
            // 伪造两者 -> App 本地用 point 对照 level_config.levels[].point 匹配到高等级对象,
            // 卡片/颜色/权益全部自动切换(用户要求的"系统hook到真正的高等级").
            if (url.contains("home/account/info")) {
                org.json.JSONObject root = new org.json.JSONObject(body);
                org.json.JSONObject data = root.optJSONObject("data");
                if (data != null && cfgLevel != null && !cfgLevel.isEmpty()) {
                    int[] tgt = levelTarget(cfgLevel);
                    if (tgt != null) {
                        data.put("author_level_id", tgt[0]);
                        data.put("point", tgt[1]);
                        // point_detail 明细同步: 总分全部并入成长分(task_point), 其余置0
                        org.json.JSONArray pd = data.optJSONArray("point_detail");
                        if (pd != null) {
                            for (int i = 0; i < pd.length(); i++) {
                                org.json.JSONObject o = pd.optJSONObject(i);
                                if (o != null && "task_point".equals(o.optString("key"))) {
                                    o.put("point", tgt[1]);
                                } else if (o != null) {
                                    o.put("point", 0);
                                }
                            }
                        }
                        return root.toString();
                    }
                }
            }
            if (url.contains("level_config") && cfgLevelName != null && !cfgLevelName.isEmpty()) {
                // v2.4: 等级卡"整卡替换" —— 用户当前等级(Lv.1, id=200)对象的全部样式字段
                // 换成最高等级(Lv.4)的样式: 图标/勋章/背景/动效资源URL lv1->lv4(已逐一验证CDN存在),
                // 颜色替换为殿堂金色系. 仅改本机显示层, App匹配逻辑(id/point)不动, 进度条不乱.
                String esc = cfgLevelName.replace("\\", "\\\\").replace("\"", "\\\"");
                String r = body;
                // 1) 所有等级名 "Lv.N" -> 配置名 (无论匹配哪级都显示配置名, 如"破解大神")
                r = r.replaceAll("\"name\":\"Lv\\.[0-9]+\"", "\"name\":\"" + esc + "\"");
                // 2) Lv.1对象全部资源URL -> Lv.4 (icon/勋章/封面/背景/动效)
                r = r.replace("lv1", "lv4");
                r = r.replace("author_level_1", "author_level_4");
                // 2b) Lv.4不存在的两个背景资源 -> 用已验证存在的 385_app_bg_lv4_new.png
                r = r.replace("author-level/level_bg_lv4.png", "author-level/385-level/385_app_bg_lv4_new.png");
                r = r.replace("author-level/mine_bg_lv4.png", "author-level/385-level/385_app_bg_lv4_new.png");
                // 3) Lv.1专属颜色 -> 殿堂金色系 (这些色值在响应中仅Lv.1使用, 全局唯一)
                r = r.replace("0xFF295EAF", "0xFFD4AF37");   // app_theme_color 金
                r = r.replace("0xFF96B5E4", "0xFFFFD700");   // app_card_colors 金
                r = r.replace("0xFFDAE6F9", "0xFFB8860B");   // app_card_colors 暗金
                r = r.replace("0xFF85B0F0", "0xFFFFD700");   // app_progress_colors 金
                r = r.replace("0xFF5E8ED4", "0xFFDAA520");   // app_progress_colors 暗金
                r = r.replace("#295EAF", "#B8860B");         // pc_theme_color
                r = r.replace("#5E8ED4", "#DAA520");         // pc_progress_color
                r = r.replace("#85B0F0", "#FFD700");         // pc_progress_color
                r = r.replace("#DAE6F9", "#FFE4B5");         // pc_home_tag_background
                // 4) 头衔文本 "作家Lv.1" -> 配置名; 升级条件文案 -> 满级文案
                r = r.replace("\"fanqie_title_text\":\"作家Lv.1\"", "\"fanqie_title_text\":\"" + esc + "\"");
                r = r.replace("(成长分达到400分)", "(已达成殿堂级)");
                if (!r.equals(body)) {
                    return r;
                }
            }
            if (url.contains("statistic/overview/book_list") || url.contains("statistic/overview/book_common")) {
                // book_list 作品列表页 read_count 也可以改(可选扩展)
                org.json.JSONObject root = new org.json.JSONObject(body);
                org.json.JSONArray arr = root.optJSONObject("data") == null ? null
                        : root.optJSONObject("data").optJSONArray("stats_book_list");
                if (arr != null && cfgReaders != null && !cfgReaders.isEmpty()) {
                    for (int i = 0; i < arr.length(); i++) {
                        arr.getJSONObject(i).put("read_count", cfgReaders);
                    }
                    return root.toString();
                }
            }
        } catch (Throwable t) {
            log("modifyResponse 异常: " + t);
        }
        return null;
    }

    /**
     * v2.6: 配置等级 -> 目标等级 {author_level_id, point}
     * level 配置: 0~5 = Lv.0~Lv.5, 6 = 金番作家(id=500), 7 = 殿堂作家(id=600)
     * point 取超过目标阈值(确保匹配到该等级, Lv.5给20万远超市值).
     */
    private static int[] levelTarget(String lv) {
        if (lv == null) return null;
        switch (lv.trim()) {
            case "0": return new int[]{100, 0};
            case "1": return new int[]{200, 500};
            case "2": return new int[]{300, 2000};
            case "3": return new int[]{400, 6000};
            case "4": return new int[]{416, 16000};
            case "6": return new int[]{500, 200000}; // 金番作家(签约制, 无等级分, id直指)
            case "7": return new int[]{600, 200000}; // 殿堂作家(签约制, 无等级分, id直指)
            case "5": // Lv.5
            default: return new int[]{432, 200000};
        }
    }

    /**
     * v2.8: 章节率值生成. 支持两种配置:
     *  单值 "95"      -> 所有章节统一 95
     *  范围 "90-99"   -> 每章按 index 确定性伪随机在区间内取值(刷新不变, 各章不同, 更真实)
     */
    private static String varyRate(String cfg, int idx) {
        int dash = cfg.indexOf('-');
        if (dash <= 0) return cfg; // 无范围, 单值直返
        try {
            double lo = Double.parseDouble(cfg.substring(0, dash).trim());
            double hi = Double.parseDouble(cfg.substring(dash + 1).trim());
            if (hi <= lo) return cfg;
            // 固定seed: 同一章每次刷新值一致, 避免"刷新就变"的违和感
            java.util.Random r = new java.util.Random(idx * 1000003L + 0x9E3779B9L);
            double v = lo + r.nextDouble() * (hi - lo);
            return String.format("%.2f", v);
        } catch (Throwable t) {
            return cfg;
        }
    }

    /**
     * v2.9: 章节率取值. 优先级: 逐章覆盖(chCfg) > 范围/单值(cfg).
     */
    private static String chapterValue(String cfg, String chCfg, int idx) {
        String ov = getChapterOverride(chCfg, idx);
        if (ov != null) return ov;
        return varyRate(cfg, idx);
    }

    /** 解析逐章覆盖配置 "0:93.5,1:92.1" -> 第idx章的值, 无则null */
    private static String getChapterOverride(String chCfg, int idx) {
        if (chCfg == null || chCfg.isEmpty()) return null;
        try {
            for (String part : chCfg.split(",")) {
                int colon = part.indexOf(':');
                if (colon > 0 && Integer.parseInt(part.substring(0, colon).trim()) == idx) {
                    return part.substring(colon + 1).trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** v2.9: 导出章节列表(总章数+每章标题)到前端UI读取文件 (sdcard优先, 失败则App私有目录) */
    private static void exportChapters(org.json.JSONArray arr) {
        try {
            StringBuilder sb = new StringBuilder("{\"total\":").append(arr.length()).append(",\"chapters\":[");
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject c = arr.optJSONObject(i);
                if (c == null) continue;
                String t = c.optString("title", "第" + (i + 1) + "章");
                sb.append("{\"idx\":").append(i).append(",\"title\":\"")
                        .append(t.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")).append("\"},");
            }
            if (arr.length() > 0) sb.setLength(sb.length() - 1);
            sb.append("]}");
            byte[] bytes = sb.toString().getBytes("UTF-8");
            boolean ok = false;
            try {
                java.io.File f = new java.io.File("/sdcard/Download/fanqieshow_chapters.json");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                fos.write(bytes);
                fos.close();
                ok = true;
            } catch (Throwable ignored) {
            }
            try {
                java.io.File f2 = new java.io.File("/data/data/com.bytedance.writer_assistant_flutter/files/fanqieshow_chapters.json");
                java.io.FileOutputStream fos2 = new java.io.FileOutputStream(f2);
                fos2.write(bytes);
                fos2.close();
                ok = true;
            } catch (Throwable ignored) {
            }
            if (ok) log("    章节列表已导出 " + arr.length() + " 章");
        } catch (Throwable ignored) {
        }
    }

    /** 钩住指定名字的所有回调方法（ttnet 内部类也覆盖） */
    private void hookCallback(Class<?> clazz, final String methodName) {
        try {
            XposedBridge.hookAllMethods(clazz, methodName, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!cfgEnabled) return;
                    String url = findUrlField(param.thisObject);
                    if (url != null && !isInteresting(url)) return; // 只记录业务接口
                    log("<< " + methodName + (url != null ? " [" + shortUrl(url) + "]" : ""));

                    // v1.1: 抓取响应体 (onReadCompleted 第三参/字段中的 ByteBuffer)
                    if ("onReadCompleted".equals(methodName)) {
                        String body = extractBodyFromArgs(param.args);
                        if (body == null) {
                            body = extractBodyFromFields(param.thisObject);
                        }
                        if (body != null) {
                            log("    BODY(" + body.length() + "): " + body);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            log("hook " + methodName + " 失败: " + t);
        }
    }

    /** 从回调参数中提取响应体 (标准 Cronet onReadCompleted 第三参为 ByteBuffer) */
    private static String extractBodyFromArgs(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            String s = dumpBuffer(a);
            if (s != null) return s;
        }
        return null;
    }

    /** 从对象字段中提取响应体 */
    private static String extractBodyFromFields(Object obj) {
        if (obj == null) return null;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    String s = dumpBuffer(v);
                    if (s != null) return s;
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** 将 ByteBuffer/byte[] 转为可读字符串 (判断 JSON/protobuf)，过长截断 */
    private static String dumpBuffer(Object v) {
        if (v == null) return null;
        try {
            byte[] bytes = null;
            if (v instanceof ByteBuffer) {
                ByteBuffer bb = (ByteBuffer) v;
                if (bb.position() == 0 && bb.limit() == bb.capacity()) return null; // 空缓冲
                int len = Math.min(bb.position() > 0 ? bb.position() : bb.limit(), 32768);
                bytes = new byte[len];
                ByteBuffer dup = bb.duplicate();
                dup.position(0);
                dup.get(bytes, 0, len);
            } else if (v instanceof byte[]) {
                bytes = (byte[]) v;
                if (bytes.length == 0) return null;
            } else {
                return null;
            }
            if (bytes == null || bytes.length == 0) return null;
            // 判断: JSON 文本 or 二进制 (UTF-8中文 >=0x80 视为文本候选)
            boolean text = true;
            for (int i = 0; i < Math.min(bytes.length, 128); i++) {
                int b = bytes[i] & 0xFF;
                if (b == 0 || (b < 0x20 && b != '\n' && b != '\r' && b != '\t')) { text = false; break; }
            }
            if (text) {
                return new String(bytes, "UTF-8");
            } else {
                // 二进制: 同时输出 UTF-8 预览便于识别 (可能是误判的JSON)
                StringBuilder sb = new StringBuilder("[BINARY ");
                sb.append(bytes.length).append("B:");
                for (int i = 0; i < Math.min(bytes.length, 24); i++) {
                    sb.append(String.format("%02X", bytes[i] & 0xFF)).append(' ');
                }
                sb.append("] UTF8预览: ");
                String preview = new String(bytes, "UTF-8");
                if (preview.length() > 300) preview = preview.substring(0, 300);
                return sb.append(preview.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "?")).toString();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** 是否是需要关注的业务接口 */
    private static boolean isInteresting(String url) {
        String u = url.toLowerCase();
        // v2.9: 放宽为记录所有 /app/ 接口(含字数完读等未知名接口), 便于抓包定位
        if (u.contains("/app/")) return true;
        return u.contains("/statistic/") || u.contains("level_config") || u.contains("income")
            || u.contains("book_daily") || u.contains("book_summary") || u.contains("monthly")
            || u.contains("user/info") || u.contains("wallet") || u.contains("medal")
            || u.contains("growth_task") || u.contains("book_list") || u.contains("author")
            || u.contains("account/info"); // v2.5: 作者账户信息(等级分嫌疑: 当前等级分726不在此前任何已记录接口)
    }

    /** URL 截断显示 */
    private static String shortUrl(String url) {
        int q = url.indexOf('?');
        return q > 0 ? url.substring(0, q) : url;
    }

    /** 反射遍历字段找 URL（String 且 http 开头） */
    private static String findUrlField(Object obj) {
        if (obj == null) return null;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == String.class) {
                    try {
                        f.setAccessible(true);
                        String s = (String) f.get(obj);
                        if (s != null && (s.startsWith("http://") || s.startsWith("https://"))) {
                            return s;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** 反射找 ByteBuffer 字段（响应体缓冲区，用于判断 JSON/protobuf） */
    private static ByteBuffer findByteBufferField(Object obj) {
        if (obj == null) return null;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (ByteBuffer.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        return (ByteBuffer) f.get(obj);
                    } catch (Throwable ignored) {
                    }
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** 读取用户配置: 优先番茄私有目录(必可读), 兜底 /sdcard/Download */
    private static void loadConfig() {
        String sigBefore = cfgSignature();
        boolean loaded = false;
        // 1) 番茄应用私有目录(模块注入后必可读写, 由root同步脚本写入)
        File f1 = new File("/data/data/" + TARGET_PKG + "/files/" + CONFIG_FILE);
        if (parseConfigFile(f1)) {
            loaded = true;
        }
        // 2) 兜底: /sdcard/Download (番茄无权限时EACCES, 自动跳过)
        if (!loaded) {
            File f2 = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), CONFIG_FILE);
            if (parseConfigFile(f2)) {
                loaded = true;
            }
        }
        if (!loaded) {
            if (!cfgLoggedMissing) {
                log("配置文件不可读 (番茄私有目录与Download均失败), 使用默认值");
                cfgLoggedMissing = true;
            }
            return;
        }
        cfgLoggedMissing = false;
        String sigAfter = cfgSignature();
        if (!sigBefore.equals(sigAfter)) {
            log("配置已加载: enabled=" + cfgEnabled + " level=" + cfgLevel + "/" + cfgLevelName
                    + " 阅读=" + cfgReaders + " 在读=" + cfgReading
                    + " 日收益=" + cfgIncome + " 月稿费=" + cfgMonthly);
        }
    }

    private static boolean cfgLoggedMissing = false;

    /** 解析单个配置文件, 成功返回 true */
    private static boolean parseConfigFile(File conf) {
        if (conf == null || !conf.exists()) return false;
        try {
            Map<String, String> kv = new HashMap<>();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(conf), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) {
                    kv.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
            br.close();
            if (kv.containsKey("enabled")) cfgEnabled = "1".equals(kv.get("enabled")) || "true".equals(kv.get("enabled"));
            if (kv.containsKey("level")) cfgLevel = kv.get("level");
            if (kv.containsKey("level_name")) cfgLevelName = kv.get("level_name");
            if (kv.containsKey("readers")) cfgReaders = kv.get("readers");
            if (kv.containsKey("reading")) cfgReading = kv.get("reading");
            if (kv.containsKey("income")) cfgIncome = kv.get("income");
        if (kv.containsKey("monthly")) cfgMonthly = kv.get("monthly");
        // v2.7 新增
        if (kv.containsKey("read_income")) cfgReadIncome = kv.get("read_income");
        if (kv.containsKey("tomato_income")) cfgTomatoIncome = kv.get("tomato_income");
        if (kv.containsKey("complete_rate")) cfgCompleteRate = kv.get("complete_rate");
        if (kv.containsKey("pursue_rate")) cfgPursueRate = kv.get("pursue_rate");
        // v2.9: 逐章覆盖
        if (kv.containsKey("chapter_complete_rate")) cfgChapterComplete = kv.get("chapter_complete_rate");
        if (kv.containsKey("chapter_follow_rate")) cfgChapterFollow = kv.get("chapter_follow_rate");
        return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前配置内容签名(用于检测变化) */
    private static String cfgSignature() {
        return (cfgEnabled ? "1" : "0") + "|" + cfgLevel + "|" + cfgLevelName + "|"
                + cfgReaders + "|" + cfgReading + "|" + cfgIncome + "|" + cfgMonthly + "|"
                + cfgReadIncome + "|" + cfgTomatoIncome + "|" + cfgCompleteRate + "|" + cfgPursueRate + "|"
                + cfgChapterComplete + "|" + cfgChapterFollow;
    }

    /** 追加写日志（进程内线程安全） */
    private static void log(String msg) {
        if (logPath == null) return;
        String ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String full = "[" + ts + "] " + msg + "\n";
        synchronized (LOG_LOCK) {
            try {
                File f = new File(logPath);
                if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(f, true);
                OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
                w.write(full);
                w.flush();
                w.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
