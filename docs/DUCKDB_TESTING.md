# CoreProtectAddon 1.3.0 DuckDB 安装与测试

## 安装

1. 停止测试服务器，保存原 Addon JAR 和 `plugins/CoreProtectAddon/config.yml`。
2. 将 `CoreProtectAddon-1.3.0.jar` 放入 `plugins`，替换旧 Addon JAR，目录中只留一个 Addon 版本。继续使用你提供的 `CoreProtect-24.0-patched.jar`，无需替换 CoreProtect。
3. 修改 **Addon** 配置顶层的这一项：

   ```yaml
   database-type: duckdb
   ```

   原来的 `database:`、`clickhouse-*` 和 `table-prefix` 可以保留，DuckDB 模式不使用这些连接/表名配置。不需要输入路径、用户名或密码，也不要复制或移动 CoreProtect 的数据库。

4. 完整启动服务器，让 CoreProtect 先完成数据库初始化。本次请停服替换并重启，避免通过 PlugMan 热卸载持有原生数据库连接的插件。
5. 检查 Addon 启动日志：应显示 `尝试连接到数据库：DUCKDB`、`DuckDB 只读查询目标：...`、实际表前缀和 `READ ONLY` 事务说明。默认文件为服务器的 `plugins/CoreProtect/database.duckdb`，应与你的 CoreProtect 数据目录一致。随后应正常注册查询命令。

## 只读与路径保证

Addon 从正在运行的 CoreProtect 读取 `ConfigHandler.path`、`duckdb`、`prefix`，并核对实际连接报告的文件路径。文件须已存在，解析符号链接后的真实路径须在 CoreProtect 的数据目录内；任一检查失败就停止启用，不回退到其他数据库、不创建文件。

Addon 复用 CoreProtect 已加载的 DuckDB 驱动和数据库实例，取得独立的连接。每次查询先执行 `BEGIN TRANSACTION READ ONLY`，查询结束关闭该连接，数据库引擎拒绝事务内写入。连接包装还禁止提交/结束该只读事务、修改事务模式、批处理和非 SELECT SQL，不暴露原生连接。Addon 不调用 CoreProtect 可能打开/创建文件的 `getConnection()` 方法，不关闭 CoreProtect 的根连接，不调整其锁或数据库设置。

这不是给写入中的数据库文件另开一个只读实例。DuckDB Java 文档说明同一数据库不支持混用文件级读写/只读连接，因此使用同一实例的独立只读事务：[官方 Java 文档](https://duckdb.org/docs/lts/clients/java)、[1.4.5 只读事务测试](https://github.com/duckdb/duckdb/blob/v1.4.5/test/sql/transactions/test_read_only_transactions.test)。

兼容范围为本次提供的定制 CoreProtect 24.0 构建；桥接需要它的运行时字段。普通 CoreProtect 版本号为 24.0 并不保证存在这些接口。若未来换用其他定制构建导致接口变化，Addon 会拒绝连接，需要重新适配。

## 游戏内验收

使用测试玩家登录，发一条容易识别的聊天（例如 `COQ_DUCKDB_TEST`），执行 `/list`，丢出并捡回铁锭，再向普通箱子放入/取出铁锭。等待 CoreProtect 将记录写入数据库，再以 OP 执行：

```text
/coq l a:chat u:你的玩家名 t:10m content:COQ_DUCKDB_TEST
/coq l a:command u:你的玩家名 t:10m content:^/list
/coq l a:item u:你的玩家名 i:iron_ingot t:10m
/coq l a:+item u:你的玩家名 t:10m
/coq l a:-item u:你的玩家名 t:10m
/coq l a:container u:你的玩家名 t:10m
/coq l a:container u:你的玩家名 t:10m rows:1
/coq l 2
```

把 `你的玩家名` 替换为实际名字。至少两条匹配记录才有第 2 页。核对名字、数量、动作、时间、悬停位置；可与 `/co l` 相同条件的已落库记录对照。再产生一条新聊天并重新查询，确认 CoreProtect 继续写入。Addon 不读取 CoreProtect 内存中尚未落库的记录。

DuckDB 的 `content:` 使用参数化 `regexp_matches(message, pattern)`，默认区分大小写，可加 `(?i)`；遵循 RE2，不支持回溯引用和前后查找。原始聊天中的引号、反引号和分号都作为参数数据处理。若使用 AstrBot，再验证原有聊天/命令查询；旧 Java API 签名保留。

## 切换回其他数据库

- MySQL/MariaDB：设 `database-type: mysql`，使用原有 `database:` 连接设置；如需恢复 `database.tables` 各自表名，设 `table-prefix: ''`。
- ClickHouse：设 `database-type: clickhouse`，核对顶层 `clickhouse-*` 和前缀，参照 [ClickHouse 测试说明](CLICKHOUSE_TESTING.md)。

修改后完整重启服务器。切换仅改变 Addon 查询目标，不迁移历史数据，也不改变 CoreProtect 的写入后端。DuckDB 只能读取当前同一服务器进程内、由 CoreProtect 打开的数据库。

## 已做的本地验证及反馈

使用与特殊 CoreProtect 相同的 DuckDB JDBC 1.4.5.0，在临时目录实际创建数据库并执行查询，覆盖只读拒绝、路径不符/文件缺失、并行写入可见性、连接关闭隔离、七张表、物品与容器动作、玩家/物品过滤、正则、64 位 ID、空坐标、分页及实际超时中断。另直接加载你提供的 CoreProtect JAR 创建测试表，验证运行时桥接及真实表结构。测试不接触服务器数据，DuckDB 驱动不打包进 Addon。

仍需在测试服务器确认 Bukkit 插件加载、实际记录、游戏界面和 AstrBot 交互。若有异常，请提供 Addon 启动时的目标路径/前缀日志、完整错误堆栈、执行的 `/coq` 命令，以及 `/co l` 是否可查到相同记录；不需要发送数据库文件或密码。
