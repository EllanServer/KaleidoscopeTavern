# AGENTS.md

## LANGUAGE POLICY

- 默认使用简体中文回答。
- 除非明确要求英文，否则不要切换英文叙述。
- 代码、命令、报错、API 名称保持原文。

## 当前项目事实

- 运行目标仅为 Minecraft / Paper `26.2`，使用 Java 25、CraftEngine `26.8-SNAPSHOT` 与 CustomCrops `3.6.52`。
  CraftEngine 26.8 目前只在 `https://repo.momirealms.net/snapshots/` 发布，`releases` 仓库最新仍为 `26.7.4`。
- 这是服务端插件重写，不再编译或加载 Forge。Paper 主源码位于 `src/paper/java`，插件资源位于
  `src/paper/resources`，CraftEngine 项目位于 `src/paper/pack`，测试位于 `src/paperTest/java`。
- 入口类是
  `com.github.ysbbbbbb.kaleidoscopetavern.paper.KaleidoscopeTavernPlugin`；插件声明位于
  `src/paper/resources/plugin.yml`。
- `src/main/java`、`src/main/resources` 与 `src/generated/resources` 中的旧 Forge 内容继续保留，作为可审计的
  迁移输入和原始美术资源，但不属于 Gradle Java source set。

## 内容建模原则

- 遵循 CraftEngine Wiki 对物品、方块和家具的区分。需要真实网格状态、邻居更新、随机刻、存活规则或
  世界生成的内容使用 CE 自定义方块；静态陈设和实体展示设备使用 CE 家具。当前生成 44 个 CE 方块 id，
  其中 15 个是供 CustomCrops 切换的私有葡萄阶段方块。
- 十六色沙发共用一个私有 `kaleidoscope_tavern:_internal/sofa` CE 方块：16 个公共物品通过 `dyed_color`
  与 CE 原生 `tint_source_block` 保存颜色和精确掉落，活动状态仅为 6 种连接 × 4 个朝向（24 状态）。旧 16 个
  方块 id 及其迁移别名已移除；Java 只负责 CE 无法表达的六状态拓扑。
- 桌子、吧台与两种吧台柜本来就是每个家族一个活动 CE 方块，没有颜色维度可再合并。`block_item`、hard-coded
  `facing`/`axis`、含水、carrier、碰撞、模型、掉落和座位均由 CE 原生行为或生成配置负责；Java 只保留桌子
  世界坐标轴连接，以及双展示槽、异形酒瓶和精确展示变换等原生接口无法完整表达的源玩法语义。
- 旧沙发、桌子、吧台、两种吧台柜和压榨桶家具定义及其运行时迁移器已移除；旧 CE 存档中的这些对象不再自动转换。
- 饮品、果汁桶、葡萄、雪克杯等始终保留物品态。可摆放饮品仅在潜行时由 Paper 层生成家具，正常使用仍
  执行饮用；雪克杯在物品态与家具态之间迁移其原料和产物数据。
- 家具业务状态使用家具元实体数据；CE 方块业务状态使用方块实体 NBT。静态碰撞、座位、放置、朝向、含水、
  发光和掉落优先使用 CraftEngine 原生行为或配置；只有原生展示槽无法表达的多槽点击路由、过滤与变换才保留 Java 方块实体。
- CraftEngine API 以 `26.8-SNAPSHOT` 固定版本为准。动态展示差量、自定义方块实体和源玩法特有的邻居拓扑会
  接触该固定版本的非稳定实现，升级 CraftEngine 前必须重新编译并做服务器实测。
- 26.8 相对 26.7.4 唯一的破坏性变更是 `FurnitureController.onUnload(boolean isStopping)` 改为 `onUnload()`；
  CE 不再区分"关服卸载"与"区块卸载"，因此 `LifecycleFurnitureBehavior.Handler#onUnavailable` 与
  `TickingFurnitureBehavior.Handler#onUnload` 也去掉了对应的 `stopping` 形参（原本无任何消费者使用）。
- 26.8 新增的 JS 脚本默认关闭，内置只绑定日志、调度和事件上下文，不直接暴露 Tavern 服务。Nashorn 与
  GraalJS 均可通过宿主 Java 互操作强行访问 Bukkit、插件类及 CE 非稳定实现，但这只是把相同的 Java/NMS
  依赖迁到无类型脚本；`@Subscribe` 仍会注册 Bukkit 全局监听，并额外增加脚本调用。CE 配置事件内的 `js`
  function/condition 只用于对应对象的事件，现有可由这类无状态事件表达的逻辑已经直接使用 CE 原生函数，
  无需再套脚本。`BiomeCondition` 只做群系 id 集合匹配而非 `getBaseTemperature()` 连续值，loot source 面向
  原版掉落场景注入而非自定义容器内容还原。评估后不把剩余 Java 机械改写为 JS。
- CustomCrops 负责悬挂葡萄的成长刻、阶段、骨粉、交互、掉落和持久化；Tavern 只保留棚架连线、藤蔓
  传播与悬挂存活规则。托管配置源位于 `src/paper/customcrops`，不要在 Java 中重复这些作物机制。

## 常用命令

- 完整构建：Windows 使用 `.\gradlew.bat clean build`，Unix/CI 使用 `./gradlew clean build`。
- 只编译：`.\gradlew.bat compileJava`。
- 重新迁移：`.\gradlew.bat migrateLegacyContent`。
- 单独校验 CE 配置：`.\gradlew.bat validatePack` 或 `python tools/validate_pack.py`。
- 校验 CE 服务端状态预算：`.\gradlew.bat validateServerStateBudget` 或
  `python tools/check_server_state_budget.py --capacity 2000 --reserve 1000`。
- 迁移与校验会生成/检查 157 个公共物品、44 个方块 id、116 个家具定义、413 个私有渲染物品、114 个配方
  和 3 个 CustomCrops 作物。

## 生成文件

- `tools/migrate_legacy.py` 从保留的 Forge 注册表、数据生成结果和资源模型确定性地产生
  `src/paper/pack/configuration/*.json` 与 `src/paper/resources/catalog/*.tsv`。
- 不要直接修改生成的 JSON/TSV；应修改迁移脚本或旧迁移输入，再重新生成并检查差异。
- `tools/validate_pack.py` 会检查对象分类、资源引用、家具变体、配方、CustomCrops 阶段映射、饮品物品
  语义、酒桶占位和玩法目录。

## 验证注意事项

- `build` 已依赖 `validatePack`、`validateServerStateBudget`，并运行 JUnit 目录语义测试。
- Windows 下若 JUnit worker 因中文绝对路径无法加载类，可把仓库临时映射到空闲盘符后构建；构建结束后
  必须删除该临时映射。
- 正式服升级前需备份世界。Forge 方块实体 NBT 与 CraftEngine 家具/PDC 不同，旧世界及旧 CE 版本中已放置的对象不会自动转换；上线前必须在测试服完成验收。
