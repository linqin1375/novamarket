# NovaMarket 测试与指标口径

## 自动化测试

源码测试不使用部署中的 H2 文件库，默认用独立的内存库。

PowerShell 示例：

```powershell
Set-Location -LiteralPath '<project-root>'
$env:JAVA_HOME='<your-jdk-17>'
.\mvnw.cmd test
```

默认 25 个测试通过；Redis 专项默认跳过。需要跑真实 Redis 测试：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\start-redis.ps1 -Port 6382
$env:NOVAMARKET_REDIS_TEST_PORT='6382'
.\mvnw.cmd test
```

额外 5 个 Redis 测试使用随机活动和请求键，仅清理自身键，不执行 FLUSHDB。此次改造已跑过真实 Redis 测试。

原有的 1000 请求测试使用 64 个线程、200 个任务，每个任务 5 次请求，抢 100 件库存；不是 200 个实际线程。另有直接内存适配器的 32 线程 / 400 次同键扣减测试，以及 Redis 的 16 线程 / 400 次同键扣减与重复回滚测试。

## 固定请求数实机测试

需 Node.js 18 或更新版本，先启动页面。

```powershell
node tools\loadtest.mjs --url http://127.0.0.1:8082 --requests 1000 --concurrency 32 --stock 100 --out reports\local-redis-benchmark.json
```

每次自动创建独立活动，不重用旧库存。默认最多 32 个在途请求，每个新请求用不同用户和不同幂等键。

- requestRps = 完整请求数 / 从第一批开始到最后一条结束的完整时段。
- acceptedRps = HTTP 2xx 且 SUCCESS 或 PROCESSING 的受理数量 / 同一完整时段。
- accepted 不等于 granted；完成后轮询已发奖数，核对缓存库存、数据库库存和成功领取数。
- P50 / P95 / P99 使用全部请求端到端耗时，nearest-rank 计算。
- SOLD_OUT、LIMIT_EXCEEDED、RATE_LIMITED 分开统计；HTTP 5xx、超时和网络错误记为系统错误。
- 没有爬坡阶段，不从分母额外扣时间。
- 只用于这台机器的回归对比；客户端、Redis、数据库、应用争用同机资源，且售罄和限流请求占比会影响结果。

当前本机限流为每活动 500 请求/秒、初始桶容量 500；1000 请求快速发出可能有大量 RATE_LIMITED。报告会保留数量，不能隐去限流响应后仍宣称完整业务吞吐。

页面并发演示为 16 个在途请求、200 次请求、100 件库存，适合直接演示正确性。

## 真实进程重启验收

```powershell
python scripts\verify-local.py
```

脚本只操作本工程登记的进程，创建新演示活动，注入消息发送失败，停止并重启 Java 与 Redis，然后切换一次内存库存验证冷恢复，最后恢复 Redis 部署。会暂时中断页面，运行前不要同时操作演示按钮。结果保存到 `reports/local-deployment.json`。

## 历史报告

`reports/baseline-memory-200c.json` 是改造前记录。其分子包含爬坡流量而分母扣掉爬坡时间，原吞吐值不适合作为简历或新版本结论。文件保留用于追溯，正文不再引用它。
