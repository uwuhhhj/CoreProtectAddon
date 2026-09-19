# COQ 1.6.0 查询重构

本版以 CoreProtect 24.0 的查询动作和数据库结构为基准，继续独立执行只读 SQL。命令入口为 `/coq`，不接管 `/co`，也不依赖 CoreProtect 的查询/检查器缓存。

## 用法

```text
/coq near t:1h
/coq lookup t:1h r:20
/coq l t:1h r:20x10x30
/coq l t:1h r:#we
/coq l a:block t:1d r:#worldedit
/coq l a:-block t:1h i:stone e:Alex
/coq l a:click t:1h r:5x5
/coq l a:kill t:1h i:zombie
/coq l a:kill t:1h i:player
/coq l a:inventory u:Steve t:1h
/coq l a:+inventory u:Steve t:1h
/coq l a:sign t:1h content:"hello.*world"
/coq l a:+session u:Steve t:1d
/coq l a:username u:Steve t:1d
/coq l a:item t:1h world:world coord:10,64,20 r:5x5
/coq l a:container t:1h r:#world
/coq l a:chat t:1h r:#global c:hello
/coq l a:block t:1h i:#door #count
/coq l t:1h r:10 #verbose
/coq l 2:15
```

上面的 `r:#world` 表示名为 `world` 的世界。不存在的世界不会自动回退到当前世界。`r:#global` 无世界/坐标限制；仍受时间、类型等条件限制。

普通 `r:5` 只限制 X/Z，各 ±5；`r:5x3` 限制 X/Z ±5、Y ±3；`r:5x3x8` 分别限制 X/Y/Z。范围包含边界。玩家中心使用向下取整后的方块坐标，负坐标与原版一致。半径接受非负小数并按原版截为整数；非法数值、溢出和超过三维的输入直接拒绝。

`near` 默认使用 `r:5x5`，并允许显式过滤参数覆盖。这是 COQ 扩展：原版命令入口会用固定 near 参数替换输入。COQ 默认仍要求 `t:`，不自动补时间，也不更改原有最大时间跨度、超时和结果上限。

`c:` 保留为 `content:`。坐标使用 `coord:/coords:/coordinate:/coordinates:/location:/loc:/position:`，支持 `x,z` 或 `x,y,z`；也可分写 `x: y: z:`。控制台半径查询需要世界和坐标。仅给坐标时按 X/Z 精确位置筛选；需要精确 XYZ 时显式加 `r:0x0`。

## 查询类型和结果来源

| 类型 | 来源及行为 |
| --- | --- |
| 省略 a: | 默认合并 block、container、item；仅正向包含非方块物品时保留 COQ 的自动 item 推断 |
| block / +block / -block | block 的 action 0、1；正号放置，负号破坏 |
| click | block 的 action 2 |
| kill | block 的 action 3；实体类型从 entity_map 解析，type=0 时 data 关联被击杀玩家 |
| item / ±item | 延续既有 CoreProtect 物品动作映射 |
| container / ±container | 容器存取；正号放入，负号取出 |
| inventory / ±inventory | 玩家背包方向；跨 block/container/item 查询，排除原版指定的火、水、耕地和漏斗用户；必须指定玩家 |
| chat / command | 聊天和命令；content 为数据库正则 |
| sign | action=1 的非空招牌文字；按 face 选择正面/背面四行，content 为数据库正则 |
| session / ±session | 登录和退出，正号登录，负号退出 |
| username | username_log，按用户 UUID 关联历史名字；没有位置，拒绝空间参数 |

`i:` 支持物品/方块或实体；不能混合已识别的实体与物品。`e:` 依次匹配物品、实体、玩家，排除优先；未知排除值明确报错。环境用户如 `#hopper` 按数据库已记录的名字查询。CoreProtect 的六个方块组 `#button/#container/#door/#natural/#pressure_plate/#shulker_box` 使用其公开分组定义，在命令线程展开，不在数据库线程访问 Bukkit。

混合记录以 `(source,rowId)` 区分，按 `time DESC, source_order DESC, rowid DESC` 排序；来源顺序为 block=0、container=1、item=2。SQL 在计数及分页之前应用全部空间和类型条件；不分别取各表一页后拼接。物品二进制元数据仅按当前返回页或组件候选批次加载，计数不读取它们。

`#count/#sum` 按所对照版本表示记录计数，不是物品数量求和。仍遵守 COQ `max-results`，超限显示“超过 N 条”。`#verbose` 增加来源、记录 ID、世界和坐标文本，`#silent` 取消该扩展显示；不抑制查询错误。旧物品面板保留，仅适用于 item/container 明细查询；混合结果和计数查询不会被伪装成物品面板。

## WorldEdit / FAWE

依赖为可选。主线程通过 WorldEdit 公开 API 取得玩家 session、选区世界、选区 min/max 坐标，然后只把不可变整数边界传给异步查询。未安装、选区不完整、选区不在当前世界或 API 不兼容分别报错，不退化成无范围查询。

与 CoreProtect 一样，非长方体选区按包围盒处理。玩家移动、换世界或重新选择后，已有查询的翻页仍使用第一次的范围。WE 选区不能与额外 world/coord 条件混用。

## 数据库与 API

新增配置项 `database.tables.block/entities/session/sign/username`，默认分别对应 `co_block/co_entity_map/co_session/co_sign/co_username_log`。配置前缀覆盖这些表名，DuckDB 继续使用 CoreProtect 运行时前缀。启动/重载校验扩展至十二张表/兼容视图及所需列。

旧六参数 `QueryRequest`、旧 `LookupRequest` 构造入口及旧结果构造入口保留。扩展 API 使用 `LookupOptions` 和 `SpatialBounds`；直接调用查询服务时必须提供已解析的边界，不能传入需要玩家上下文的 `r:#we` 等原始参数。`withPage` 保留所有过滤条件，原有固定时间基准、有效期和异步队列继续生效。

本版实现上述独立 lookup 查询入口及 near/page/help，不提供 CoreProtect 的回滚、恢复、purge、检查器状态或依赖检查器的 `u:#container`。`#preview` 是回滚语境的参数，独立 lookup 仍明确拒绝。上游后续增加的实体移动位置追踪、实体容器/交互新表不属于本地 24.0 结构，不能仅凭主版本号认为完全兼容。

## 验证方式

```powershell
mvn -o '-Dcoq.build.directory=target/verify-spatial' '-Dcoq.test.coreprotectJar=.asset/CoreProtect-24.0-patched.jar' test
# 独立本机 MariaDB/MySQL 测试库，仅允许名为 coq_fixture 的数据库：
mvn -o -Psql-integration '-Dcoq.build.directory=target/verify-spatial' '-Dcoq.test.jdbc=jdbc:mysql://127.0.0.1:13327/coq_fixture' '-Dcoq.test.coreprotectJar=.asset/CoreProtect-24.0-patched.jar' test
```

`SpatialCommandTest` 对照原版半径和动作解析；`SpatialLookupTest` 在真实 DuckDB 上验证所有新来源、边界、跨表同 ID、世界字典 ID、背包方向、文字/UUID、计数和组件候选。既有测试继续覆盖数据库错误、超时、只读事务、API、组件搜索和分页。

`src/test/runtime/SpatialProbe.java` 是仅供隔离服务器使用的运行时验证插件，测试结束主动关服，不能放入生产服务器。它验证真实 WorldEdit 的未完成选区、长方体、椭球包围盒、翻页快照、near 以及 CoreProtect 方块组。测试入口不会打入发布 JAR。
