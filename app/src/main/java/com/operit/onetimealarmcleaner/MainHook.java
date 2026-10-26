package com.operit.onetimealarmcleaner;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * ColorOS 时钟 一次性闹钟自动清理 (v8)
 *
 * v7 已验证：hook CR.update 命中 enabled=0 后删除 alarms 记录，数据库真实删除。
 * v8 新增：删除后补发 notifyChange，让 App 列表即时刷新（无需退出重进）。
 *   - 通知整表 URI  content://.../alarm
 *   - 通知单条 URI  content://.../alarm/<id>
 *   - 通知调度表    content://.../schedules
 *   - 加 notifyChange 探针，确认通知真的发出去了
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "OTAC";
    private static final String TARGET_PKG = "com.coloros.alarmclock";
    private static final String AUTH = "com.coloros.alarmclock.alarmclock";
    private static final String ALARM_BASE = "content://" + AUTH + "/alarm";
    private static final String SCHED_BASE = "content://" + AUTH + "/schedules";

    private static volatile boolean busy = false;
    private static volatile File logFile = null;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;
        initLogFile();
        log("===== 注入 pid=" + android.os.Process.myPid() + " proc=" + lpparam.processName + " =====");

        hookUpdate(lpparam);
        hookNotify(lpparam);

        log("===== 挂载完成 =====");
    }

    private void initLogFile() {
        try {
            File dir = new File("/sdcard/Android/data/com.coloros.alarmclock/files");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "otac.log");
            FileWriter w = new FileWriter(f, true);
            w.write("---- session ----\n");
            w.close();
            logFile = f;
        } catch (Throwable t) {
            logFile = null;
        }
    }

    private void hookUpdate(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> cr = XposedHelpers.findClass("android.content.ContentResolver", lp.classLoader);
            XposedBridge.hookAllMethods(cr, "update", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.args[0] instanceof Uri)) return;
                        String s = param.args[0].toString();
                        if (!s.startsWith(ALARM_BASE)) return;
                        String tail = s.substring(ALARM_BASE.length());
                        if (tail.startsWith("/")) tail = tail.substring(1);
                        if (tail.length() == 0 || tail.contains("/")) return;
                        long alarmId;
                        try { alarmId = Long.parseLong(tail); } catch (Throwable t) { return; }
                        if (alarmId <= 0) return;
                        ContentValues cv = null;
                        for (int i = 1; i < param.args.length; i++) {
                            if (param.args[i] instanceof ContentValues) { cv = (ContentValues) param.args[i]; break; }
                        }
                        if (cv == null) return;
                        Integer en = cv.getAsInteger("enabled");
                        if (en == null || en.intValue() != 0) return;
                        Object self = param.thisObject;
                        if (!(self instanceof ContentResolver)) {
                            log("thisObject 不是 ContentResolver: " + self);
                            return;
                        }
                        ContentResolver resolver = (ContentResolver) self;
                        log("捕获停用 alarmId=" + alarmId + " values=" + cv);
                        tryCleanup(resolver, alarmId);
                    } catch (Throwable t) {
                        log("UPD_ERR " + t);
                    }
                }
            });
            log("探针 CR.update OK");
        } catch (Throwable t) {
            log("HOOK_FAIL " + t);
        }
    }

    /** 探针：记录 App 侧 notifyChange 是否发生（判断 observer 链路） */
    private void hookNotify(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> cr = XposedHelpers.findClass("android.content.ContentResolver", lp.classLoader);
            XposedBridge.hookAllMethods(cr, "notifyChange", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.args[0] instanceof Uri)) return;
                        String s = param.args[0].toString();
                        if (s.contains("alarmclock")) {
                            log("notifyChange uri=" + s);
                        }
                    } catch (Throwable ignored) { }
                }
            });
            log("探针 CR.notifyChange OK");
        } catch (Throwable t) {
            log("HOOK_NOTIFY_FAIL " + t);
        }
    }

    /** 纯 Provider 判定 + 删除 + 补发通知 */
    private void tryCleanup(ContentResolver resolver, long alarmId) {
        if (busy) return;
        busy = true;
        Cursor c = null;
        try {
            Uri target = Uri.parse(ALARM_BASE + "/" + alarmId);
            c = resolver.query(target,
                    new String[]{"_id", "daysofweek", "loopSwitch", "specialAlarmDays", "loopResetDays", "hour", "minutes"},
                    null, null, null);
            if (c == null || !c.moveToFirst()) {
                log("查询无结果 alarmId=" + alarmId);
                return;
            }
            int idIdx = c.getColumnIndex("_id");
            int dowIdx = c.getColumnIndex("daysofweek");
            int loopIdx = c.getColumnIndex("loopSwitch");
            int spIdx = c.getColumnIndex("specialAlarmDays");
            int lrdIdx = c.getColumnIndex("loopResetDays");
            int hIdx = c.getColumnIndex("hour");
            int mIdx = c.getColumnIndex("minutes");

            long id = idIdx >= 0 ? c.getLong(idIdx) : alarmId;
            int dow = dowIdx >= 0 ? c.getInt(dowIdx) : 0;
            int loopSwitch = loopIdx >= 0 ? c.getInt(loopIdx) : 0;
            String special = spIdx >= 0 ? c.getString(spIdx) : null;
            String lrd = lrdIdx >= 0 ? c.getString(lrdIdx) : null;
            int hh = hIdx >= 0 ? c.getInt(hIdx) : -1;
            int mm = mIdx >= 0 ? c.getInt(mIdx) : -1;

            log("读取 id=" + id + " " + hh + ":" + mm
                    + " daysofweek=" + dow
                    + " loopSwitch=" + loopSwitch
                    + " specialAlarmDays=\"" + special + "\""
                    + " loopResetDays=\"" + lrd + "\"");

            if (dow != 0) {
                log("判定 daysofweek=" + dow + " 非零 -> 重复闹钟，保留");
                return;
            }
            if (loopSwitch != 0) {
                log("判定 loopSwitch=" + loopSwitch + " 非零 -> 循环闹钟，保留");
                return;
            }
            if (hasDayContent(special)) {
                log("判定 specialAlarmDays=\"" + special + "\" 非空 -> 特殊日闹钟，保留");
                return;
            }
            if (hasDayContent(lrd)) {
                log("判定 loopResetDays=\"" + lrd + "\" 非空 -> 循环休息日，保留");
                return;
            }

            // 先关闭 cursor，避免 delete 期间持有窗口
            try { c.close(); } catch (Throwable ignored) { }
            c = null;

            log("判定 确认为一次性闹钟 -> 执行删除");
            int n = resolver.delete(target, null, null);
            log("DONE 删除 alarmId=" + alarmId + " rows=" + n);

            if (n > 0) {
                notifyAll(resolver, alarmId);
            }
        } catch (Throwable t) {
            log("CLEAN_ERR " + t);
        } finally {
            if (c != null) { try { c.close(); } catch (Throwable ignored) {} }
            busy = false;
        }
    }

    /** 补发通知：覆盖整表 URI / 单条 URI / 调度表，尽力让 App 列表重载 */
    private void notifyAll(ContentResolver resolver, long alarmId) {
        String[] uris = new String[]{
                ALARM_BASE,
                ALARM_BASE + "/" + alarmId,
                SCHED_BASE
        };
        for (String u : uris) {
            try {
                resolver.notifyChange(Uri.parse(u), null);
                log("已补发 notifyChange uri=" + u);
            } catch (Throwable t) {
                log("notifyChange 失败 uri=" + u + " err=" + t);
            }
        }
    }

    private static boolean hasDayContent(String s) {
        if (s == null) return false;
        String t = s.replace("#", "").replace("/", "").trim();
        return t.length() > 0;
    }

    private static void log(String msg) {
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + msg;
        File f = logFile;
        if (f != null) {
            try {
                FileWriter w = new FileWriter(f, true);
                w.write(line);
                w.write("\n");
                w.close();
            } catch (Throwable ignored) {}
        }
        try { Log.i(TAG, msg); } catch (Throwable ignored) {}
    }
}
