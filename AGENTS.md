# AGENTS.md

## LANGUAGE POLICY

- 默认使用简体中文回答。
- 除非明确要求英文，否则不要切换英文叙述。
- 代码、命令、报错、API 名称保持原文。

## 当前项目事实

- 运行目标仅为 Minecraft / Paper `26.2`，使用 Java 25、CraftEngine `26.7.4` 与 CustomCrops `3.6.52`。
- 这是服务端插件重写，不再编译或加载 Forge。Paper 主源码位于 `src/paper/java`，插件资源位于
  `src/paper/resources`，CraftEngine 项目位于 `src/paper/pack`，测试位于 `src/paperTest/java`。
- 入口类是
  `com.github.ysbbbbbb.kaleidoscopetavern.paper.KaleidoscopeTavernPlugin`；插件声明位于
  `src/paper/resources/plugin.yml`。
- `src/main/java`、`src/main/resources` 与 `src/generated/resources` 中的旧 Forge 内容继续保留，作为可审计的
  迁移输入和原始美术资源，但不属于 Gradle Java source set。

## 内容建模原则

- 遵循 CraftEngine Wiki 对物品、方块和家具的区分。只有需要网格状态、随机刻、存活规则或世界生成的
  34 个公开/结构内容使用 CE 自定义方块；另有 15 个私有葡萄阶段方块供 CustomCrops 切换，总计 49 个
  CE 方块 id。桌椅、设备、酒瓶、灯具和展示柜等 125 个内容使用 CE 家具。八种熏香属于 CE 方块，
  使用已释放的铜灯笼状态提供客户端碰撞，并由自定义方块行为保存开关/红石状态。
- 饮品、果汁桶、葡萄、雪克杯等始终保留物品态。可摆放饮品仅在潜行时由 Paper 层生成家具，正常使用仍
  执行饮用；雪克杯在物品态与家具态之间迁移其原料和产物数据。
- 业务状态使用家具元实体 PDC；基础碰撞箱、座位、发光、储物和展示槽优先使用 CraftEngine 原生行为。
- CraftEngine API 以 `26.7.4` 固定版本为准。展示槽清空/发射桥接和自定义方块行为会接触该固定版本的
  非稳定实现，升级 CraftEngine 前必须重新编译并做服务器实测。
- CustomCrops 负责悬挂葡萄的成长刻、阶段、骨粉、交互、掉落和持久化；Tavern 只保留棚架连线、藤蔓
  传播与悬挂存活规则。托管配置源位于 `src/paper/customcrops`，不要在 Java 中重复这些作物机制。

## 常用命令

- 完整构建：Windows 使用 `.\gradlew.bat clean build`，Unix/CI 使用 `./gradlew clean build`。
- 只编译：`.\gradlew.bat compileJava`。
- 重新迁移：`.\gradlew.bat migrateLegacyContent`。
- 单独校验 CE 配置：`.\gradlew.bat validatePack` 或 `python tools/validate_pack.py`。
- 迁移与校验会生成/检查 157 个公共物品、49 个方块 id、125 个家具、554 个私有渲染物品、114 个配方
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
