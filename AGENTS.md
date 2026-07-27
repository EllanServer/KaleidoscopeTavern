# AGENTS.md

## LANGUAGE POLICY

- 默认使用简体中文回答。
- 除非明确要求英文，否则不要切换英文叙述。
- 代码、命令、报错、API 名称保持原文。

## 当前项目事实

- 运行目标仅为 Minecraft / Paper `26.2`，使用 Java 25、CustomCrops `3.6.52`，临时同时支持 CraftEngine
  `26.7.4` 与上游 `dev`（当前为 `26.8-SNAPSHOT`）。
- 这是服务端插件重写，不再编译或加载 Forge。Paper 主源码位于 `src/paper/java`，插件资源位于
  `src/paper/resources`，CraftEngine 项目位于 `src/paper/pack`，测试位于 `src/paperTest/java`。
- 入口类是
  `com.github.ysbbbbbb.kaleidoscopetavern.paper.KaleidoscopeTavernPlugin`；插件声明位于
  `src/paper/resources/plugin.yml`。
- `src/main/java`、`src/main/resources` 与 `src/generated/resources` 中的旧 Forge 内容继续保留，作为可审计的
  迁移输入和原始美术资源，但不属于 Gradle Java source set。

## 内容建模原则

- 遵循 CraftEngine Wiki 对物品、方块和家具的区分。只有需要网格状态、随机刻、存活规则或世界生成的
  55 个公开/结构内容使用 CE 自定义方块；另有 15 个私有葡萄阶段方块供 CustomCrops 切换，总计 70 个
  CE 方块 id。设备、酒瓶、灯具和展示柜等 104 个内容使用 CE 家具。十六色沙发、八种熏香、水龙头、
  酒窖柜、倾斜酒架、圆形酒架与酒瓶托架属于 CE 方块，由方块状态或方块实体保存连接、开关、储物与红石状态。
- 饮品、果汁桶、葡萄、雪克杯等始终保留物品态。可摆放饮品仅在潜行时由 Paper 层生成家具，正常使用仍
  执行饮用；雪克杯在物品态与家具态之间迁移其原料和产物数据。
- 家具业务状态使用家具元实体 PDC；CE 方块业务状态使用方块实体 NBT。基础碰撞箱、座位、发光、储物和
  展示槽优先使用 CraftEngine 原生行为。
- CraftEngine 的公开 API、Bukkit/Proxy 内部实现、展示实体元数据、家具快照与服务端方块状态都属于兼容面。
  GitHub CI 必须同时通过 `26.7.4` 和直接从 `Xiao-MoMi/craft-engine:dev` 构建的版本；升级前仍须服务器实测。
- **临时兼容清理提醒：**CraftEngine `26.8`（或承载当前 `dev` API 的后续稳定版）正式发布后，删除
  `26.7.4` 兼容和对应 CI 线路，把默认依赖提升到该稳定版，并重新做完整内容加载与服务器交互验收。
- CustomCrops 负责悬挂葡萄的成长刻、阶段、骨粉、交互、掉落和持久化；Tavern 只保留棚架连线、藤蔓
  传播与悬挂存活规则。托管配置源位于 `src/paper/customcrops`，不要在 Java 中重复这些作物机制。

## 常用命令

- 完整构建：Windows 使用 `.\gradlew.bat clean build`，Unix/CI 使用 `./gradlew clean build`。
- 只编译：`.\gradlew.bat compileJava`。
- 重新迁移：`.\gradlew.bat migrateLegacyContent`。
- 单独校验 CE 配置：`.\gradlew.bat validatePack` 或 `python tools/validate_pack.py`。
- 迁移与校验会生成/检查 157 个公共物品、70 个方块 id、104 个家具、506 个私有渲染物品、114 个配方
  和 3 个 CustomCrops 作物。

## 生成文件

- `tools/migrate_legacy.py` 从保留的 Forge 注册表、数据生成结果和资源模型确定性地产生
  `src/paper/pack/configuration/*.json` 与 `src/paper/resources/catalog/*.tsv`。
- 不要直接修改生成的 JSON/TSV；应修改迁移脚本或旧迁移输入，再重新生成并检查差异。
- `tools/validate_pack.py` 会检查对象分类、资源引用、家具变体、配方、CustomCrops 阶段映射、饮品物品
  语义、酒桶占位和玩法目录。

## 验证注意事项

- `build` 已依赖 `validatePack`，并运行 JUnit 目录语义测试。
- Windows 下若 JUnit worker 因中文绝对路径无法加载类，可把仓库临时映射到空闲盘符后构建；构建结束后
  必须删除该临时映射。
- 正式服升级前需备份世界。Forge 方块实体 NBT 与 CraftEngine 家具/PDC 不同，旧世界中已放置的设备
  不会自动原地转换。
