# ColorOS 时钟逆向分析：一次性闹钟删除机制

本文档记录定位「一次性闹钟响铃结束后不自动删除」问题时走过的完整路径，包括每一条错误路线的教训。

- 目标应用：`com.coloros.alarmclock`
- 设备：OPPO PJA110 / Android 16
- 工具：JADX + smali 阅读 + LSPosed 动态 Hook 验证

---

## 一、先搞清楚数据模型

### 两个表，不要混

| URI | 实际表名 | 说明 |
|---|---|---|
| `content://com.coloros.alarmclock.alarmclock/alarm` | **`alarms`** | 闹钟定义 |
| `content://com.coloros.alarmclock.alarmclock/schedules` | `schedules` | 调度实例 |

**第一个坑**：URI path 是 `/alarm`，但真实表名是 `alarms`。

```bash
# 报错：no such column: alarm_id
content query --uri content://com.coloros.alarmclock.alarmclock/alarm --projection alarm_id
```

`alarm_id` 列属于 **`schedules` 表**，`alarms` 表没有这一列。

### `schedules` 表字段（实测）

```
_id, year, month, day, hour, minutes, alarmtime, snooze_time,
alarm_state, alarm_id, alarm_hour, alarm_minute, alarm_mills,
daysofweek, enabled, alerttype, message, snooze, alert, ringName,
volume, vibrate, backGround, deleteAfterUse
```

### `alarms` 表关键字段

```
_id, hour, minutes, daysofweek, alarmtime, enabled, alerttype,
message, snooze, alert, ringName, volume, vibrate,
workdaySwitch, holidaySwitch, closeOnceNextTime, closeOncePreviousTime,
specialAlarmDays, loopSwitch, loopCycleDays, loopID,
loopWorkDays, loopAlarmNumber, loopDay, loopResetDays,
deleteAfterUse, garbSwitch
```

---

## 二、响铃结束的真实调用链

### 广播入口

```
AlarmManager: sending alarm ... action change_state
              component com.coloros.alarmclock/...AlarmStateManager
```

**第二个坑（最关键的坑）**：`change_state` 广播 Data URI 里携带的 id，是 **`schedules` 表的主键**，不是闹钟 `_id`。

实测日志：
```
09:41:00.028 CHANGE_STATE id=328 state=2 gid=431
```

这里的 `328` 是 `schedules._id`，而对应的闹钟是 `alarms._id=133`。我最初直接用这个 id 去删闹钟，全部失败——这就是早期版本清理一直没效果的直接原因。

### `AlarmStateManager.onReceive`

```java
public void onReceive(Context ctx, Intent intent) {
    if ("indicator".equals(...)) return;   // 直接 return
    goAsync();                              // 异步！
    s5.u0.k(ctx);
    n7.h.a(new s5.h2(...));                // 投递到线程池
}
```

**注意**：真正的处理逻辑是**异步**执行的，`onReceive` 里只投递任务。`s5.h2.run()` 再调 `AlarmStateManager.a(...)` 桥接方法 → `q(Context, Intent)`。

### 完整写库序列（实测抓取）

```
09:41:00.025 ONRECV action=change_state
09:41:00.028 CHANGE_STATE id=328 state=2 gid=431 from=onReceive
09:41:00.049 CR.update .../schedules/328  values=alarm_state=0
09:41:00.055 CR.delete .../schedules  a1=alarm_id=133 AND _id!=328
09:41:03.233 w3.g [Context,328]
09:41:03.233 CR.delete .../schedules/328
09:41:03.283 CR.update .../alarm/133  values=closeOnceNextTime=0 enabled=0 closeOncePreviousTime=0  ← 目标
09:41:03.292 w3.e [Context,133]
```

### `state` 值的实测语义

早期从 smali 推断 `state==4` 是「garb 分支」、`state==1/3` 是响铃中/超时，结果**完全错了**。

实测：**一次性闹钟响铃结束走 `state=2`**（09:41 与 09:46 两次均为 `state=2`）。

教训：不要靠 smali 里的分支字面值猜语义，要看 `AlarmStateManager.q` 里每个分支实际干了什么。

### `w3` 类方法表（实测确认）

| 方法 | 签名 | 语义 |
|---|---|---|
| `w3.d` | `(Context, J, Boolean)` | 取消通知（`NotificationManager.cancel`） |
| `w3.e` | `(Context, J)` | **清理孤儿调度**（不是删闹钟！） |
| `w3.f` | `(J, Ls5/c2;)` | 构造 `ContentValues` |
| `w3.g` | `(Context, J)` | 删单条 schedule |
| `w3.h` | `(Context, J)` | 按 `alarm_id` 批量删 schedules |
| `w3.i` | `(Context, J, J)` | 双条件删 |
| `w3.u` | `(Context, J)` | `t(ctx, "alarm_id=<id>")` |

**第三个坑**：`w3.e` 听起来像「删除闹钟」，实际是「清理孤儿调度」。对已经 `enabled=0` 的闹钟它不会走删除分支。我花了很多轮才纠正这个误解。

`np_apk_dex_xref` 确认：`w3.h` 只被 `w3.e` 一个地方调用。

---

## 三、为什么最终选 `ContentResolver.update`

### 排除的候选点

| 候选 Hook 点 | 为何放弃 |
|---|---|
| `w3.e` | 语义是清理调度，不是删闹钟 |
| `AlarmStateManager.onReceive` | 异步投递，真正逻辑不在这里；且 id 是 schedules 主键 |
| `AlarmStateManager.q` | 分支太复杂，state 语义容易误判 |
| `AlarmStateManager.j` | 只在 TIMEZONE_CHANGED / TIME_SET 时调用，不是响铃路径 |
| `w3.g` | 只删 schedule，不涉及闹钟本体 |

### 最终方案

**`ContentResolver.update` + `alarm/<id>` + `enabled=0`**

理由：

1. **时机精确**：就是响铃结束、系统停用闹钟的那一行
2. **信号可靠**：重复闹钟永不写这一行，一次性闹钟必写
3. **不依赖 App 内部结构**：只用公开 API
4. **无异步时序问题**：发生在写入瞬间，同步可控

---

## 四、踩过的工程坑（很重要）

### 坑 1：`ContentResolver.mContext` 反射不到

v6 用 `XposedHelpers.getObjectField(resolver, "mContext")` 取 Context，实测**失效**——部分实现里该字段为空或名不同。

**解法**：不要 Context。`param.thisObject` 就是 `ContentResolver` 实例，它本身有 `query` / `delete` / `notifyChange` 方法。v7 起彻底不需要 Context。

### 坑 2：logcat 被冲爆 → 误判「Hook 没触发」

这是耗时最长的一个坑。多次出现「日志一片空白」，一度以为 Hook 根本不生效，换了好几轮 Hook 点。

**真相**：客户端（AI 助手）自己每轮往 logcat 写几 MB 日志：

```
10-26 09:36:15 D DeepseekProvider: Final DeepSeek reasoning mode request body: Part 6/142
```

单次请求 142 个 Part，环形缓冲区几分钟就失效。

**解法**：日志改写入文件，完全绕开 logcat。

```java
File dir = new File("/sdcard/Android/data/com.coloros.alarmclock/files");
File f = new File(dir, "otac.log");
// 直接 FileWriter 追加，靠 root 读
```

`XposedBridge.log` 同样会被冲掉（走 `LSPosedFramework` tag）。

**教训**：调试 Xposed 模块，第一件事就是把日志写文件，不要信 logcat。

### 坑 3：数据表名与 URI path 不一致

URI 是 `/alarm`，表名是 `alarms`。写 `content query --projection alarm_id` 会直接报 `no such column`。

### 坑 4：判定要「宁可保留」

删除是不可逆操作。判定逻辑必须保守：

- 四个条件全部满足才删
- 任何查询异常、字段缺失 → 放行（不删）
- `busy` 标志防重入

---

## 五、最终判定逻辑

全部从 Provider cursor 读取，不反射 App 私有类：

```java
int dow      = cursor.getInt(daysofweek);       // 必须是 0
int loop     = cursor.getInt(loopSwitch);       // 必须是 0
String sp    = cursor.getString(specialAlarmDays);  // 去 # / 后必须为空
String lrd   = cursor.getString(loopResetDays);     // 去 # / 后必须为空

if (dow != 0) return;               // 重复闹钟
if (loop != 0) return;              // 循环闹钟
if (hasContent(sp)) return;         // 特殊日
if (hasContent(lrd)) return;        // 循环休息日

// 确认是一次性闹钟 → 删除
resolver.delete(Uri.parse(ALARM_BASE + "/" + alarmId), null, null);
```

实测数据：
- 一次性闹钟：`daysofweek=0, loopSwitch=0, specialAlarmDays="#", loopResetDays="#"`
- 重复闹钟：`daysofweek=27`（0b0011011）

---

## 六、UI 刷新问题

删除后列表不即时消失，必须退出重进。

### 原因

`OplusAlarmMiniFragment.onResume()` → `Y0(true)` → `adapter.notifyDataSetChanged()` + `a1(list)`

但 `a1(List)` 实际只更新标题文字（「X 个闹钟」），`notifyDataSetChanged()` 只是**重绘 adapter 已有的 List 快照**，不会重新查库。

所以删除数据库记录后，adapter 里那份旧的 `List` 还在，UI 无感知。

### 解法

删除后主动补发 `notifyChange`，覆盖所有可能的注册方式：

```java
resolver.notifyChange(Uri.parse(ALARM_BASE + "/" + alarmId), null);
resolver.notifyChange(Uri.parse(ALARM_BASE), null);
resolver.notifyChange(Uri.parse(SCHED_BASE), null);
```

---

## 七、环境备忘

### 快速取证命令

```bash
# 看闹钟定义
su -c "content query --uri content://com.coloros.alarmclock.alarmclock/alarm \
  --projection _id:hour:minutes:enabled:daysofweek"

# 看调度实例
su -c "content query --uri content://com.coloros.alarmclock.alarmclock/schedules \
  --projection _id:alarm_id:alarm_hour:alarm_minute:alarm_state"

# 读模块日志
su -c "cat /sdcard/Android/data/com.coloros.alarmclock/files/otac.log"
```

### 进程结构

- 主进程 `com.coloros.alarmclock` —— 响铃处理在这里
- 子进程 `:clockWidget` —— 小部件，也会被注入

两者 `packageName` 相同，`handleLoadPackage` 里 `TARGET_PKG.equals(lpparam.packageName)` 对子进程同样命中。

---

## 八、版本演进

| 版本 | 主要改动 | 结果 |
|---|---|---|
| v1 | Hook `w3.e` | 无效，语义理解错 |
| v2/v3 | Hook `AlarmStateManager.onReceive` / `q` | 无效，异步 + id 是 schedules 主键 |
| v4 | 新增 `ContentResolver` 探针 | 定位到真实写库序列 |
| v5 | 日志改写入文件 | 解决「logcat 被冲爆」误判 |
| v6 | 正式 Hook `ContentResolver.update` | 精确命中，但反射 Context 失败 |
| v7 | 去掉 Context 依赖，纯 Provider 判定 | **删除成功** |
| v8 | 补发 `notifyChange` | 列表即时刷新 |
