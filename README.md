# 河牌会（Friend Poker）

面向熟人圈、自行部署的聚会游戏 Web 应用，包含飞行棋与无限注德州扑克。筹码仅用于娱乐，不支持充值、提现、抽水或交易。

## 现在具备的能力

- 管理员创建、启用、禁用和重置玩家账号；没有公开注册。
- 玩家修改昵称和密码，登录按 IP 做单机固定窗口限流。
- 私人房间、六位邀请码和可选密码；大厅不会公开其他房间的邀请码。
- 大厅可选飞行棋或德州扑克：2～4 人推荐飞行棋，5 人以上推荐德州。
- 飞行棋无操作时限，5 或 6 起飞，连续掷出 6 可一直行动，支持撞机、同色跳跃、快捷航线和终点反弹。
- 飞行棋首轮不刷新道具；从第 2 轮开始每 2 轮刷新 2 个神秘道具，保留 3 轮且同时最多 4 个。当前拾取后仅消失并提示，效果待后续开放。
- 完整现金桌规则：2～9 人、Heads-up、All-in、任意层边池、平分和奇数筹码。
- 服务端权威状态、版本校验、`actionId` 幂等、行动/超时竞争串行化。
- 洗牌承诺先持久化和广播，再发底牌；牌局结束公开种子。
- 两种游戏的活跃房间和进度都写入数据库；重启后，德州恢复牌堆、下注与计时器，飞行棋恢复棋子、轮次、骰子和道具。
- 参与者可分页查看自己的牌局历史；聊天只实时传输，不落库。
- Vue 3 响应式界面、URL 刷新恢复、断线遮罩、移动下注快捷操作。
- Docker Compose + PostgreSQL + Caddy HTTPS，带健康检查、日志轮转和安全响应头。

## 结构

```text
poker-domain/   无框架的德州/飞行棋规则与可恢复状态机
poker-server/   Spring Boot REST/STOMP、安全、持久化与迁移
poker-web/      Vue 3 + TypeScript 单页前端
deploy/         Caddy 配置
scripts/        验证、启动、备份和迁移检查脚本
docs/           架构、API 与上线手册
```

单服务器最多约 20 人、同时 1～3 桌是当前设计边界。项目刻意不使用 Redis、消息队列、微服务、前端路由库或状态管理库。

## 本地运行

要求：JDK 21+、Maven 3.9+、Node.js 22+、pnpm。

Windows 可使用后台启动脚本，它会构建后端、检查服务可用性，并把日志保存在 `data/local-logs/`：

```powershell
./scripts/run-local.ps1
```

已有最新 JAR 时可加 `-SkipBuild`。重复运行会复用正在运行的服务。网页入口为 `http://localhost:5173`，`8080` 是后端接口端口，可通过 `http://localhost:8080/actuator/health` 检查状态。后台服务退出后，重新运行脚本即可恢复。

也可以分别在终端中启动：

```powershell
mvn -pl poker-server -am spring-boot:run
```

另开终端：

```powershell
cd poker-web
pnpm install
pnpm dev
```

浏览器访问 `http://localhost:5173`。本地数据在 `data/` 的 H2 文件中；正式环境使用 PostgreSQL。

项目不创建默认账号。首次使用前按[上线手册](docs/上线部署清单与操作手册.md)生成 BCrypt 哈希并手工插入首个管理员。

## 验证

Windows：

```powershell
./scripts/verify.ps1
```

Linux/macOS：

```bash
sh scripts/verify.sh
```

脚本依次运行后端测试、前端依赖锁校验、TypeScript 检查和生产构建。安装 Docker 后还可运行：

```bash
sh scripts/verify-postgres.sh
```

该脚本会使用临时容器分别验证 PostgreSQL 全新库和 V1 升级到 V4，并实际读写飞行棋房间、成员和历史授权表，完成后自动清理。

## 上线

完整步骤见[上线部署清单与操作手册](docs/上线部署清单与操作手册.md)。API 契约见 [API.md](docs/API.md)，恢复和并发设计见 [ARCHITECTURE.md](docs/ARCHITECTURE.md)。
