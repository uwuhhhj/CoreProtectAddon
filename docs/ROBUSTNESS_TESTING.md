# 1.6.2 健壮性与验证范围

本版按要求交付打包产物，不继续启动 Paper 实例。以下自动化检查不需要服务器；它们不代替真实服务器的物品还原、组件编码和重载验收。

## 线程边界

| 工作 | 执行位置与限制 |
| --- | --- |
| 数据库连接、校验、重载候选连接、旧连接关闭 | 单独的维护工作线程；主线程不等待 JDBC |
| 重载配置读取、白名单文件写入 | 维护工作线程；主线程发布配置；重载失败保留原服务 |
| 命令、面板查询、详情和 debug 文本生成 | 2 个工作线程，最多 8 个排队任务；超出立即提示繁忙 |
| SQL lookup API | 调用方工作线程；所有服务实例共享最多 2 个数据库查询名额；主线程误用返回 `ASYNC_REQUIRED` |
| 历史物品还原、组件 codec、游戏状态、消息发送 | 服务器主线程；物品工作共用每 tick 2 件、2ms 协作预算 |
| 独立组件快照的值比较与嵌套字符串遍历 | 查询工作线程；不访问实时物品或注册表 |

组件条件在一次查询中仅编译一次，固定本次使用的白名单。主线程提取所需组件为独立快照，再交给工作线程只读匹配。debug 仅编码当前页最多 10 个组件。配置首次加载和候选配置发布仍包含主线程上的配置处理，不应理解为所有任务都已异步化。

## 自动化检查

在仓库根目录使用既有 Maven/JDK 环境：

```shell
mvn -Dcoq.build.directory=target/verify-robustness -Dcoq.test.coreprotectJar=.asset/CoreProtect-24.0-patched.jar package
mvn -Dcoq.build.directory=target/verify-robustness -Dtest=ClickHouseTest -Dcoq.test.pluginJar=.asset/CoreProtectAddon-1.6.2.jar test
```

- `QueryTaskPoolTest`：有界队列、同一发送者排他、取消后的任务实际退出前保留名额、取消排队任务、关闭拒绝新任务、排队超时和中断状态清理。使用不立即响应中断的模拟工作，验证取消不会允许重复任务堆积。
- `ConfigurationReloadTest`：独立配置读取不修改当前配置、发布解析及失败回滚、消息列表读取。
- `DatabaseConfigurationTest`：MySQL 连接和 socket 超时默认值；保留用户显式参数。
- `DuckDBTest`：真实临时 DuckDB 数据库中的只读连接、查询和超时；单条超过 8 MiB 的元数据不会进入 BLOB 读取，结果页超过 16 MiB 时明确失败。
- `ClickHouseTest`：本地 HTTP 协议 fixture、元数据长度预检、传入最终 JAR 时检查打包依赖隔离。它不验证真正的 ClickHouse SQL 执行计划。
- 既有解析、分页、动作、会话与组件语法测试继续运行。MySQL 外部集成测试需要其专用环境，未配置时跳过；未指定 `coq.test.pluginJar` 时，打包依赖检查也会跳过。

本次打包执行 63 项测试，61 项通过、2 项跳过、无失败；随后对最终 1.6.2 JAR 单独运行依赖隔离检查并通过。合计 62 项通过，剩余 1 项外部 MySQL 集成测试未执行。JAR 的版本、生产入口类和新增工作池类已检查，未包含临时服务器探针。

测试报告位于 `target/verify-robustness/surefire-reports`，完整打包和产物检查日志分别为 `target/verify-robustness-package.log`、`target/verify-robustness-artifact.log`。Paper 实机验收尚未完成，因此不宣称本版已通过服务器压测或证明无主线程停顿。

## 保护的边界

1. 查询时限从任务提交开始计算，包含排队。取消采用中断和协作检查，主线程不调用 JDBC 的取消接口。驱动、连接获取或单件服务器 API 若暂不返回，无法靠 Java 超时强制安全终止它们；占用名额直到实际退出。
2. 每 tick 的 2ms 是每件工作之间检查的预算。正在进行的历史反序列化或组件编码不可抢占，复杂数据仍可能造成单次 tick 延长。
3. 元数据先查字节数，再获取允许范围的 BLOB；单条 8 MiB、每批及保留结果页各 16 MiB。它限制原始数据体积，不等于 JVM 总堆内存上限；解码后的对象、JDBC 缓冲和快照仍会占用内存。
4. 异步只读查询仍消耗数据库 CPU、I/O、连接与内存，共享资源可能影响 CoreProtect 写入。只读不等于对写入性能没有影响。
5. 组件查询为取得完整总数仍会扫描候选，翻页和打开面板会重新查询；候选数与超时上限继续生效，超限明确提示搜索未完成，不把未扫描部分当作不存在。
