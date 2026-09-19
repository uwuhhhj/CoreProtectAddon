# near、半径与 WorldEdit 选区查询：源码研究

研究日期：2026-09-19。本轮仅研究，未实现或启用新查询功能。

## 结论与版本基准

可以复现 CoreProtect 的 near、半径、WorldEdit 选区及默认混合查询行为。现有 COQ 已有独立 SQL、时间限制、分页和物品还原基础，不需要转发 `/co` 命令。完整复现这组查询功能需要同时扩展空间条件和多来源结果模型，不能仅把 `near` 改写成一次 `a:block` 查询。

本次对照三组证据：

- CoreProtect `v23.2` 源码：现有 COQ README 声明采用的动作语义。
- 本地 Maven 依赖 `net.coreprotect:coreprotect:24.0` 的字节码：SHA-256 `F807739FA13047015E805BE7EF5A3C6B499CE2D37E061B4FB86CEE126A362492`。确认了 `near → r:5x5`、WE 选区边界读取，以及混合结果排序；这不是对整份 JAR 的完整反编译验证，也未确认它与当前服务器实际加载的 JAR 相同。
- 上游固定提交 [`9cf12ee5bfc55438d681b4ffef18ecbaa3e788b8`](https://github.com/PlayPro/CoreProtect/tree/9cf12ee5bfc55438d681b4ffef18ecbaa3e788b8)：新实现已有实体容器、实体交互、实体位置相关逻辑，内部接口与本地 24.0 不同。不要把浮动 master 当作本地构建的精确行为说明。

实现前应锁定实际服务器构建，以同一数据集上的 `/co` 输出为对照。这里的“完整”指查询功能；回滚、恢复、清理及日志写入不属于本次研究范围。

## 已确认的原版语义

| 输入 | 原版行为 |
| --- | --- |
| `/co near` | 命令入口转交固定参数 `near r:5x5`，中心为发送者位置；玩家场景中 XYZ 各 ±5，即包含边界的 11×11×11 方块区域 |
| `r:5` | X/Z 各 ±5，Y 不受此参数限制；不是球形距离判断 |
| `r:5x3` | X/Z 各 ±5，Y ±3 |
| `r:5x3x8` | X ±5、Y ±3、Z ±8 |
| `r:#we` / `r:#worldedit` | 读取玩家 WE 选区，转换为 XYZ 包围盒，并要求选区世界与玩家当前世界一致 |
| `r:#<世界名>` | 指定世界；世界参数由 `WorldParser` 处理 |
| `r:#global` | 取消空间/世界限制的语义，仍保留其他过滤条件 |

依据：[CommandHandler v23.2](https://github.com/PlayPro/CoreProtect/blob/v23.2/src/main/java/net/coreprotect/command/CommandHandler.java)、[LocationParser v23.2](https://github.com/PlayPro/CoreProtect/blob/v23.2/src/main/java/net/coreprotect/command/parser/LocationParser.java)、[WorldParser](https://github.com/PlayPro/CoreProtect/blob/9cf12ee5bfc55438d681b4ffef18ecbaa3e788b8/src/main/java/net/coreprotect/command/parser/WorldParser.java)。

注意这些容易造成“看似兼容”的差异：

1. 原版命令入口固定替换 near 的参数，不是给用户参数补一个默认 `r:5`。如果 COQ 允许 `/coq near a:item t:1h`，这是有用的扩展，但需要说明与原版入口不同。
2. WE 球形、多边形等选区也是按包围盒查询，原版这里没有逐条调用 `Region.contains()`。精确形状筛选属于增强功能，并会改变结果数量和分页成本。[WorldEditHandler](https://github.com/PlayPro/CoreProtect/blob/9cf12ee5bfc55438d681b4ffef18ecbaa3e788b8/src/main/java/net/coreprotect/command/WorldEditHandler.java)
3. 默认查询不是只有放置/破坏。v23.2 的默认 lookup 合并 `block`、`container`、`item`；block 来源还可能包含交互、实体击杀记录。item 分支排除原版指定的 inventory-only 动作。混合结果按 `time DESC, tbl DESC, id DESC` 排序。[LookupRaw v23.2](https://github.com/PlayPro/CoreProtect/blob/v23.2/src/main/java/net/coreprotect/database/LookupRaw.java)
4. near 本身不注入时间条件；原版查询路径可以从历史起点查询。COQ 当前 `query.require-time: true` 和最大时间跨度属于明确的行为差异，不能悄悄填 `t:1d` 后宣称一致。[LookupCommand](https://github.com/PlayPro/CoreProtect/blob/9cf12ee5bfc55438d681b4ffef18ecbaa3e788b8/src/main/java/net/coreprotect/command/LookupCommand.java)

## COQ 当前缺口

| 位置 | 当前状态 | 需要调整 |
| --- | --- | --- |
| `command/LookupParameters.java` | near、radius、world、location 明确拒绝；缺少 action 时通常推断 block 后报错 | 解析空间表达式；用独立的“默认混合”模式表达省略 action |
| `api/lookup/LookupRequest.java` | 只有单一 action，无空间条件 | 增加不可变空间快照，保留旧构造入口；`withPage` 保留全部条件 |
| `command/QueryCommands.java` | 主线程解析，异步查询 | 主线程捕获位置/WE 选区后启动会话，异步线程不再读取玩家或 WE 对象 |
| `service/CoreProtectQueryService.java` | 单表查询，共用 where；已有组件候选扫描 | SQL 层加入世界和范围过滤；支持多来源合并和一致计数 |
| `api/lookup/LookupRecord.java` | 只有 action 数字，无来源类型 | 增加来源、来源内 ID 与必要元数据；不同表 action 数字不能混用 |
| `command/LookupRenderer.java`、物品面板 | 多处按查询整体 `isItem()` 判断 | 混合结果必须逐记录判断；明确混合查询与物品面板的关系 |
| `Tables`、`DataManager`、配置 | 只声明/校验现有七张表或视图 | 添加 block 及目标版本需要的其他来源，更新各数据库校验 |
| `plugin.yml`、POM | 没有 WE 集成声明 | 添加可选依赖和独立桥接类；未安装 WE 时普通查询仍能启用 |

已有 SQL 明细读取 chat/command/item/container 的 `wid,x,y,z`，所以现有类型加入空间条件有直接落点。已有计数和组件筛选不能绕过新条件。

另一个明确冲突：COQ 的 `c:` 是 `content:` 别名，CoreProtect 的 `c:` 用于坐标。完整命令语法兼容必须制定迁移规则；第一步可以保留现有 `c:`，新增不歧义的 `coord:`，但不能宣称所有别名已完全一致。当前 `x/y/z` 还映射到同一个占位 key，也需要拆分或改为统一坐标参数。

## 建议实现结构

建议采用以下独立层次，名称为设计建议，并非已存在代码：

```text
LookupParameters：解析 Near / NumericRadius / WorldEdit / World / Global
    ↓
SpatialResolver：主线程捕获世界与整数边界
    ↓
LookupRequest + LookupSessions：保存不可变查询条件和固定时间基准
    ↓
QueryPlan：决定来源表、动作映射、空间谓词、结果排序
    ↓
CoreProtectQueryService：异步计数、分页和元数据读取
    ↓
LookupRenderer：按每条记录的来源渲染
```

空间快照包含世界名及 min/max X/Z，可选 min/max Y；不要存 Bukkit `Location` 或可变 WE `Region`。未限制世界、指定世界、指定区域应明确区分。解析时校验非负半径、维数、数值溢出和未知世界，非法参数不能退化成全库查询。

异步阶段用实际查询数据库的 `co_world.id` 解析世界，不能使用 `co_world.rowid`，也不能假定另一个 CoreProtect 数据源的内存世界 ID 与当前 COQ 目标库一致。随后生成参数化条件：

```sql
AND wid = ?
AND x BETWEEN ? AND ?
AND z BETWEEN ? AND ?
-- 只有三维半径或 WE 选区才添加：
AND y BETWEEN ? AND ?
```

所有来源都先应用过滤，再合并、计数、分页。不能各表取一页后拼接，不能分页后再筛坐标。混合查询需来源稳定序号作为排序键；记录唯一键为 `(source, rowId)`。当前组件匹配使用按 rowId 的映射时也要审查跨表冲突。

WE 桥接使用公开 API 取得 `LocalSession`、`getSelectionWorld()`、`getSelection(world)`，捕获未完成选区错误，复制边界数值。无需遍历选区内每个方块。[WorldEdit 官方 LocalSession 示例](https://worldedit.enginehub.org/en/latest/api/examples/local-sessions/)

依赖采用 provided/可选加载方式，桥接类只在能力检测成功后初始化。FAWE 可以作为同一 WE API 桥接的兼容目标，但本轮未在 FAWE 运行时验证，不应提前宣称已兼容全部版本。

## 完整复现的边界与实施顺序

建议按三步推进，最终以结果集一致性验收：

1. **空间筛选基础**：所有已支持 action 接入 `r:`、WE、世界条件；固定翻页范围，保留组件搜索与原有 Java API。更新帮助和补全，不再显示尚未生效的参数。
2. **near 默认混合查询**：接入 block 与动作/实体类型字典，增加来源模型及跨表 SQL；复现默认来源组合、动作排除、排序和 rolled_back 展示。同步决定无时间 near 如何与 COQ 的查询限制共存。
3. **目标版本查询能力对齐**：逐项补齐 click/kill/inventory/sign/session/username、玩家/实体排除、统计及其他标志；如果目标是当前上游，还需实体容器、实体交互、实体当前位置等新模型。不能把本地旧表结构硬套到新源码。

查询能力对齐可行，但“支持 near/r/WE”和“完整复刻整个 `/co`”的工作量差别很大。数据库已记录的信息可以查询；目标构建没有记录的信息不能从 Addon 补出来。现有 COQ 的组件 content、只读数据库和有界查询策略也需要保留明确的产品语义。

## 验收重点

- 在相同数据库与相同中心，对照 `/co` 和 `/coq`；记录来源、动作、世界、坐标、排序及分页总数。
- 正负坐标、边界内外一格、同 X/Z 不同高度、不同世界相同坐标、`r:0`、小数半径、非法维数与溢出。
- WE 未安装、未完整选择、跨世界选区、普通长方体和非长方体包围盒；FAWE 单独验收。
- 玩家移动、传送或重选 WE 后，旧会话翻页仍使用原范围。
- 同秒跨表记录、不同表相同 rowId、原版默认排除的 item 动作、block 实体记录与 material ID 的区分。
- 无时间 near、结果上限、超时、计数和组件筛选一致；未知世界/选区无效时明确报错。
- MariaDB/MySQL、ClickHouse 兼容视图和 DuckDB 只读事务分别执行真实 SQL 验证；旧 AstrBot 六参数 API 回归。

本轮完成源码阅读及本地依赖的针对性字节码核对，没有运行 Minecraft/WE 服务端对照测试，也没有编译或修改功能代码。
