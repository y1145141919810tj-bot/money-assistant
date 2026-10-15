# 零钱管家 · 家里的电脑

这台电脑是账本服务器：入库、去重合并、分类和月报都在这里算。手机只负责采集和查看。

零依赖，需要 Node.js 22.5 或更高（使用内置 `node:sqlite`）。

```powershell
cd server
node src/http/server.js
```

默认监听 `0.0.0.0:8787`。首次启动会在 `data/device.token` 写下设备令牌，并在终端打印一次。把令牌和电脑地址填进手机 App 的「连接电脑」。

| 变量 | 默认 | 含义 |
|---|---|---|
| `HOST` | `0.0.0.0` | 监听地址 |
| `PORT` | `8787` | 端口 |
| `DEVICE_TOKEN` | 自动生成 | 覆盖 `data/device.token` |
| `DEVICE_ID` | `phone` | 设备 id |

手机在家里时，地址填电脑的局域网 IP，例如 `http://192.168.1.20:8787`。出门后走自建 WireGuard，地址改成电脑在隧道里的 IP，例如 `http://10.0.0.1:8787`。WireGuard 本身不在这个仓库里配置；它只提供加密通路，账本协议不变。

接口（`Authorization: Bearer <令牌>`）：

- `POST /v1/events:batch` 上报通知/短信原文，服务端自己解析、幂等、合并
- `POST /v1/heartbeat` 权限与待上传条数
- `GET /v1/report/monthly?month=YYYY-MM` 月支出、收入、分类、按日趋势、最近流水

```powershell
node --test --disable-warning=ExperimentalWarning
```
