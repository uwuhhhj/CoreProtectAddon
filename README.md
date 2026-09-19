# CoreProtectAddon / COQ 1.1.0

COQ 是独立的 CoreProtect 数据库查询插件。本版使用 CoreProtect 23.2 风格的 lookup 参数与交互，实际接通聊天、命令、物品与容器记录，直接查询 MariaDB/MySQL；不调用 `/co lookup`，不共用 CoreProtect 查询缓存。

## 命令

需要 `coreprotectaddon.command.query` 权限，默认 OP 可用。

```text
/coq help
/coq l a:chat u:Steve t:1d
/coq lookup a:command u:Steve t:1d content:"^/op .*"
/coq l a:item i:iron_ingot t:1d
/coq l a:+item u:Steve t:1h
/coq l a:container i:diamond e:iron_ingot t:1d
/coq l a:item u:Steve,Alex i:iron_ingot,diamond e:diamond t:1d
/coq l 2
/coq l 2:30
/coq page 2
```

`/coq chat`、`/coq command` 已移除，没有 `/coq item`。分别改用 `/coq l a:chat`、`/coq l a:command`、`/coq l a:item`。

| 参数 | 别名 | 行为 |
| --- | --- | --- |
| `u:` | `user:`、`users:`、`p:` | 玩家名，支持逗号列表；多个玩家取并集 |
| `t:` | `time:` | 支持 `1h`、`1.5h`、`1d2h`、`1w`、`10d-12d`；单位为 `y/mo/w/d/h/m/s` |
| `a:` | `action:` | 选择记录类型与动作方向 |
| `i:` | `include:`、`item:`、`items:`、`b:`、`block:`、`blocks:` | 包含的 material；多个物品取并集 |
| `e:` | `exclude:` | 排除的 material；排除优先于包含，本版不处理玩家/实体排除 |
| `content:` | `c:`、`message:`、`m:`、`command:` | chat/command 的数据库 REGEXP 条件；含空格时使用引号 |
| `page:` | — | 新查询中的页码，如 `page:2`、`page:2:30`；单独 `/coq l page:2` 继续最近查询 |
| `rows:` | — | 新查询每页条数，也可以使用 `page:1:30` |

`p:` 按 CoreProtect 语义表示玩家，不表示页码。重复参数、拼错的键、无效列表及不适用于当前类型的条件会报错。普通物品支持 `iron_ingot` 和 `minecraft:iron_ingot`。

## 动作与数据来源

| action | 表 | 数据库 action |
| --- | --- | --- |
| `chat` | `co_chat` | 保留聊天原文与 REGEXP 查询 |
| `command` | `co_command` | 保留命令原文与 REGEXP 查询 |
| `item` | `co_item` | 排除 8、9、10、11、12，与 CoreProtect 23.2 的 a:item 一致 |
| `+item` | `co_item` | 3 拾取、4 末影箱取出 |
| `-item` | `co_item` | 2 掉落、5 末影箱放入、6 投掷、7 射出 |
| `container` | `co_container` | 0 取出、1 放入 |
| `+container` | `co_container` | 1 放入普通容器 |
| `-container` | `co_container` | 0 从普通容器取出 |

`pickup/withdraw`、`drop/deposit`、`chest/transaction` 等已接通类型的原生别名也可使用。玩家通过 `co_user.rowid` 映射；物品通过 `co_material_map.id` 映射，世界通过 `co_world.id` 映射，这两个 `id` 不是表的 `rowid`。

所有明细按 `time DESC, rowid DESC` 排序。时间、玩家、material、action、包含/排除和正则均在 SQL 内筛选，条件值使用参数绑定。不会读取 `co_item.data` 或 `co_container.metadata`，不会反序列化 ItemStack。

参数和动作依据 CoreProtect 23.2 的 [ActionParser](https://github.com/PlayPro/CoreProtect/blob/v23.2/src/main/java/net/coreprotect/command/parser/ActionParser.java)、[LookupRaw](https://github.com/PlayPro/CoreProtect/blob/v23.2/src/main/java/net/coreprotect/database/LookupRaw.java)、[TabHandler](https://github.com/PlayPro/CoreProtect/blob/v23.2/src/main/java/net/coreprotect/command/TabHandler.java) 实现；COQ 的执行路径独立。

## 尚未接通的功能

不提供 `a:` 时保持原生默认 block 的含义，并提示尚未接通，不会自动改查 item。block、click、kill、inventory、sign、session、username 及其正负形式，`near`、`r:/radius:`、世界/坐标与统计标记等保留帮助和补全，执行时明确拒绝。

物品高级条件留到后续版本：`name`、Lore、CraftEngine 自定义 ID、NBT、Data Components。本版遇到以下输入会提示“本版本尚不支持物品组件条件”，不会退化为全部铁锭：

```text
/coq l a:item t:1d i:minecraft:iron_ingot[custom_name={extra: ["1"], text: ""}]
```

## 分页与显示

每个玩家及控制台分别保存最近查询、固定时间基准及页大小。新查询替换旧查询；翻页不会移动时间窗口或延长有效期。默认 5 分钟过期，最多保存 256 个发送者。分页是对同一时间窗口重新查询 SQL，并非数据库快照；CoreProtect 后续补写/清理该窗口内的记录仍可能影响结果。

COQ 标题、玩家及 material 使用深青色，正文白色、时间灰色，物品记录同时显示 `+/-` 与动作文字。玩家可悬停查看绝对时间（服务器时区）、世界及坐标，点击上一页/下一页执行 `/coq l`。控制台显示纯文本及时间、位置与翻页用法。数据库文字使用普通文本节点，不解析 MiniMessage 或可点击标签；控制字符不作为格式执行。

查询在异步线程运行，消息在服务器主线程发送；同一发送者的前一条查询结束前不再提交另一条。无记录、无最近查询、查询过期、越界、数据库错误和超时分别处理。

## 配置

沿用现有数据库连接配置。新增表名放在 `database.tables` 下，若 CoreProtect 使用自定义前缀，应修改全部相关表名。

```yaml
database:
  tables:
    users: co_user
    chat: co_chat
    command: co_command
    item: co_item
    container: co_container
    materials: co_material_map
    worlds: co_world
query:
  require-time: true
  max-time-seconds: 604800
  page-size: 15
  max-page-size: 100
  max-results: 1000
  timeout-seconds: 5
  session-ttl-seconds: 300
  max-sessions: 256
```

限制同时适用于 lookup 和旧 Java API。`max-time-seconds` 限制区间宽度，不限制记录距今多远，因而 `10d-12d` 可以查询。关闭 `require-time` 后可省略时间条件，此时仍受页大小、可浏览数量和超时限制。旧 `reverse-order` 配置保留，但 lookup 固定倒序。

记录数使用与明细相同的条件，最多检查到 `max-results + 1` 条匹配记录；超过上限显示“仅展示前 N 条”，不称作全库总数。记录上限不等于数据库扫描行数。所有 SELECT 使用 JDBC 超时，并分别使用 MariaDB 的 `SET STATEMENT max_statement_time … FOR SELECT` / MySQL 的 `MAX_EXECUTION_TIME`；共享时间预算覆盖计数、明细及字典查询，不能仅靠 LIMIT 阻止昂贵查询。

新 lookup 界面由 `LookupRenderer` 渲染，旧 `messages.yml` 中的聊天/命令分页模板不再用于 lookup。

## Java API 与代码结构

原有六参数 `QueryRequest(QueryType, String, String, Integer, Integer, String)`、`Main.query(QueryRequest)`、`QueryResult` 和 `QueryRecord` 保持签名不变，AstrBot 的 chat/command 反射调用无需修改。新增的统一限制也会约束这些调用，尤其是默认必须传入时间条件。该 API 是同步的，调用者须继续在异步线程使用。

物品通过独立的 `api.lookup.LookupRequest` / `LookupRecord` / `LookupResult` 及 `Main.getQueryService().lookup(request)` 查询，不借用旧 API 的 message 字段。

```text
QueryCommands + LookupParameters + QueryTabCompleter
  -> CoreProtectQueryService（复用 DataManager 的连接池）
  -> MariaDB/MySQL SELECT
  -> LookupResult -> LookupRenderer

LookupSessions：每个发送者的固定时间与分页状态
QueryLimits：命令和 Java API 共用限制
```

没有新增 Query Planner、StorageAdapter、PostFilter 或组件解码框架。

## 构建与验证

```shell
mvn package
```

产物为 `.asset/CoreProtectAddon-1.1.0.jar`。沿用项目现有的 Paper API 依赖和编译环境；本次本地构建使用 JDK 26。Maven 默认运行无需服务器的解析、补全、会话、动作映射、渲染及限制测试；数据库集成测试需要显式启用。

只在本机临时实例建立空的 `coq_fixture` 数据库（测试默认 root / 空密码），然后运行：

```shell
mvn -Psql-integration "-Dcoq.test.jdbc=jdbc:mysql://127.0.0.1:34579/coq_fixture?sslMode=DISABLED" test
```

集成测试拒绝其他库名和远程主机，使用随机前缀创建、清理测试表。测试数据覆盖全部 item 动作 0–12、普通容器放入/取出、用户和 material 映射、时间边界、正则与包含/排除、固定排序、受限计数和分页、SQL 错误与实际查询超时、旧 Java API 反射契约。数据库驱动只通过测试 profile 引入，不打入插件。

本地已在 MySQL 5.7.24 与 MariaDB 11.4.5 上运行集成测试。游戏内与 `/co l` 的同场景对照、客户端颜色/悬停/点击及真实 AstrBot 网络请求仍需在测试服务器验收；自动化测试不能替代这部分实机检查。
