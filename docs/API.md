# API 摘要

所有修改型 HTTP 请求都需要由 `/api/auth/csrf` 获取的 CSRF 请求头。登录后的认证信息保存在安全会话 Cookie 中。

## HTTP

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/auth/csrf` | 获取 CSRF Token |
| POST | `/api/auth/register` | 注册并登录 |
| POST | `/api/auth/login` | 登录 |
| GET | `/api/auth/me` | 当前用户 |
| POST | `/api/auth/logout` | 退出 |
| GET | `/api/rooms` | 房间列表 |
| POST | `/api/rooms` | 创建房间 |
| POST | `/api/rooms/join` | 使用邀请码加入 |
| GET | `/api/rooms/{id}` | 获取自己的房间视图 |
| POST | `/api/rooms/{id}/seats/{seat}` | 入座 |
| DELETE | `/api/rooms/{id}/seat` | 离座 |
| POST | `/api/rooms/{id}/start` | 房主开始下一手 |

## WebSocket / STOMP

连接地址：`/ws`

客户端发送：

- `/app/rooms/{roomId}/action`
- `/app/rooms/{roomId}/chat`

客户端订阅：

- `/user/queue/rooms/{roomId}`：针对当前玩家过滤后的牌桌快照
- `/user/queue/rooms/{roomId}/chat`：房间聊天
- `/user/queue/errors`：规则错误或输入错误

动作消息：

```json
{
  "actionId": "客户端生成的UUID",
  "action": "RAISE",
  "amount": 120,
  "version": 18
}
```
