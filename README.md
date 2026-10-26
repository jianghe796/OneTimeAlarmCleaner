# OneTimeAlarmCleaner

> ColorOS 时钟的一次性闹钟，响完就该消失。

一个 Xposed / LSPosed 模块，用于修复 **ColorOS 时钟「一次性闹钟响铃结束后不自动删除」** 的问题。

系统原本的行为是：一次性闹钟响完，只把 `enabled` 置为 `0`（显示为关闭状态），记录仍留在数据库里，日积月累列表堆满一堆「已关闭」的废闹钟，需要手动一个个删。

本模块在闹钟响铃结束、系统即将停用它的瞬间，判定其为一次性闹钟并直接删除记录。**列表即时消失，无需退出重进。**

---

## 效果

| | 原系统 | 本模块 |
|---|---|---|
| 一次性闹钟响完 | 变灰「已关闭」，残留 | **直接消失** |
| 列表清洁度 | 越用越脏 | 始终干净 |
| 重复闹钟 | 正常保留 | 正常保留（不受影响） |

---

## 原理

### 关键洞察

逆向 ColorOS 时钟（`com.coloros.alarmclock`）后发现，响铃结束时的写库序列是：

```
AlarmManager 发 change_state 广播（Data URI 携带 schedules 表 _id，不是闹钟 _id）
  └─> AlarmStateManager.onReceive
        └─> CR.update  .../schedules/<sid>  { alarm_state = 0 }
        └─> CR.delete  .../schedules?alarm_id=<aid> AND _id!=<sid>
        └─> w3.g(ctx, sid)                  删单条 schedule
        └─> CR.update  .../alarm/<aid>  { closeOnceNextTime=0, enabled=0, closeOncePreviousTime=0 }   ← 关键点
        └─> w3.e(ctx, aid)                  清理孤儿调度
```

**`CR.update .../alarm/<alarmId> { enabled=0 }` 这一行，只在一次性闹钟响铃结束时出现。**

重复闹钟永远不写这一行——因为系统知道它明天还要响。

所以这个写入**本身就是「这是一次性闹钟」的最可靠信号**，比任何字段推断都强。

### 实现

```
Hook: ContentResolver.update
  ├─ URI 匹配 content://com.coloros.alarmclock.alarmclock/alarm/<id>
  ├─ ContentValues 含 enabled=0
  ├─ 用 param.thisObject（ContentResolver 实例本身）查询该闹钟
  ├─ 五条件判定（全部来自 Provider 字段，不反射任何 App 私有类）
  │    ① daysofweek == 0
  │    ② loopSwitch == 0
  │    ③ specialAlarmDays 去 # / 后为空
  │    ④ loopResetDays   去 # / 后为空
  │    ⑤ 任一不满足 → 保留并 return
  ├─ resolver.delete(alarm/<id>)
  └─ 补发 notifyChange（整表 URI + 单条 URI + schedules 表），驱动列表刷新
```

**全程不反射 App 内部类、不依赖 Context**，只使用公开的 `ContentResolver` API 与 Provider 字段，兼容性和稳定性都很好。

---

## 安装

### 前置条件

- Android 9+（minSdk 28）
- 已安装 **LSPosed**（或兼容的 Xposed 框架）
- ColorOS 时钟（`com.coloros.alarmclock`）

### 步骤

1. 下载 `OneTimeAlarmCleaner.apk` 并安装
2. 打开 **LSPosed 管理器** → 模块 → 启用「一次性闹钟清理」
3. 在**作用域**中勾选 **时钟**（`com.coloros.alarmclock`）
4. 强制停止时钟（或重启手机）
5. 完成

### 验证

打开时钟新建一个 1 分钟后的一次性闹钟，等它响完：

- ✅ 列表里该闹钟**直接消失**（无需退出重进）
- ✅ 重复闹钟（勾选了星期）**不受影响**

---

## 兼容性

| 项目 | 状态 |
|---|---|
| 测试设备 | OPPO PJA110 |
| 测试系统 | Android 16（ColorOS） |
| 测试应用 | ColorOS 时钟 `com.coloros.alarmclock` |
| 框架 | KernelSU + LSPosed |

> ⚠️ **仅测试于上述环境。** 不同 ColorOS 版本的表结构或字段名可能不同。
> 若你的版本字段有变，需同步调整 `MainHook.java` 中以下四个列名：
> `daysofweek`、`loopSwitch`、`specialAlarmDays`、`loopResetDays`。

### 数据表说明

| URI | 说明 |
|---|---|
| `content://com.coloros.alarmclock.alarmclock/alarm` | 闹钟定义表（实际表名 `alarms`） |
| `content://com.coloros.alarmclock.alarmclock/schedules` | 调度实例表（含 `alarm_id` 列） |

注意：`change_state` 广播中的 id 是 **`schedules` 表主键**，不是闹钟 `_id`。这是很多人排查此处问题时会踩的坑。

---

## 风险提示

**本模块会真实删除数据库记录。**

- 判定逻辑为「宁可保留，不可误删」：任何判定异常都会放行（不删）
- 只处理「正在进行 `enabled` 1→0 写入」的闹钟，**不会**扫描清理历史遗留记录
- 卸载模块即可完全恢复系统原始行为

如果担心，可以先在备份数据的设备上试用。

---

## 已知问题

- **卸载模块后，之前未删除的旧残留闹钟不会自动消失**（模块只处理实时写入，不做批量清理）

---

## 构建

### 使用 Android Studio

直接打开项目目录即可。

### 命令行

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release-unsigned.apk
```

### GitHub Actions

仓库已配置自动构建：

- Push 到 `main` / `master` → 自动构建并上传 artifact
- Push tag（如 `v8.0`）→ 自动构建并发布 GitHub Release

---

## 目录结构

```
.
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/xposed_init              # Xposed 入口声明
│       ├── java/.../MainHook.java          # 核心逻辑
│       └── res/values/strings.xml
├── docs/
│   └── REVERSE_ENGINEERING.md              # 完整逆向分析过程
├── .github/workflows/build.yml             # CI 自动构建
├── build.gradle
└── settings.gradle
```

---

## 逆向分析文档

完整的逆向取证过程、调用链追踪、踩坑记录见 → [docs/REVERSE_ENGINEERING.md](docs/REVERSE_ENGINEERING.md)

如果你也想改 ColorOS 时钟的其他行为，这份文档可以省掉你大量时间。

---

## License

MIT
