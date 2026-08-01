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
    }

    /** 钩住指定名字的所有回调方法（ttnet 内部类也覆盖） */
    private void hookCallback(Class<?> clazz, final String methodName) {
        try {
            XposedBridge.hookAllMethods(clazz, methodName, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!cfgEnabled) return;
                    String url = findUrlField(param.thisObject);
                    String extra = "";
                    if ("onSucceeded".equals(methodName) && param.args != null && param.args.length > 0) {
                        extra = " info=" + param.args[0].getClass().getSimpleName();
                    }
                    log("<< " + methodName + (url != null ? " [" + url + "]" : "") + extra);
                }
            });
        } catch (Throwable t) {
            log("hook " + methodName + " 失败: " + t);
        }
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

    /** 读取 /sdcard/Download/fanqieshow.conf 用户配置 */
    private static void loadConfig() {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File conf = new File(dir, CONFIG_FILE);
            if (!conf.exists()) {
                log("配置文件不存在: " + conf.getAbsolutePath() + " (使用默认值)");
                return;
            }
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
            log("配置读取成功: " + conf.getAbsolutePath());
        } catch (Throwable t) {
            log("配置读取失败: " + t);
        }
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
