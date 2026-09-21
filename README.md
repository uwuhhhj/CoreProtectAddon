# CoreProtectAddon / COQ 1.6.2

**使用教程：[管理员指令教程](docs/COMMAND_GUIDE.md)** — 从附近查询、箱子物品追踪到自定义组件筛选，包含操作示例、面板说明和常见问题。

1.6.2 将数据库初始化、重载连接检查、连接关闭和白名单文件写入移到后台；增加有界查询队列、取消控制及元数据内存限制，并将组件快照比较、详情和 debug 文本生成移到工作线程。保持已有查询语法，详见下方“线程与负载控制”。

1.6.1 支持组件名存在性筛选、`组件=值` 简写，以及递归查找配置白名单组件内的完整字符串值；debug 可分行复制全部有效组件，并通过按钮保存白名单增删。保留 1.6.0 的统一多来源查询能力。

1.6.0 重构 lookup 为统一的多来源查询，新增附近/半径/WE 选区、方块/交互/击杀/背包/招牌/登录/改名记录及计数。

COQ 是独立执行查询的 CoreProtect 附属插件。使用 CoreProtect 风格的 lookup 参数与交互，支持方块、交互、击杀、背包、聊天、命令、物品、容器、招牌、登录/退出及改名记录，查询 MariaDB/MySQL、ClickHouse 或 DuckDB；不调用 `/co lookup`，不共用 CoreProtect 查询缓存。编译依赖为 CoreProtect 24.0，现有 AstrBot Java API 保持兼容。

1.5.0 使用 `content:` 查询历史物品的全部有效组件（包含默认值），面板点击只输出配置白名单中的组件，每行复制完整条件，坐标点击执行 `/tppos x y z`。筛选、悬停和面板共用 `ItemSnapshot` 历史数据。

1.4.0 修复省略 action 时的非方块物品查询、历史物品悬停，新增一次性箱子面板。

1.3.0 新增针对已提供的 `CoreProtect-24.0-patched.jar` 的 DuckDB 只读查询。自动定位 CoreProtect 数据目录中的现有文件及运行时表前缀，复用其数据库实例，为每次查询建立独立的 `READ ONLY` 事务。MySQL/MariaDB、ClickHouse 及已有查询功能保留，切换不会迁移历史数据。

## 与 CoreProtect 本体的关系

本节比较当前 COQ 与 CoreProtect 本体，不是与 CoreProtectAddon 早期上游版本比较。CoreProtect 负责记录事件，并提供检查、查询、回滚和恢复；COQ 读取其已有日志，提供独立查询、历史物品组件筛选和查看功能。两者可以配合使用：一般检查与回滚使用 `/co`，需要组件筛选或物品面板时使用 `/coq`。本体命令见 [CoreProtect 官方文档](https://docs.coreprotect.net/commands/)。

### 共同点与分工

| 方面 | CoreProtect 本体 | 当前 COQ |
| --- | --- | --- |
| 历史数据 | 记录服务器事件并保存日志 | 读取配置所指向的 CoreProtect 表或兼容视图，不另建一份事件日志 |
| 查询习惯 | `/co lookup`，按玩家、时间、动作、类型和范围筛选 | `/coq lookup`，沿用相近参数、动作语义、半径和 WE 选区用法；兼容边界见下文 |
| 执行方式 | 本体的查询流程 | 自己解析参数、执行 SQL、维护分页会话和渲染结果 |
| 管理操作 | 检查器、回滚、恢复、清理日志等 | 不提供上述操作，不接管日志记录、数据库迁移或清理 |
| 物品调查 | 提供物品、容器等历史记录查询 | 增加组件存在性、完整组件值及白名单内嵌套字符串筛选，配合历史物品悬停、箱子面板和条件复制 |
| 配置与权限 | 本体配置及权限 | 独立配置、`coreprotectaddon.command.*` 权限和 `/coq reload`；两者重载互不代替 |

COQ 不会补记历史：目标数据库中未记录、尚未写入或已被清理的事件无法查询；CoreProtect 未保存的物品信息也不能凭空恢复。两个入口只有在指向同一份数据、使用等价条件且未触及各自限制时，才适合对照结果，不应仅因命令相似就假定结果完全相同。

### 独立查询的依赖与边界

- **执行路径独立，部分能力仍依赖本体。** `plugin.yml` 将 CoreProtect 声明为可选依赖（`softdepend`），但历史物品元数据还原会调用本体的 `RollbackUtil.populateItemStack`，方块组展开也使用本体定义，不能据此认为所有功能都能脱离 CoreProtect 运行。MySQL/MariaDB、ClickHouse 读取 Addon 自己的连接配置；DuckDB 必须复用已启用、接口兼容的特定 CoreProtect 构建的数据库实例。详见[配置](#配置)。
- **参数兼容有范围。** COQ 默认要求 `t:`（可通过 `query.require-time` 调整），`c:` 始终表示内容，坐标用 `coord:`；`near` 可附带过滤参数。它没有本体检查器状态，不支持 `u:#container` 或回滚参数 `#preview`。当前以本地 CoreProtect 24.0 数据结构为基准，不保证兼容上游新增表。详见[查询兼容范围](#查询兼容范围)。
- **数据库只读与领取副本是两回事。** COQ 不修改历史日志，但箱子面板允许创造模式玩家左键领取还原的物品副本，并可重复领取。这会向玩家背包添加物品，不会撤销原交易、扣除其他玩家的物品或回滚容器；不是 CoreProtect 的 rollback/restore。详见[一次性箱子面板](#一次性箱子面板)。
- **组件搜索不保证比本体查询更快。** 普通条件在 SQL 内筛选；组件条件还需分批读取元数据，并在服务器主线程队列中还原物品、匹配组件。应先用时间、玩家、物品类型缩小范围；候选数和超时限制可能使搜索中止。分页固定时间窗口但不是数据库快照，后续补写或清理仍会影响结果。

### 适合使用 COQ 的场景

同为 `paper` 的物品可能属于不同自定义道具。把保存标识的 `minecraft:custom_data` 加入 `item-panel.component-whitelist` 后，可以按完整字符串值查找某个道具；也可按地图编号定位记录：

```text
/coq l a:item t:1d i:paper content:"smc:dou_dizhu_table"
/coq l a:item t:1d content:map_id=101205
/coq items
```

第一条在白名单组件内递归匹配字符串值，不依赖 CraftEngine，也不是子串搜索；第二条要求 `minecraft:map_id` 的值匹配。每次新查询会替换最近查询，`/coq items` 打开最近一次物品/容器查询的面板，可查看组件并复制条件继续筛选。完整规则见 [content 组件搜索](#content-组件搜索)。

## 命令

需要 `coreprotectaddon.command.query` 权限，默认 OP 可用。

`/coq` 显示常用命令首页，`/coq help [1-3]` 分为查询入门、筛选与组件、面板与管理，游戏内支持点击填入命令和帮助翻页。
`/coq reload` 使用独立权限 `coreprotectaddon.command.reload`（默认 OP）。它在后台读取配置、消息并建立和检查候选数据库连接，验证成功后才在主线程切换；期间旧配置与连接继续提供查询。成功后关闭旧面板、清空旧会话、取消旧任务并丢弃晚到结果，需重新查询；失败保留旧配置与连接，修正文件后可再次重载。重载和白名单保存串行执行，重复操作会提示稍候。

```text
/coq
/coq help
/coq help 2
/coq reload
/coq near t:1h
/coq l t:1h r:#we
/coq l a:block t:1h r:20
/coq l a:inventory u:Steve t:1h
/coq l t:1h #count
/coq l a:chat u:Steve t:1d
/coq lookup a:command u:Steve t:1d content:"^/op .*"
/coq l a:item i:iron_ingot t:1d
/coq l a:+item u:Steve t:1h
/coq l a:container i:diamond e:iron_ingot t:1d
/coq l a:item u:Steve,Alex i:iron_ingot,diamond e:diamond t:1d
/coq l 2
/coq l 2:30
/coq page 2
/coq lookup user:Loliiiico include:potion time:1h
/coq l a:item i:potion t:1h content:{"minecraft:max_stack_size":1}
/coq items
/coq items 2:15
```

`/coq chat`、`/coq command` 已移除，没有 `/coq item`。分别改用 `/coq l a:chat`、`/coq l a:command`、`/coq l a:item`。

| 参数 | 别名 | 行为 |
| --- | --- | --- |
| `u:` | `user:`、`users:`、`p:` | 玩家名，支持逗号列表；多个玩家取并集 |
| `t:` | `time:` | 支持 `1h`、`1.5h`、`1d2h`、`1w`、`10d-12d`；单位为 `y/mo/w/d/h/m/s` |
| `a:` | `action:` | 选择记录类型与动作方向 |
| `i:` | `include:`、`item:`、`items:`、`b:`、`block:`、`blocks:` | 包含的物品/方块或实体类型；列表取并集，不混用已识别的实体与物品 |
| `e:` | `exclude:` | 排除物品、实体或玩家；排除优先于包含 |
| `content:` | `c:`、`message:`、`m:`、`command:` | chat/command/sign 使用数据库正则（含空格加引号）；item/container 支持组件存在性、完整值、物品 ID 及白名单内字符串值筛选，见下文 |
| `page:` | — | 新查询中的页码，如 `page:2`、`page:2:30`；单独 `/coq l page:2` 继续最近查询 |
| `rows:` | — | 新查询每页条数，也可以使用 `page:1:30` |
| `r:` | `radius:` | 半径、WE 选区、世界或 #global |
| `world:` | `w:` | 世界名 |
| `coord:` | `coords:`、`coordinate:`、`coordinates:`、`location:`、`loc:`、`position:` | 查询中心；c: 保留为内容筛选 |

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

单一来源按 `time DESC, rowid DESC` 排序；混合查询按 `time DESC, source_order DESC, rowid DESC` 排序，以来源和 ID 共同区分记录。时间、玩家、material、action、包含/排除和消息正则均在 SQL 内筛选，条件值使用参数绑定。不带组件条件时，只读取返回页的 `co_item.data` 或 `co_container.metadata`，计数不读取元数据；带组件条件时按最多 32 条的小批次读取候选元数据，先筛选再计数、分页。DuckDB 按列序号读取 BLOB，ClickHouse 兼容视图的 `Array(Int8)` 空值/二进制标记按 CoreProtect 格式移除。

参数和动作依据 CoreProtect 23.2 的 [ActionParser](https://github.com/PlayPro/CoreProtect/blob/v23.2/src/main/java/net/coreprotect/command/parser/ActionParser.java)、[LookupRaw](https://github.com/PlayPro/CoreProtect/blob/v23.2/src/main/java/net/coreprotect/database/LookupRaw.java)、[TabHandler](https://github.com/PlayPro/CoreProtect/blob/v23.2/src/main/java/net/coreprotect/command/TabHandler.java) 实现；COQ 的执行路径独立。

## 查询兼容范围

1.6.0 已接通 `block/+block/-block`、`click`、`kill`、`inventory/+inventory/-inventory`、`sign`、`session/+session/-session`、`username`，并保留聊天、命令、物品、容器及组件查询。省略 `a:` 时默认合并方块（含交互/击杀）、容器和物品；仅包含非方块物品时保留自动推断 item。`inventory` 必须指定玩家，按玩家背包的增减方向映射各表动作。

新增 `/coq near t:1h`、`r:/radius:`、`r:#we/#worldedit`、`r:#世界名`、`r:#global`、`world:/w:`、`coord:/coords:/location:/loc:/position:` 和 `x:/y:/z:`。普通 `r:5` 只限制 X/Z；`r:5x5` 限制 XYZ；`r:5x3x8` 分别指定三轴。WE/FAWE 为可选依赖，查询使用选区包围盒，并要求选区位于玩家当前世界。翻页保留原世界与边界。

`#count/#sum` 按所对照的 CoreProtect 24 语义只计记录条数，遵守 COQ 结果上限；`#verbose` 在记录行显示来源/ID/坐标，`#silent` 关闭此扩展显示。`e:` 可排除物品、实体或玩家（按这个顺序匹配）；`u:` 支持实际已记录的环境用户如 `#hopper`。CoreProtect 方块组 `#button/#container/#door/#natural/#pressure_plate/#shulker_box` 在命令线程展开。

保留 COQ 差异：默认必须指定时间；`c:` 始终是 `content:`，坐标使用 `coord:`；near 可携带时间及其他过滤参数。`#preview` 等回滚参数和依赖 CoreProtect 检查器状态的 `u:#container` 不属于独立查询入口。查询代码以本地 24.0 数据结构为基准，不声称兼容上游新加入的实体位置追踪表。详见 [1.6.0 查询说明与验证](docs/LOOKUP_1.6.0.md)。

`i:/e:` 的物品条件只填写类型。下面的方括号组件写法仍会拒绝，并提示改用 `content:`，不会退化为全部铁锭：

```text
/coq l a:item t:1d i:minecraft:iron_ingot[custom_name={extra: ["1"], text: ""}]
```

## content 组件搜索

白名单组件中的字符串值可以直接查询，无需填写完整的组件和子节点路径。例如把 `minecraft:custom_data` 加入 `item-panel.component-whitelist` 后：

```text
/coq lookup user:Loliiiico action:item time:1d include:paper content:"smc:dou_dizhu_table"
```

这会在白名单指定的全部组件中递归查找字符串值，包括对象的字段值和列表元素。示例路径为 `minecraft:custom_data → craftengine:id → "smc:dou_dizhu_table"`；同一对象里的其他字段不会影响匹配。
组件和字段名均不写死，不依赖 CraftEngine；更深层对象、列表、其他插件使用的字段名也采用同一规则。只匹配完整字符串值（区分大小写），不匹配字段名、子串或把数值转换成字符串后比较。
白名单中的组件可以使用完整命名空间或省略 `minecraft:`；此项搜索使用整个名单，不受面板展示前 12 项的限制。空名单关闭省略路径的值搜索，移出组件后该组件不再参与值搜索。
已注册的组件名仍表示组件存在性，已注册的物品 ID 仍匹配物品类型，优先于嵌套值搜索；显式写 `custom_data=...` 时仍比较完整组件值，也不受白名单限制。

`content:"minecraft:filled_map"` 匹配已填充地图物品；`filled_map` 是物品 ID，地图编号组件是 `minecraft:map_id`。
Tab 补全后只填组件名，就筛选所有拥有该有效组件的物品；追加 `=值` 才要求精确值。组件名可以省略 `minecraft:`，简单值不需要引号或额外的物品 ID：

```text
/coq lookup user:Loliiiico time:1d action:item content:"minecraft:filled_map"
/coq lookup user:Loliiiico time:1d action:item content:map_id
/coq lookup user:Loliiiico time:1d action:item content:map_id=101205
```

`content:"minecraft:map_id=101205"` 和 Tab 补全后直接追加的 `content:"minecraft:map_id"=101205` 也接受。
原有 `content:{"minecraft:map_id":101205}`、`content:"minecraft:filled_map[map_id=101205]"` 继续可用。
方括号内多个条件用逗号分隔，必须全部满足，单写组件名表示存在。
在 `content:"` 后按 Tab 从 `item-panel.component-whitelist` 补全组件 ID；也支持无引号、单引号及 `c:` 别名。
手动修改配置后执行 `/coq reload` 即可更新补全、面板展示与省略路径的值搜索范围。也可从 debug 的组件行直接增删白名单；显式组件条件不受白名单限制。

`/coq debug item [页码]`（也可直接 `/coq debug`）查看当前主手物品全部有效组件，包括默认值，不受白名单和面板 12 项限制。
每页 10 个组件，每行提供 `[复制值]` 和 `[复制条件]`；预览省略不会截断复制内容。翻页重新读取当前主手物品，但仅编码当前页的组件；编码进入共享主线程队列，详情文本和复制条件在工作线程生成。
拥有 `coreprotectaddon.command.reload` 权限时，每行额外提供 `[加入白名单]`、`[移出白名单]` 两个按钮，在后台保存到配置文件的 `item-panel.component-whitelist`，保存成功后更新补全与面板，无须手动重载；重复点击不会产生重复项。
也可使用 `/coq debug whitelist add <组件名>` 或 `/coq debug whitelist remove <组件名>`。
使用独立权限 `coreprotectaddon.command.debug`，默认 OP；空手或控制台执行会提示。无需 `/paper dumpitem`。

查询并打开 `/coq items`，点击物品，再点击聊天中的某个组件行：复制的是 `{"完整组件ID":完整值}`，直接粘贴在 `content:` 后即可，无须额外包一层引号。`include:potion` 继续限定物品类型。聊天/命令的 `content:` 保持正则语义。

```text
/coq l a:item i:potion t:1h content:{"minecraft:max_stack_size":1}
/coq l a:item i:potion t:1h content:{"minecraft:max_stack_size":1,"minecraft:rarity":"common"}
```

显式组件条件支持所有有效组件，不受面板白名单限制。仅指定组件名时检查存在；指定值时要求完整值相等。多个组件须全部匹配，物品的其他组件不影响结果。复合对象的字段顺序不影响匹配，列表顺序和 NBT 数字类型保留；例如 `1` 与 `1b` 不相等。复杂名称、Lore、药水效果、附魔和 `custom_data` 建议直接复制，避免手动转义。显式对象条件不采用子串匹配或局部对象包含语义；省略路径的字符串值搜索则按上文的白名单递归规则处理。

有效组件由历史元数据还原的物品及服务器当前物品默认值组成，与悬停展示一致；CoreProtect 未保存的旧版本默认值不能凭空恢复。查询不使用管理员当前手持物品。白名单外的组件可手写条件，或加入展示白名单后复制。

单条 `content:` 最多 16384 字符、64 个组件，命令括号嵌套最多 64 层；空对象与格式错误明确拒绝。缺失组件不匹配；历史元数据无法还原时中止组件查询，避免把未知数据当作不匹配。服务器原生完整组件编码接口不兼容时明确报错。

## 分页与显示

每个玩家及控制台分别保存最近查询、固定时间基准及页大小。新查询替换旧查询；翻页不会移动时间窗口或延长有效期。默认 5 分钟过期，最多保存 256 个发送者。分页是对同一时间窗口重新查询 SQL，并非数据库快照；CoreProtect 后续补写/清理该窗口内的记录仍可能影响结果。

COQ 标题、玩家及 material 使用深青色，正文白色、时间灰色，物品记录同时显示 `+/-` 与动作文字。玩家可悬停查看绝对时间（服务器时区）、世界及坐标，点击上一页/下一页执行 `/coq l`。控制台显示纯文本及时间、位置与翻页用法。数据库文字使用普通文本节点，不解析 MiniMessage 或可点击标签；控制字符不作为格式执行。

物品名称带原生 `SHOW_ITEM` 悬停，使用 CoreProtect 的元数据还原逻辑和 Paper 的物品组件序列化，保留名称、Lore、药水效果、自定义数据等历史信息。其余文字继续显示时间和坐标。损坏或不兼容的记录显示详情无法还原，其余记录仍可查看；控制台不反序列化物品。

### 一次性箱子面板

完成 `a:item` 或 `a:container` 查询后，点击结果末尾的 `[箱子面板]`，或输入 `/coq items`，打开最近查询的当前页。`/coq items 2` 指定页码，`/coq items 2:15` 同时指定每页条数。面板保持相同筛选条件、排序、固定时间基准、结果上限和有效期，翻页成功后同步聊天查询的页码。

每条记录占一个物品格，最后一行为上一页、页码、关闭和下一页。每页最多 45 条，超过会提示手动调整，不会静默截断当前页。展示堆叠数量最多为该物品堆叠上限，原始记录数量在点击详情中显示。面板不修改物品的名称或 Lore。

非创造模式左键 / 右键、创造模式右键点击物品，输出记录 ID、玩家、动作、物品数量、时间、世界、页码和配置白名单中的组件。坐标按钮执行 `/tppos x y z`（由服务器现有传送命令处理权限及所在世界）。组件行显示截短预览，点击复制完整 SNBT 条件；不会输出全部组件清单。超过条件长度上限的值明确标记，不能复制成一个不完整条件。

面板只保留当前页；关闭、退出服务器、换查询或插件停用后清空物品、取消待还原任务并释放数据。关闭后的异步 SQL 结果会被丢弃，不会重新打开面板。原有轻量查询会话仍保留到过期，方便重新查询；面板和物品本身不缓存。关闭时请求取消未完成的查询，旧任务实际退出前不能再次提交同一玩家的查询。创造模式左键领取历史物品副本到背包，保留原组件，数量与当前格显示一致（不超过单组上限），历史展示保留，可以再次领取。背包满时提示，不覆盖物品或掉落副本；无法还原的占位物品不能领取。其他模式仅查看；快捷栏交换、Shift、双击、丢弃与拖拽继续取消，导航按钮不能取出。

SQL 查询异步执行。组件条件每次查询只编译一次，固定该次搜索的白名单；主线程仅还原并提取需要的组件，交接后的独立 NBT 数据只读，值比较与嵌套字符串搜索在工作线程执行，不访问实时物品或注册表。面板详情的文本和复制条件也在工作线程生成。聊天悬停、箱子面板、点击详情、debug 与组件提取共用一个轮转队列，所有玩家合计每 tick 最多处理 2 件，在每件操作后检查 2ms 时间预算。筛选仅编码所选组件，不生成悬停对象；展示不预读下一页。单次 Bukkit/组件编码操作无法中途抢占，因此不是绝对 2ms 的耗时保证；序列化体积和对象深度另有限制。翻页期间禁止重复提交，同一面板点击有 250ms 间隔。

组件搜索最多检查 `query.component-max-candidates` 条候选，且包含主线程排队的整个查询受 `query.timeout-seconds` 约束。超过任一上限会报“搜索未完成”，不返回假空结果或不完整总数；请优先缩小时间、玩家和物品条件。搜索临时持有一个候选批次和目标页，不缓存全部物品；关闭面板会请求取消搜索，不再显示晚到结果；已经进入 JDBC 或单件物品还原的操作仍须等待驱动响应或当前操作返回，不承诺瞬间终止。

查询在异步线程运行，消息在服务器主线程发送；同一发送者的前一条查询结束前不再提交另一条。无记录、无最近查询、查询过期、越界、数据库错误和超时分别处理。

## 线程与负载控制

- 启动时异步初始化数据库，未完成时 `/coq` 提示稍候；就绪后注册完整查询入口。重载连接检查、旧连接关闭和白名单文件写入也在后台执行，主线程不等待 JDBC。主线程仍负责游戏状态、配置发布、物品还原和组件提取。
- 命令、面板与详情使用插件级工作池：2 个工作线程、最多 8 个排队任务，满载时明确提示繁忙。每个发送者只允许一个尚未结束的数据库任务；关闭面板、退出或重载会请求取消，任务实际退出前保留名额。独立 Java lookup API 也受最多 2 个并发数据库查询的限制，不得在主线程调用，误用返回 `ASYNC_REQUIRED`。
- 命令查询的时限从提交队列时开始，包含排队、SQL 和组件处理等待。取消采用协作检查与工作线程中断，主线程不会调用可能阻塞的 JDBC 取消方法。连接等待、驱动取消和单件服务器 API 调用不属于可强制抢占的操作，因此超时不是绝对的墙钟耗时保证。
- 普通分页与组件候选批次先查询元数据字节数，再读取 BLOB。单条超过 8 MiB 时不加载其 BLOB，展示为无法还原；组件搜索遇到该记录仍明确失败，不当作不匹配。每批读取及组件查询保留的结果页各限制为 16 MiB，超限提示减少范围或每页条数，避免仅靠记录数量限制内存。
- MySQL/MariaDB JDBC URL 未显式设置 `connectTimeout`、`socketTimeout` 时，默认按查询时限补齐；显式值保持原样。只读查询仍与本体共享数据库资源，异步、有界队列和超时用于控制影响，不保证零卡顿或完全不影响日志写入。

组件查询当前仍为计算总数而扫描候选，翻页、打开面板会重新查询。上述改动没有增加全量结果缓存或改变历史物品的解释规则。线程与故障场景验证见[健壮性验证](docs/ROBUSTNESS_TESTING.md)。

## 配置

默认 `database-type: mysql`，继续使用原有 `database.driver/host/port/database/username/password/extra` 配置，也可以填 `mariadb`。旧配置不必修改。修改配置后执行 `/coq reload`，会验证并重建 Addon 数据库连接。DuckDB 仍要求 CoreProtect 已运行在对应模式；此命令不会重载 CoreProtect，`/co reload` 也不会重载 Addon。

ClickHouse 的键名与 CoreProtect 一致，全部位于配置文件顶层：

```yaml
database-type: clickhouse
table-prefix: co_
clickhouse-host: clickhouse
clickhouse-port: 8123
clickhouse-database: test
clickhouse-username: test
clickhouse-password: '替换为实际密码'
clickhouse-tls: false
```

填写到 `plugins/CoreProtectAddon/config.yml`，不要把 CoreProtect 的整份配置覆盖到 Addon。`clickhouse-host` 只填主机名或 IP；Docker 服务名 `clickhouse` 仅在服务器能解析它时可用。8123 是常见 HTTP 端口，开启 TLS 时填写实际 HTTPS 端口。JAR 已包含隔离包名的 ClickHouse JDBC 0.10.0 驱动，不需要安装额外驱动，也不会复用 CoreProtect 的驱动版本。

要求 ClickHouse 25.6+，以及已经初始化相同数据库和前缀的、具备 ClickHouse 支持的 CoreProtect 构建。仅凭 CoreProtect 的版本号不能确认这一点，应确认运行配置与启动日志。Addon 启动时检查版本和十二个兼容视图，只执行 SELECT，不创建数据库/表，不迁移或清理日志，也不接管 CoreProtect 的写入锁。账号需要对兼容视图及底层数据的适当 SELECT 权限，并允许查询使用超时设置。

MySQL/MariaDB、ClickHouse 模式下，`table-prefix` 留空（默认）时使用 `database.tables` 中的完整表名；非空时统一生成 `<前缀>user/chat/command/item/container/material_map/world/block/entity_map/session/sign/username_log`，覆盖各自表名。切回旧库时设置 `database-type: mysql`，保留旧 `database` 连接设置；如果旧库使用单独配置的表名，将 `table-prefix` 还原成空字符串。切换只改变查询目标，不合并两边的历史记录。SQLite 暂不支持。

当前特殊 CoreProtect 实际使用 DuckDB 时，只需在 **Addon** 配置顶层设置：

```yaml
database-type: duckdb
```

此模式无需设置路径或账号，忽略 `database:`、`clickhouse-*` 和 `table-prefix`，自动使用 CoreProtect 的实际文件和前缀。默认文件为 `plugins/CoreProtect/database.duckdb`。校验规范化后的文件位于 CoreProtect 数据目录内，并与活动连接报告的路径一致；缺失、路径不符、CoreProtect 未启用/未使用 DuckDB、内部接口不兼容均停止启用。Addon 不打开或创建数据库文件，不关闭 CoreProtect 根连接；停止 Addon 只关闭其自己取得的查询连接。支持范围与测试方法见 [DuckDB 安装与测试](docs/DUCKDB_TESTING.md)。

[服务器安装与验收步骤](docs/CLICKHOUSE_TESTING.md) · [ClickHouse 配置片段](examples/clickhouse.yml)

原有表名与查询限制配置示例：

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
    block: co_block
    entities: co_entity_map
    session: co_session
    sign: co_sign
    username: co_username_log
query:
  require-time: true
  max-time-seconds: 604800
  page-size: 15
  max-page-size: 100
  max-results: 1000
  timeout-seconds: 5
  session-ttl-seconds: 300
  max-sessions: 256
  component-max-candidates: 2000
item-panel:
  component-whitelist:
    - minecraft:custom_name
    - minecraft:lore
    - minecraft:potion_contents
    - minecraft:custom_model_data
    - minecraft:custom_data
  component-preview-length: 100
```

白名单控制组件补全、面板点击后的聊天输出以及省略路径的字符串值搜索范围。面板按配置顺序去重、最多取前 12 个，不存在的组件跳过；值搜索使用整个名单。空列表使面板详情仅输出记录信息，并关闭省略路径的值搜索；显式组件条件不受白名单限制。可添加 `minecraft:max_stack_size` 等默认组件。预览长度限制为 20–300，复制不截断；手动修改后 `/coq reload`，debug 按钮修改立即保存生效。

限制同时适用于 lookup 和旧 Java API。`max-time-seconds` 限制区间宽度，不限制记录距今多远，因而 `10d-12d` 可以查询。关闭 `require-time` 后可省略时间条件，此时仍受页大小、可浏览数量和超时限制。旧 `reverse-order` 配置保留，但 lookup 固定倒序。

记录数使用与明细相同的条件，最多检查到 `max-results + 1` 条匹配记录；超过上限显示“仅展示前 N 条”，不称作全库总数。记录上限不等于数据库扫描行数。查询使用 JDBC 超时，并分别使用 MariaDB 的 `SET STATEMENT max_statement_time … FOR SELECT`、MySQL 的 `MAX_EXECUTION_TIME`、ClickHouse 的 `SETTINGS max_execution_time`。DuckDB 使用其 JDBC 驱动的定时中断。共享时间预算覆盖计数、明细及字典查询，不能仅靠 LIMIT 阻止昂贵查询。

ClickHouse 读取 CoreProtect 提供的兼容视图（包括其 `FINAL` 语义），不直接访问底层 `event_data`。聊天/命令的 `content:` 使用参数化的 `match(message, pattern)`，遵循 ClickHouse RE2 规则，默认区分大小写；可用 `(?i)` 忽略大小写，不支持回溯引用和前后查找。MySQL/MariaDB 继续使用原有 REGEXP 及该库排序规则，两边的正则行为不保证完全相同。参考 [CoreProtect 兼容视图源码](https://github.com/PlayPro/CoreProtect/blob/master/src/main/java/net/coreprotect/database/clickhouse/ClickHouseSchema.java)、[ClickHouse JDBC 文档](https://clickhouse.com/docs/integrations/language-clients/java/jdbc)、[match 文档](https://clickhouse.com/docs/sql-reference/functions/string-search-functions#match)。

新 lookup 界面由 `LookupRenderer` 渲染，旧 `messages.yml` 中的聊天/命令分页模板不再用于 lookup。

## Java API 与代码结构

原有六参数 `QueryRequest(QueryType, String, String, Integer, Integer, String)`、`Main.query(QueryRequest)`、`QueryResult` 和 `QueryRecord` 保持签名不变，AstrBot 的 chat/command 反射调用无需修改。新增的统一限制也会约束这些调用，尤其是默认必须传入时间条件。该 API 是同步的，调用者须继续在异步线程使用；主线程调用返回 `ASYNC_REQUIRED`。启动初始化期间 `Main.query` 返回 `NOT_READY`，使用 `Main.getQueryService()` 的调用者须等待服务就绪。

物品通过独立的 `api.lookup.LookupRequest` / `LookupRecord` / `LookupResult` 及 `Main.getQueryService().lookup(request)` 查询，不借用旧 API 的 message 字段。

```text
QueryCommands + LookupParameters + QueryTabCompleter
  -> CoreProtectQueryService（使用 DataManager 选择的数据源）
  -> MariaDB/MySQL SELECT、ClickHouse 兼容视图 SELECT 或 DuckDB 只读事务 SELECT
  -> LookupResult -> LookupRenderer

LookupSessions：每个发送者的固定时间与分页状态
QueryLimits：命令和 Java API 共用限制
```

`HistoricalItemDecoder` 提供统一历史物品还原入口，`ItemPreparationQueue` 为聊天、箱子面板与组件搜索共享主线程预算。`ItemComponents` 使用原生有效组件映射和注册表感知的 NBT codec；`CoreProtectQueryService` 先筛选再计数、分页。

## 构建与验证

1.5.0 的组件搜索、物品还原与临时面板测试、实机验收步骤见 [物品面板验证](docs/ITEM_PANEL_TESTING.md)。

```shell
mvn package
```

产物为 `.asset/CoreProtectAddon-1.6.2.jar`。沿用项目现有的 Paper API 依赖和编译环境；本次本地构建使用 JDK 26。Maven 默认运行无需服务器的解析、补全、会话、动作映射、渲染及限制测试，以及旧配置兼容、ClickHouse JDBC HTTP 协议测试和真实 DuckDB 1.4.5.0 临时数据库测试。DuckDB 测试覆盖只读约束、文件路径、读写共存、生命周期、查询结果和实际超时中断；ClickHouse 测试使用本地 HTTP fixture，不执行真正的 ClickHouse SQL。1.6.2 未完成 Paper 实机验收；验证范围与限制见[健壮性验证](docs/ROBUSTNESS_TESTING.md)。

若 IDE 同时往 `target/classes` 写入编译结果，可用 `mvn -Dcoq.build.directory=target/verify-clickhouse package` 独立构建；这也会保留 `target/config.yml` 等参考文件。打包后可验证隔离环境仅靠 JAR 中的驱动完成连接：

```shell
mvn -Dcoq.build.directory=target/verify-duckdb -Dcoq.test.coreprotectJar=.asset/CoreProtect-24.0-patched.jar package
mvn -Dcoq.build.directory=target/verify-duckdb -Dtest=ClickHouseTest,DuckDBTest -Dcoq.test.pluginJar=.asset/CoreProtectAddon-1.6.2.jar -Dcoq.test.coreprotectJar=.asset/CoreProtect-24.0-patched.jar test
```

只在本机临时实例建立空的 `coq_fixture` 数据库（测试默认 root / 空密码），然后运行：

```shell
mvn -Psql-integration "-Dcoq.test.jdbc=jdbc:mysql://127.0.0.1:34579/coq_fixture?sslMode=DISABLED" test
```

MySQL/MariaDB 集成测试拒绝其他库名和远程主机，使用随机前缀创建、清理测试表。测试数据覆盖全部 item 动作 0–12、普通容器放入/取出、用户和 material 映射、时间边界、正则与包含/排除、固定排序、受限计数和分页、SQL 错误与实际查询超时、旧 Java API 反射契约。MySQL 驱动继续由服务器提供；ClickHouse 驱动随插件打包；DuckDB 仅作为测试依赖，生产复用 CoreProtect 已加载的驱动。

本地已在 MySQL 5.7.24 与 MariaDB 11.4.5 上运行集成测试。游戏内与 `/co l` 的同场景对照、客户端颜色/悬停/点击及真实 AstrBot 网络请求仍需在测试服务器验收；自动化测试不能替代这部分实机检查。
