# 架构说明

## 指令流

```text
浏览器操作
  -> STOMP /app/rooms/{roomId}/action
  -> GameCoordinator
  -> HoldemTable（同步串行校验与状态变更）
  -> 每位房间成员的 /user/queue/rooms/{roomId}
  -> 浏览器用服务端快照替换本地状态
```

客户端指令包含 `actionId`、动作、目标下注额和期望状态版本。当前聚合使用版本号拒绝过期操作；每张牌桌的变更方法均为同步方法，确保同一时刻只接受一个动作。

## 边界

- `poker-domain`：牌、牌型、底池、玩家状态和桌面状态机。没有 Spring 依赖，可以独立测试。
- `poker-server`：用户认证、私人房间、WebSocket、超时任务、牌局记录和数据库迁移。
- `poker-web`：只显示服务端快照并提交操作，不参与规则判定。

## 隐藏信息

`HoldemTable.snapshotFor(viewerId)` 为每个接收者生成不同视图。进行中的底牌只向本人返回；摊牌结束后，只公开没有弃牌且参与摊牌者的底牌。房间状态不通过公共 topic 广播。

## 扩展到多实例

当前部署是一张桌固定在一个 JVM 内。需要横向扩展时：

1. 给房间增加所属节点和租约。
2. WebSocket 网关按房间路由到所属节点。
3. 把接受的牌桌事件先持久化到 PostgreSQL 或 Redis Streams。
4. 使用 Redis Pub/Sub 只做在线广播，不将它作为牌局事实来源。
5. 增加快照及事件回放，处理节点失效后的房间迁移。

