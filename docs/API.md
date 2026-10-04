# API 摘要

会话使用安全 Cookie。除 GET/HEAD/OPTIONS 外，HTTP 请求必须携带 `/api/auth/csrf` 返回的 CSRF 请求头。错误格式为 `{ "message": "...", "timestamp": "..." }`。

## HTTP

| 方法 | 路径 | 权限 | 用途 |
|---|---|---|---|
| GET | `/api/auth/csrf` | 公开 | 获取 CSRF Token |
| POST | `/api/auth/login` | 公开 | 登录；失败统一提示，按 IP 限流 |
| GET | `/api/auth/me` | 登录 | 当前用户，含 `role`、`enabled` |
| POST | `/api/auth/logout` | 登录 | 退出 |
| PATCH | `/api/profile` | 登录 | 修改昵称 |
| POST | `/api/profile/password` | 登录 | 验证当前密码后修改密码 |
| POST | `/api/rooms` | 登录 | 创建德州扑克私人房间 |
| POST | `/api/rooms/flight` | 登录 | 创建飞行棋私人房间 |
| POST | `/api/rooms/join` | 登录 | 凭邀请码和可选密码加入 |
| GET | `/api/rooms/mine` | 登录 | 自己加入过的活跃房间 |
| GET | `/api/rooms/{id}` | 成员 | 获取自己的脱敏牌桌视图 |
| POST | `/api/rooms/{id}/seats/{seat}` | 成员 | 入座 |
| DELETE | `/api/rooms/{id}/seat` | 成员 | 离座 |
| POST | `/api/rooms/{id}/start` | 房主 | 准备并开始下一手 |
| GET | `/api/history?page=0&size=20` | 登录 | 自己参与的历史分页 |
| GET | `/api/history/{handId}` | 参与者 | 历史详情 |
| GET/POST | `/api/admin/users` | 管理员 | 列出/创建玩家账号 |
| PATCH | `/api/admin/users/{username}` | 管理员 | 启用或禁用玩家 |
| POST | `/api/admin/users/{username}/reset-password` | 管理员 | 重置密码 |
| GET | `/api/admin/rooms` | 管理员 | 活跃房间元数据 |
| POST | `/api/admin/rooms/{roomId}/close` | 管理员 | 强制关闭房间 |

公开注册接口不存在。禁用账号、重置密码、修改角色或玩家自行修改密码后，旧 HTTP 会话和 STOMP 连接都会失效；重新启用账号不会恢复旧会话。管理员房间接口不返回牌桌快照、底牌、牌堆或洗牌种子。

## STOMP

连接：`/ws`

发送：

- `/app/rooms/{roomId}/action`
- `/app/rooms/{roomId}/chat`

订阅：

- `/user/queue/rooms/{roomId}`：当前用户的牌桌视图
- `/user/queue/rooms/{roomId}/shuffle`：先于发牌发送的洗牌承诺
- `/user/queue/rooms/{roomId}/chat`：本进程内实时聊天
- `/user/queue/errors`：规则或输入错误

动作示例：

```json
{
  "actionId": "f016f9e6-cc44-4878-aa69-e2cff1f78086",
  "action": "RAISE",
  "amount": 120,
  "version": 18
}
```

`amount` 是本轮累计下注目标。重复 `actionId` 不再执行，即使携带旧版本也只返回最新状态。

飞行棋发送地址为 `/app/flight/rooms/{roomId}/action`，私有状态和聊天订阅分别为
`/user/queue/flight-rooms/{roomId}` 与 `/user/queue/flight-rooms/{roomId}/chat`。掷骰和移动消息示例：

```json
{ "actionId": "唯一 ID", "action": "ROLL", "pieceIndex": null, "version": 12 }
```

```json
{ "actionId": "唯一 ID", "action": "MOVE", "pieceIndex": 2, "version": 13 }
```

飞行棋没有行动截止时间。`version` 和 `actionId` 的并发、过期和重复消息语义与德州一致。
