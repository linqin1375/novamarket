# NovaMarket 部署

## 环境

JDK 17；Maven Wrapper 随源码提供。首次构建需要下载依赖。默认 H2 + 内存库存不需要额外服务。

## 构建与启动

```powershell
.\mvnw.cmd -B -ntp verify
java -jar target/novamarket-1.0.0.jar --spring.profiles.active=local --novamarket.stock=memory
```

访问 http://127.0.0.1:8082/ 。Linux/macOS 使用 `bash ./mvnw`。创建库存 100、每人限领 2 的活动，领取后重试同一请求，查看领取单、发奖状态与库存对账。页面上的演示用户不是经过认证的真实账号。

## Redis 模式

```sh
docker compose up -d redis
java -jar target/novamarket-1.0.0.jar --spring.profiles.active=local --novamarket.stock=redis --spring.data.redis.port=6379
```

应用的库存实现应显示 `redis-lua`。强制 Redis 模式下连接失败会报错；不要以自动回退的内存结果冒充 Redis 测试。

Windows `scripts/start.ps1 -Build` 为可选快捷启动；`-Redis` 还需要自行准备脚本所用的 Windows Redis 便携程序。仓库不包含二进制运行时。

## MySQL 可选配置

复制 `.env.example` 为不提交的 `.env` 并填写自己的本地密码，再运行 `docker compose up -d mysql`。应用不会自动加载 Compose 的 `.env`；还需在应用进程设置 `NOVAMARKET_MYSQL_PASSWORD` 与 Compose 一致，再激活 `local,mysql` profiles。该数据库模式尚未作为现有报告的业务验收结论。

## 数据、重启与停止

- `data/novamarket.mv.db`：活动、库存、领取单、消息与发奖记录。
- 前台启动用 Ctrl+C 停止；备份数据库前先停止应用，再复制 data。
- 同一数据库不可由多个应用实例同时打开；不要提交 data、日志或 `.local` 进程记录。
- Windows 若使用后台启动脚本，使用对应 stop.ps1 停止；改代码重新打包前停止占用旧 jar 的进程。

历史 HTTP / 进程中断验收见 reports/local-deployment.json；这些结果属于报告标注的本机环境，不能推广为公网生产能力。
