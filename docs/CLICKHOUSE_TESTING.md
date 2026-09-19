# CoreProtectAddon 1.3.0 ClickHouse 服务器测试

## 安装与连接

1. 停止测试服务器，备份原有 CoreProtectAddon JAR 与 `plugins/CoreProtectAddon/config.yml`。
2. 用测试包中的 `CoreProtectAddon-1.3.0.jar` 替换旧 Addon JAR，插件目录中只留一个 Addon 版本。
3. 确认 CoreProtect 本身已经在目标 ClickHouse 数据库正常记录。Addon 的编译依赖为 CoreProtect 24.0；服务器需要具备 ClickHouse 功能的 CoreProtect 构建，并使用 ClickHouse 25.6+。本次交付只替换 Addon。
4. 将 [配置片段](../examples/clickhouse.yml) 合并到 **Addon** 的配置顶层，填写服务器实际使用的连接目标、账号与 `table-prefix`。原有 `database:` 段保留，便于切换。
5. 启动服务器，检查 Addon 日志出现“尝试连接到数据库：CLICKHOUSE”，且没有数据库加载失败。如果库或兼容视图不存在，先检查 CoreProtect 初始化情况与前缀。

参考配置及测试服务器的 CoreProtect 实际使用 `duckdb`。如需查询该服务器现有记录，请使用 [DuckDB 安装与测试](DUCKDB_TESTING.md)，将 Addon 设为 `database-type: duckdb`。本文只适用于实际写入 ClickHouse 的情况。CoreProtect 与 Addon 各自有配置文件，两者改动不会自动同步。

主机名 `clickhouse` 仅适用于能够解析该名称的网络；服务器不在同一 Docker 网络时填写可访问的地址。`clickhouse-tls: true` 使用 HTTPS，并需填写对应端口（常见 8443），证书须受 JVM 信任。账号应能 SELECT CoreProtect 的七个兼容视图及其依赖，并允许查询设置 `max_execution_time` 等。Addon 不进行写入，不需要为它修改 CoreProtect 的 `database-lock`。

## 生成记录并查询

使用测试玩家登录，发送一条聊天消息、执行一次 `/list`，丢出并捡回铁锭，再向普通箱子放入/取出物品。确认 CoreProtect 已启用对应记录项，等待写入完成后，以 OP 身份查询；将 `你的玩家名` 替换为实际名字：

```text
/coq l a:chat u:你的玩家名 t:10m
/coq l a:command u:你的玩家名 t:10m content:^/list
/coq l a:item u:你的玩家名 i:iron_ingot t:10m
/coq l a:+item u:你的玩家名 t:10m
/coq l a:-item u:你的玩家名 t:10m
/coq l a:container u:你的玩家名 t:10m
/coq l a:+container u:你的玩家名 t:10m
/coq l a:-container u:你的玩家名 t:10m
```

核对玩家名、物品数量、取放方向、时间与悬停位置。可用 `/co l` 的相同条件对照已落库的近期记录。Addon 只查询数据库，不包含 CoreProtect 尚未写入数据库的记录。

再验证分页和空结果：

```text
/coq l a:item u:你的玩家名 t:10m rows:1
/coq l 2
/coq l a:chat t:10m content:^COQ_NO_MATCH_20260919$
```

只有至少两条符合条件的记录时，第 2 页才应该有数据；无匹配聊天应显示无记录。`content:` 在 ClickHouse 上使用 RE2；`(?i)` 可忽略大小写，不支持回溯引用或前后查找。原 MySQL 正则并非全部能原样迁移。

如果同时使用 AstrBot，再各做一次原有聊天/命令查询，确认返回文本及分页符合预期；反射 API 签名保持不变。

## 切回 MySQL/MariaDB

停止测试服务器，在 **Addon** 配置中设置：

```yaml
database-type: mysql
table-prefix: ''  # 恢复使用原有 database.tables 完整表名
```

确认原 `database:` 连接与表名正确后重启，再查询旧库中已知存在的时间段。ClickHouse 配置项可以留在文件中，不会被 MySQL 模式使用。若旧库使用统一前缀，也可填写对应 `table-prefix`。

这里只切换 Addon 的查询目标，不会切换 CoreProtect 的日志写入目标或迁移历史数据。要让 CoreProtect 改写入另一种数据库，需要另行配置 CoreProtect；不要把“切换后查不到另一边的记录”当成自动迁移失败。

## 反馈结果

如果出现 `516 / AUTHENTICATION_FAILED`，表示目标 ClickHouse 实例拒绝认证。核对 Addon 启动日志中的“ClickHouse 查询目标”、用户名与“密码状态”，以及配置顶层的 `clickhouse-*`；不能只核对旧的 `database.username/password`。配置不会自动从 CoreProtect 复制。示例中的 `test` 账号必须替换成实际可用账号。

若与 CoreProtect 完全一致，1.2.1 会在连接失败后读取已启用 CoreProtect 的运行时配置，输出其实际数据库类型，并逐项比较六个 `clickhouse-*` 连接设置。只输出“相同/不同”，不输出密码内容或指纹。请提供这些“运行时配置对照”日志；若当前 CoreProtect 构建不提供这些内部字段，则会提示无法对照，此时再提供 CoreProtect 版本及实际数据库初始化日志。

已分析测试服务器提供的 `CoreProtect-24.0-patched.jar`：该构建使用 JDBC 0.9.8，与 Addon 的 JDBC 0.10.0 以相同测试账号密码完成了本地 HTTP 认证对照。它使用自定义配置解析器，并非标准 YAML；例如未加引号的 `001234` 会保持原文，而 Addon 的 YAML 解析会将其当作数值。建议在 Addon 中将密码写为单引号字符串，密码中单引号写成两个连续单引号，并将说明注释放到独立行。不要发送实际密码。

1.2.1 修复数据库加载失败后仍启用的问题；上述本地验证不代表特定服务器上的认证故障已经解决。切换此修复版时请停服替换并重启，让依赖插件先完成初始化。

如遇问题，请提供 CoreProtect/Addon 版本、ClickHouse 版本、执行的完整 `/coq` 命令、是否能在 `/co l` 中查到，以及启动或查询时的相关错误日志。配置片段只需类型、主机/端口与前缀，密码请遮盖。

本地验证覆盖编译、原查询测试、配置切换和真实 JDBC 对本地 HTTP fixture 的协议行为；真实 ClickHouse SQL 执行、实际 CoreProtect 视图及游戏客户端交互由这次服务器测试确认。
