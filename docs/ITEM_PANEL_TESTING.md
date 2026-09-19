# COQ 1.5.0 组件搜索、物品与一次性面板验证

## 自动测试

```powershell
mvn '-Dcoq.build.directory=target/verify-content' '-Dcoq.test.coreprotectJar=.asset/CoreProtect-24.0-patched.jar' package
# 已构建 JAR 后可额外验证 shaded 驱动隔离：
mvn '-Dcoq.build.directory=target/verify-content' '-Dcoq.test.coreprotectJar=.asset/CoreProtect-24.0-patched.jar' '-Dcoq.test.pluginJar=.asset/CoreProtectAddon-1.5.0.jar' test
```

本次结果：40 项，39 项通过，1 项因未配置外部 MySQL/MariaDB 测试库而跳过。真实 DuckDB 测试全部通过；ClickHouse 使用真实 JDBC 驱动和本地 HTTP fixture。新增验证覆盖嵌套 SNBT 命令解析、白名单配置往返、tppos、候选批次游标、筛选前分页错误回归、匹配总数与结果上限、扫描上限、失败不退化为零结果、container 元数据；保留默认物品查询、悬停、会话和聊天查询回归。

## 临时 Paper 实例

`src/test/runtime/ItemHoverProbe.java`、`PanelProbe.java` 与 `ContentProbe.java` 是独立测试插件，**测试完成后会关闭服务器，绝不能放到生产服务器**。使用已有许可与缓存的临时 Paper 实例运行，避免操作现有世界和数据库。测试插件是在发布 JAR 的副本中加入这些类，并用以下 `plugin.yml` 替换其描述符；发布 JAR 本身不包含测试入口。

```yaml
name: COQItemProbe
version: 1
main: coqtest.ItemHoverProbe
api-version: '1.21'
depend: [CoreProtect]
```

构建测试类可使用 Maven 依赖 classpath：

```powershell
mvn dependency:build-classpath '-Dmdep.outputFile=target/probe-classpath.txt'
$probeClasspath = 'target/verify-content/classes;' + (Get-Content target/probe-classpath.txt -Raw).Trim()
javac -encoding UTF-8 -source 17 -target 17 -cp $probeClasspath -d target/item-probe-classes src/test/runtime/ItemHoverProbe.java src/test/runtime/PanelProbe.java src/test/runtime/ContentProbe.java
# 将上述 plugin.yml 保存为 target/item-probe-classes/plugin.yml。
# probe-server 必须是专用临时服务器，目录中不能有生产数据。
Copy-Item .asset/CoreProtectAddon-1.5.0.jar probe-server/plugins/COQItemProbe.jar
jar uf probe-server/plugins/COQItemProbe.jar -C target/item-probe-classes .
```

临时服务器另需 CoreProtect 24.0；无需启用第二份 Addon。测试插件仅使用 CoreProtect 的物品序列化，数据库读取已有独立测试覆盖。面板测试使用真实 Paper 物品、Inventory、事件及调度器，查询结果与查看玩家用 fixture 提供，验证异步查询、物品还原预算、各类点击取消、组件输出、下一页、与聊天会话同步、关闭清理和关闭后异步结果丢弃。

本次实际环境为 Paper 1.21.8-60、Java 21、用户提供的 CoreProtect 24.0 patched 构建。结果写入临时服务器根目录的 `coq-item-probe.txt`、`coq-panel-probe.txt` 和 `coq-content-probe.txt`。物品还原比较名称、Lore、药水效果、颜色、自定义持久化数据、数量及原生悬停对象。组件测试逐个复制、解析和匹配测试药水全部 15 个有效组件（含默认值），验证复合对象顺序、NBT 数字类型、缺失组件、完整剪贴板、截短预览、白名单顺序、空白名单、异步筛选队列、格式错误、损坏元数据和超时。客户端实际显示仍需连接游戏确认，这项测试没有模拟真实客户端渲染。

## 游戏内验收

2026-09-19 最终构建的三份隔离服报告均为 PASS；发布包不含测试入口。配置示例见 `examples/item-components.yml`。

1. 替换 Addon JAR 后重启服务器。
2. 使用带自定义名称、Lore 与效果的药水产生拾取、丢弃和容器记录。
3. 执行 `/coq lookup user:Loliiiico include:potion time:1h`，核对物品名称悬停与时间/坐标悬停。
4. 执行 `/coq items`，核对每一行对应一个格子，以及下一页/上一页。
5. 非创造模式点击药水，或创造模式右键药水，聊天只显示白名单组件与简短记录信息。点击某个组件行，粘贴到新查询的 `content:` 后，应查询到相同组件值的记录；尝试把 `minecraft:max_stack_size` 加入白名单复制默认值，验证默认组件也能匹配。
6. 在还原或翻页期间关闭面板，确认不会自动重新打开；重新执行命令应创建新面板。
7. 创造模式左键领取物品，确认数量不超过当前显示的一组且组件完整；背包满时提示，无法还原的占位物品不可领取。切换生存 / 冒险 / 旁观模式应只能查看。分别尝试 Shift、快捷栏数字键、双击、丢弃和拖拽，确认不能移动物品或导航按钮。
8. 每页超过 45 条时应明确拒绝；使用 `/coq items 1:45` 后才能打开，不应静默丢失当前页的部分记录。
9. 点击坐标按钮，应执行 `/tppos x y z`；传送插件不存在或权限不足时，结果由服务器处理，不绕过权限。
10. 验证长 Lore 的聊天预览虽被省略，复制仍为完整条件；白名单外组件仍可手写搜索；超过扫描上限或时间预算时须明确报未完成，不能显示假零结果。

## 创造模式与重载补充验收

- `/coq` 显示简洁首页；`/coq help 1`、`2`、`3` 可相互翻页，错误页码提示用法。
- 无 reload 权限者不能重载，补全与帮助不显示管理入口；控制台可重载。
- 修改面板白名单、查询页大小和会话时限，执行 `/coq reload`，确认新配置生效、旧面板关闭、旧分页会话失效。
- 查询进行中重载，旧结果不能重新显示；连续重载后每次点击只发放一次物品。
- 修改数据库连接为错误目标或将会话时限设为 0，重载应失败且原配置与查询连接继续可用；磁盘上的编辑保留供修正。
