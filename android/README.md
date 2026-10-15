# 零钱管家 · Android 采集端（P0）

后台自动捕获微信、支付宝、银行卡的收支。本地先落一份，再补传到家里的电脑；月报以电脑合并后的账本为准。

架构与后续分期见 `../DESIGN.md`。

---

## P0 交付范围

| 能力 | 状态 |
|---|---|
| 通知监听捕获（微信 / 支付宝 / 云闪付 / 银行 App 推送） | ✅ |
| 银行短信解析（含余额，是唯一可自校验的来源） | ✅ |
| 本地模板库（JSON，可被云端下发覆盖，无需发版） | ✅ |
| 本地 SQLite 账本，`source_key` 幂等 | ✅ |
| 实时流水界面 + 来源健康度面板 | ✅ |
| 未识别条目落库（补齐模板库的唯一依据） | ✅ |
| 隐私闸门（验证码整条丢弃、白名单外不看内容） | ✅ |
| 上报到家里的电脑，并查看月支出 / 收入 / 分类 / 趋势 | ✅ 需在 App 里填写电脑地址和令牌 |
| 对账、播报、预算 | ❌ 尚未做 |

采集仍然先落在手机本地。填了「连接电脑」之后，新捕获的流水会补传到那台电脑；月报以电脑上合并后的账本为准。

---

## 构建

前置：JDK 17+（本机用 Android Studio 自带 JBR）、Android SDK platform 34、build-tools。

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd <本目录>
.\gradlew.bat testDebugUnitTest assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

单测只覆盖纯逻辑（金额解析、归一化、模板匹配、幂等键、来源识别），不打桩 Android API。模板库测试直接读 `app/src/main/assets/templates.json`，保证被测的就是发版时打进 APK 的那一份。

## 安装与授权

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

首次打开后必须完成两件事，否则后台抓取不生效：

1. 点「授权通知使用权」，在系统设置里勾选 **零钱管家 · 收支捕获**。
2. 点「授权短信权限」，允许读取短信。

然后点「回补最近 7 天短信」，这一步会立刻用真实历史流水检验模板库命中率。

### 连接家里的电脑

先在电脑上按 `../server/README.md` 把账本服务跑起来。再在 App 里点「连接电脑」：

- 地址：家里局域网用 `http://<电脑局域网 IP>:8787`；出门后把地址改成电脑在 WireGuard 里的地址，例如 `http://10.0.0.1:8787`。
- 令牌：电脑 `server/data/device.token` 里的那一行。

点「本月账单」会先把未上传的流水送上去，再显示这个月的支出、收入、分类和按日趋势。同一条流水重复上传不会记成两笔。

### 国产 ROM 必做的保活设置

`NotificationListenerService` 由系统绑定，比前台服务抗杀，但仍需逐项确认：

- 允许自启动
- 允许后台运行
- 忽略电池优化
- 锁屏后不清理后台

另外：**通知使用权可能在系统更新、清理缓存、换机后丢失**，而丢失后采集会静默停止。所以每次打开 App 都会自检权限状态。

---

## P0 验收标准

1. **实时捕获**：用支付宝或微信支付一笔小额（建议 0.01 元），App 列表应在数秒内出现该笔记录，金额与方向正确。
2. **短信解析**：银行扣款短信应在 1 分钟内出现，且带上银行名与余额。
3. **不误收**：微信里正常聊天不应产生任何记录。
4. **幂等**：同一笔通知重复投递、或重复点「回补」，都不会产生第二条账目。
5. **不采集凭据**：含验证码的短信不得出现在任何列表里。

---

## 模板库：抓取率的唯一瓶颈

模板库在 `app/src/main/assets/templates.json`，由三部分组成：

- `templates`：每条模板 = 适用来源（短信发件号码 / 通知包名）+ 正则 + 捕获组下标 + 置信度。
- `directionMap`：收支动词到 `expense|income|refund|transfer` 的映射。**动词没登记就解析不出方向**，新增模板时必须同步登记。
- `senderChannels` / `packageChannels`：来源到渠道名的映射，让账目能落到「工商银行」「支付宝」而不是「未知」。

设计取舍：模板里日期段用惰性通配（` .{0,20}?`）而不是固定格式。银行的日期写法（`12:30`、`12时30分`、`08月05日`）改一次就会让固定格式的模板失配，这是实测最容易踩的坑。

### 云端热更新

`CapturePipeline` 优先读取 `filesDir/templates.json`，其次才是 `assets/templates.json`。P1 阶段云端下发新模板写进前者即可生效，**银行改文案不需要重新发版**。若下发文件损坏，会自动回退到内置模板并在 logcat 打 `template set invalid`。

### 补模板的标准流程

1. App 里点「未识别」，看到像流水但没记上的条目——这就是缺口。
2. 复制原文，在本机复现：往 `TemplateEngineTest` 里加一条用例。
3. 按原文写正则，登记 `directionMap` 里缺的动词。
4. 跑 `.\gradlew.bat testDebugUnitTest`，绿了再发版或下发。

不要凭想象写正则。真实文案与直觉差距很大，未识别列表才是唯一可信的输入。

---

## 隐私设计

- **不进白名单不看内容**：包名不在支付/银行白名单、且文本不含 `尾号xxxx|信用卡|储蓄卡|余额` 等结构化特征的通知，直接丢弃，不解析、不落库。
- **凭据整条丢弃**：命中 `验证码|校验码|动态码|短信密码|登录码|口令` 的条目在解析前就被拒，不落库、不上报。
- **不入库就不上报**：上报的是已经过上述两道闸门的 `raw_event` 文本，不是整箱短信或全部通知。
- **非流水不留痕**：判定为非流水的通知不写入 `raw_event`，数据库里查不到。
- 短信回补默认只处理最近 7 天，首次启动只建立基线不回补历史，避免一次性灌入多年短信。

---

## 已知限制

- **小额免密交易可能没有短信**：多数银行对 300 / 1000 元以下免密交易不发提醒，需在银行 App 或云闪付里把提醒金额设为任意金额（部分银行收费）。这是最大的系统性缺口，靠 CSV 账单对账兜底（P2）。
- **微信支付通知依赖用户设置**：如果「微信支付」服务号会话被设为免打扰，系统通知不会产生。健康度面板会暴露这种静默失效。
- **银行 App 推送暂未覆盖**：银行 App 的通知文案与短信不同，P0 会落进「未识别」桶，等模板补齐。
- **无去重合并**：本机只按 `source_key` 做幂等。同一笔交易的「支付宝通知 + 银行短信」跨源合并在 P1 的云端做，本地已按未来合并所需的字段（账户尾号、渠道、金额、方向、时间）落库。
- **本机列表不是月报**：手机上的「最近流水」按单条捕获展示，同一笔的支付宝通知和银行短信在这里仍是两条。合并、内部转账剔除和分类发生在电脑上，看月度支出请用「本月账单」。

---

## 代码结构

```
app/src/main/java/com/pocketledger/
  core/Models.kt                     Draft / 枚举，零 Android 依赖
  parse/TextNormalizer.kt            全角转半角、去千分位、空白折叠
  parse/AmountParser.kt              BigDecimal 转「分」，拒绝 0 与荒谬值
  parse/PiiFilter.kt                 凭据闸门
  parse/Prefilter.kt                 「像不像流水」的廉价预筛
  parse/OriginRegistry.kt            包名/号码白名单与结构化特征
  parse/SourceKey.kt                 幂等键
  parse/TemplateEngine.kt            模板库加载与匹配（唯一解析入口）
  data/LedgerDb.kt                   schema v1
  data/LedgerRepository.kt           唯一写入入口，幂等 + 健康度累加
  capture/CapturePipeline.kt         通知/短信共用入口，单线程写盘
  capture/LedgerNotificationListener.kt
  capture/SmsInboxReader.kt          ContentObserver + 回补
  ui/MainActivity.kt                 状态面板 + 流水/未识别列表
  ui/LedgerAdapter.kt
app/src/main/assets/templates.json   模板库（可热更新）
```

`parse/` 与 `core/` 是纯 Kotlin，可在 JVM 上直接测试；Android 依赖集中在 `data/`、`capture/`、`ui/`。

### 数据库

`ledger.db`，schema v1，三张表：

- `raw_event`：原始证据，只追加。`source_key` 唯一约束。
- `txn`：账目。金额列 `amount_cents` 是以「分」为单位的整数，禁止浮点。
- `source_health`：各来源命中/未识别计数与最近时间，用于发现静默失效。

`LedgerDb.onUpgrade` 目前为空。**后续版本必须写显式迁移语句**，直接重建表会丢掉全部历史账目。

健康度累加刻意避开 `ON CONFLICT DO UPDATE`：Android 24 自带 SQLite 3.9 不支持 upsert 语法，改用「先 `INSERT OR IGNORE` 再 `UPDATE`」，在所有目标版本上都安全。
