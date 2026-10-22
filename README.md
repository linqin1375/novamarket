# NovaMarket 营销权益领取平台

[![Java CI](https://github.com/linqin1375/novamarket/actions/workflows/ci.yml/badge.svg)](https://github.com/linqin1375/novamarket/actions/workflows/ci.yml)

Java 17 · Spring Boot 3.5 · JDBC · Redis / Lua · Flyway · H2

个人学习与工程验证项目，围绕活动配置、库存预扣、用户限领、异步发奖和故障补偿，验证并发请求下的正确性。支持内存与真实 Redis 两种库存实现；权益为合成数据，发奖写入本地记录。

## 重点实现

- Redis Lua 原子处理库存预扣、用户限领和幂等身份；目标分片耗尽后继续探测其他分片。
- 数据库条件扣减与唯一约束提供最终校验；请求 ID 同时绑定活动与用户。
- 领取单与本地消息同事务提交，进程内异步消费；发奖记录、领取状态和消息确认同事务更新。
- 预扣日志结算、退避重投与库存对账；进程中断后依据已提交数据库事实恢复。

## 快速运行

安装 JDK 17，进入项目根目录。无需模型 API、MySQL 或 Redis 即可运行内存演示。

```powershell
.\mvnw.cmd -B -ntp verify
java -jar target/novamarket-1.0.0.jar --spring.profiles.active=local --novamarket.stock=memory
```

Linux / macOS 将 `mvnw.cmd` 改为 `bash ./mvnw`。浏览器打开 http://127.0.0.1:8082/ ，数据保存在本地 `data/`。只运行一个应用实例使用同一份数据库。

## 真实 Redis

```sh
docker compose up -d redis
java -jar target/novamarket-1.0.0.jar --spring.profiles.active=local --novamarket.stock=redis --spring.data.redis.port=6379
```

应用明确显示 `redis-lua` 才表示正在使用真实 Redis。Windows 便携 Redis 不随仓库分发；Windows 启动脚本的其他选项见 [部署说明](docs/DEPLOYMENT.md)。

## 测试与证据

- 默认 25 项测试，另有 5 项需指定 Redis 端口的集成测试；CI 启动 Redis 并运行完整测试。
- 本机历史验收：30 项测试及 13 项 HTTP / 进程重启检查通过，见 [验证汇总](reports/validation-summary.json) 和 [逐项重启记录](reports/local-deployment.json)。
- 本机固定请求数测试见 [Redis 测试结果](reports/local-redis-benchmark.json)；条件、口径与局限见 [LOADTEST.md](docs/LOADTEST.md)。
- `baseline-memory-200c.json` 是存在吞吐计算口径问题的旧报告，仅为历史记录，不能作为当前性能证据。
- 本次公开副本的重新构建结果见 `reports/publication-validation.json`；GitHub CI 状态以顶部徽章和 Actions 为准。

真实 Redis 集成测试：

```powershell
$env:NOVAMARKET_REDIS_TEST_PORT='6379'
.\mvnw.cmd -B -ntp verify
```

## 设计与范围

[请求链路与取舍](docs/DESIGN.md) · [部署与排错](docs/DEPLOYMENT.md) · [压测口径](docs/LOADTEST.md)

单进程、单实例；本地消息表配合进程内异步总线，没有真实 RocketMQ。尚未证明生产容量、高可用或 Redis Cluster 跨槽支持。MySQL 配置为可选能力，本仓库记录的业务验收主要使用 H2。默认没有真实用户账号认证，不应直接开放到公网。

代码使用 AI 辅助生成、阅读与改造；公开测试结果与业务边界可以核对。提交记录从本次公开快照开始，没有重建历史开发记录。
