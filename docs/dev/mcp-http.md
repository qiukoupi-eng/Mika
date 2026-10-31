# MCP HTTP 传输

把 `McpServer` 的整任务入口挂到本机 HTTP JSON-RPC,供外部客户端 `POST` 任务;不改 `LocalChannel`、虚拟屏与无障碍。

## 监听

- 默认主机:`127.0.0.1`(其它设备连不进来)
- 默认端口:`18765`(prefs `mcp_http_port`,`0` 表示系统分配)
- 路径:`POST /mcp`
- 健康检查:`GET /health`(无需令牌)

开关与配置在 `FeatureFlags`(SharedPreferences `feature_flags`):

| 键 | 含义 | 默认 |
| --- | --- | --- |
| `mcp_http_enabled` | 是否监听 | `true` |
| `mcp_http_host` | 绑定地址 | `127.0.0.1` |
| `mcp_http_port` | 端口 | `18765` |
| `mcp_http_token` | Bearer 共享令牌 | 首次启动自动生成 |

非回环绑定前务必设令牌。App 启动时 `MikaApp` 调 `McpHttpRuntime.apply()`;改 prefs 后需再调 `apply()`(重启 App 即可)。

端点说明(含令牌)写在 App 私有文件 `files/mcp-http.json`,可用:

```bash
adb shell run-as com.wangzan101.mika cat files/mcp-http.json
```

日志只打 URL,不打令牌。

## 鉴权

配置了令牌时,`POST /mcp` 需要其一:

- `Authorization: Bearer <token>`
- `X-Mika-Token: <token>`

## JSON-RPC 方法

请求体为 JSON-RPC 2.0 对象(也接受批量数组)。

| method | params | result |
| --- | --- | --- |
| `initialize` | — | `serverInfo` / `capabilities` |
| `ping` | — | `{ok:true}` |
| `tasks/submit` | `goal`,可选 `threadId`、`attendance` | `{id}` |
| `tasks/cancel` | `id` | `{ok}` |
| `tasks/events` | `id`,可选 `after` | `{events,ended}` |
| `session/open` | 可选 `threadId`、`attendance` | 设备上关闭(`enableToolSessions=false`) |
| `session/call` / `session/close` | — | 同上 |

`attendance`:`unattended`(默认) / `present` / `reachable`。

示例:

```bash
TOKEN=$(adb shell run-as com.wangzan101.mika cat files/mcp-http.json | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
curl -s http://127.0.0.1:18765/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tasks/submit","params":{"goal":"查天气","threadId":"ext-1"}}'
```

本机用 `adb reverse tcp:18765 tcp:18765` 把设备端口转到电脑后再 curl。

## 与 LocalChannel 的关系

对话界面与通知仍走 `LocalChannel`。HTTP 只多一条 MCP 整任务入口,经 `McpTaskHub` → `TaskService.startMcp`,手机虚拟屏 / 无障碍路径不变。

## 相关代码

- `HttpMcpConfig` / `HttpMcpTransport` — `core/mcp/`
- `McpHttpRuntime` — `app/task/McpHttpRuntime.kt`
- `McpTaskHub` / `TaskService` — 既有整任务生命周期
