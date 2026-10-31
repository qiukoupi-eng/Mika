# 用账号登录

「用 ChatGPT 登录」保持原样（`core/chatgpt/`，授权码 + PKCE，本机回调）。这里是另外几家账号登录，以及明确不做的原因。

登录、加密保存、刷新、调用各家自己的接口，四步分开。访问令牌、刷新令牌、设备码不进日志、不进诊断信息、不进界面上除用户码以外的文字。会话 JSON 和 API Key 放在同一处（`provider-keys.json`），用同一套加密。

## xAI / Grok（设备码）

- 读 `https://auth.x.ai/.well-known/openid-configuration`。签发方必须是 `https://auth.x.ai`，设备码和令牌地址必须是 https 且主机是 `auth.x.ai`。对不上就停止，请求不发到别的主机。
- 设备码（RFC 8628）的 scope 是 `openid profile email offline_access grok-cli:access api:access`。用户在浏览器里打开验证页、核对短码，App 轮询换令牌。验证页的主机不在允许名单（`auth.x.ai`、`accounts.x.ai`）里就改开固定的官方页。
- 客户端是 xAI 官方命令行工具的公开登录入口（公开 client_id `b1a00492-073a-47ea-816f-4c329264a828`）。这不是 xAI 给 Mika 注册的应用，xAI 可能限制这种用法，账号有被限制的风险。界面在登录前写明，不点「继续登录」就不发生。
- 调用走 xAI 自己的 Responses：`https://api.x.ai/v1/responses`（`ResponsesModel`），不转成别的协议。
- 访问令牌快过期时用刷新令牌换新的，同一时刻只有一个在换。刷新被拒且不能再用时清掉本机令牌，报「登录已失效」。
- 退出只清这台手机上的令牌。xAI 账号里的授权要到 xAI 的账号设置里自己断开（没有可用的公开注销）。

## 不做

### Claude 订阅

Anthropic 写明 OAuth 只给它自己的应用；第三方产品要用 Console 的 API Key 或云厂商凭证，不允许第三方 App 提供 Claude.ai 登录，也不允许把订阅额度接到自己的请求上。Mika 继续用 Anthropic 预设填 API Key。

### Google 账号（Gemini）

Google 不允许第三方软件借用它自家工具的 OAuth 客户端去访问后端。第三方要用 AI Studio 或 Vertex AI 的 API Key。Mika 继续用 Gemini 预设填 API Key。
