# 河牌会（Friend Poker）

一个可以自行部署、与朋友通过浏览器游玩的无限注德州扑克项目。项目只提供虚拟筹码，不包含充值、提现、抽水或筹码交易。

## 已实现

- 用户注册、登录、退出和会话认证
- 创建私人房间、六位邀请码、可选房间密码
- 2～9 人自由选座，房主开始下一手牌
- 完整的翻牌前、翻牌、转牌、河牌和摊牌流程
- 过牌、跟注、下注、加注、弃牌和 All-in
- 主池、任意层边池、平分底池及奇数筹码
- Heads-up 特殊盲注顺序和短码 All-in 加注权处理
- 每桌串行处理指令、状态版本校验和重复/延迟操作防护
- WebSocket 实时状态、私人底牌消息、断线自动重连
- 30 秒默认行动倒计时；超时能过牌则过牌，否则弃牌
- 牌桌聊天、已完成牌局记录
- PostgreSQL 持久化账号和已完成牌局
- 手机及桌面自适应界面
- Docker Compose、自动 HTTPS 和备份脚本

## 项目结构

```text
texas-holdem/
├── poker-domain/   不依赖框架的牌型、边池和牌桌状态机
├── poker-server/   Spring Boot REST、WebSocket、安全和数据库
├── poker-web/      Vue 3 + TypeScript 网页端
├── deploy/         Caddy 配置
├── scripts/        本地启动和数据库备份辅助脚本
└── compose.yml     云服务器部署编排
```

后端采用模块化单体。每张牌桌是一个串行聚合，浏览器只能提交玩家意图，筹码、合法动作、发牌及胜负全部由服务端决定。任何公共消息都不包含其他玩家的底牌；每位玩家通过专属 WebSocket 队列收到自己的视图。

## 本地开发

需要：JDK 21 或更高版本、Maven 3.9、Node.js 22 或更高版本、pnpm。

启动后端：

```powershell
mvn -pl poker-server -am spring-boot:run
```

首次启动会在项目目录的 `data` 中创建 H2 开发数据库。后端地址为 `http://localhost:8080`。

另开一个终端启动前端：

```powershell
cd poker-web
pnpm install
pnpm dev
```

浏览器打开 `http://localhost:5173`。Vite 会把 API 和 WebSocket 转发到本地后端。

运行全部测试与构建：

```powershell
mvn test
cd poker-web
pnpm build
```

## 云服务器部署

准备条件：

1. 一台建议至少 2 核、2 GB 内存的 Linux 云服务器。
2. 已安装 Docker Engine 和 Docker Compose 插件。
3. 一个已解析到服务器公网 IP 的域名。
4. 安全组放行 TCP 80、TCP/UDP 443；不要把 PostgreSQL 端口暴露到公网。

部署步骤：

```bash
cp .env.example .env
```

编辑 `.env`，填写域名并换成长随机数据库密码，然后运行：

```bash
docker compose up -d --build
docker compose ps
```

Caddy 会自动申请并续期 HTTPS 证书。健康检查地址为：

```text
https://你的域名/actuator/health
```

查看日志：

```bash
docker compose logs -f app web
```

升级版本：

```bash
git pull
docker compose up -d --build
```

备份数据库：

```bash
sh scripts/backup-postgres.sh
```

建议通过服务器的定时任务每天执行一次备份脚本，并把备份文件同步到另一台机器或对象存储。

## 关键规则约定

- 所有筹码均为 `long` 整数，不使用浮点数。
- 加注参数表示“本轮累计下注到多少”，不是额外增加多少。
- 不足一个完整最小加注的 All-in 不会重新开放已经行动玩家的加注权。
- 玩家超时：可以过牌时自动过牌，否则自动弃牌。
- 平分底池出现余数时，从庄家左侧开始向获胜者依次分配奇数筹码。
- 玩家只能在两手牌之间入座或离座。
- 服务端用 `SecureRandom` 生成 256 位种子；发牌前公布 SHA-256 承诺，结束后公开种子供玩家核对。

## 当前边界

- 活跃房间和未完成牌局保存在单个应用实例内；服务器重启后需要重新开房，账号和已完成牌局不会丢失。
- 第一版面向一台服务器，不支持多实例共同承载同一张桌。
- 房间大厅目前会显示所有正在运行的房间，但有密码的房间仍需验证密码才能进入。
- 这是朋友娱乐项目。如果未来加入真钱、可交易筹码或抽水，应在开发前咨询部署地和参与者所在地的专业法律意见。

## 默认配置

- 小盲 / 大盲：10 / 20
- 初始筹码：2,000
- 最大人数：9
- 行动时间：30 秒
- 密码哈希：BCrypt，强度 12
- 生产数据库：PostgreSQL 17
- 生产运行时：Java 25
