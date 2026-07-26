# Kaleidoscope Tavern — Paper / CraftEngine 重写版

这是 [KaleidoscopeTavern](https://github.com/KaleidoscopeMods/KaleidoscopeTavern) 的 Paper 服务端重写。运行时不再加载 Forge 代码；原 Forge 源码与数据生成器保留在仓库中，仅作为可审计的迁移输入。

## 兼容范围

| 组件 | 支持版本 |
| --- | --- |
| Minecraft / Paper | **26.2 only** |
| Java | **25** |
| CraftEngine | **26.7.4** |
| CustomCrops | **3.6.52** |

本项目以 CraftEngine 官方 Wiki 的 [自定义方块](https://xiao-momi.github.io/craft-engine-wiki/configuration/block/)、[家具](https://xiao-momi.github.io/craft-engine-wiki/configuration/furniture/) 和 [API](https://xiao-momi.github.io/craft-engine-wiki/api/) 模型为准，并没有把所有旧 Forge `Block` 机械地迁成 CE 方块。

## 内容映射

| CraftEngine 对象 | 数量 | 内容 |
| --- | ---: | --- |
| 自定义物品 | 157 | 葡萄、酒饮、容器、装饰与所有可获取内容 |
| 自定义方块 | 41 | 26 个公开/结构方块，加上 15 个只供 CustomCrops 切换的葡萄阶段方块 |
| 家具 | 133 | 桌椅、沙发、吧台、灯具、香炉、画、告示板、酒桶、压榨桶、调酒器、龙头、酒瓶与杯具等 |
| 私有渲染物品 | 554 | 仅供 CE 模型、家具槽位、酒桶部件/液面、压榨液面及高脚凳/调酒器动态部件显示使用，不作为公共内容发放 |
| 原版工作台配方 | 114 | 有序、无序与切石配方 |

家具继续使用 CraftEngine 的碰撞箱、座位、储物、展示槽、发光和 placement rule。需要业务状态的家具由 Paper 插件补充持久化逻辑：

- 物品态：葡萄仍是可食用物，果汁桶仍可饮用、清除状态并返还桶；藤条保留燃料值，瓶装酒、杯具与燃烧瓶保留 16 个堆叠上限；
- 压榨桶：地面型可放入原料并踩踏压榨，壁挂倾倒型不可踩踏；不匹配的原料会被挤出，满 1000 mB 后可用桶取出；
- 酒桶：保留 3×3×3 家具占位，支持 4 桶基液、至多 4 种原料及离线计时的 6 档陈酿品质；
- 调酒器：摆放后装入三份原料，可原地计时摇制，也可潜行空手取回并在手中摇制；原料与成品随物品保存，签名鸡尾酒会合并效果与颜色；
- 龙头：读取后方水/岩浆/蜂巢/龙首/西瓜或邻近 CE 酒桶，并向下方炼药锅或 CE 空瓶家具灌装；
- 酒瓶：饮品正常右键仍会饮用，潜行时才摆成家具；原版药水、水瓶、蜂蜜瓶、龙息与经验瓶也可摆放，药水颜色及完整 `ItemStack` 数据会保留，同类瓶可叠放四层并被投射物击碎；
- 酒架与酒柜：沿用 CE 的可见展示槽，限制瓶装酒/杯具类型；部分架柜收到红石上升沿后会随机发射其中一瓶；
- 告示板：聊天编辑、多行文本、颜色、对齐、荧光、上蜡，以及三块黑板自动合并；
- 沙发、吧台、桌子与柜体：相邻家具自动切换直线、端部和转角模型；
- 香炉：手动与红石边沿开关、粒子和亡灵驱散效果；
- 饮品效果：迁移 37 种饮品、270 条分级效果，包括 12 种原模组自定义效果；
- 世界生成：只在新生成的主世界区块中，将真实 CE 野生葡萄藤挂到橡树/白桦树冠下。
- 葡萄作物：CustomCrops 负责三类悬挂葡萄的 6 个阶段、随机刻、骨粉、交互收获、掉落和世界持久化；本插件只保留棚架连线、藤蔓传播及成熟藤架下悬挂存活规则。

## 安装

1. 使用 Java 25 启动 Paper 26.2 服务端。
2. 安装 CraftEngine 26.7.4。
3. 安装 CustomCrops 3.6.52。
4. 将 `KaleidoscopeTavern-Paper-2.0.0-paper26.2.jar` 放入 `plugins/`。
5. 启动服务端。插件会在依赖启用前，把内置 CE 项目同步到 `plugins/CraftEngine/resources/kaleidoscope_tavern/`，并把葡萄定义同步到 `plugins/CustomCrops/contents/crops/kaleidoscope_tavern.yml`。
6. 按 CraftEngine 的资源包部署方式让客户端加载生成的资源包，并执行 `/kt status` 检查内容数量。

本插件不再嵌入 Sparrow-Heart，也不再保留仅用于调试高亮的代码。CustomCrops 自身如何管理其运行库由 CustomCrops 负责，Tavern JAR 不会再打入一份重复副本。

插件只删除自己清单中记录的过期文件，不会清理 CraftEngine 的其他项目。若关闭 `pack.install-on-startup`，则需要自行把 JAR 内的 `tavern-pack/` 放入 CraftEngine 资源目录。

### 酒效 HUD（可选：CustomNameplates）

纯原版客户端无法注册自定义状态效果，插件通过隐形 BossBar 文本渲染持续型酒效。默认 `effect-hud.style: corner` 会把效果图标排到**原版药水 HUD 的位置**：右上角 24x24 原版效果框、增益第一行、其余第二行、从右向左每 25px 一格，与原版视觉一致（原版 HUD 同样不显示文字）。`style: line` 则退回顶部居中一行（图标 + 名称 + 等级 + 倒计时）。

corner 排版基于零和偏移：BossBar 文本以屏幕中心为锚，按 `effect-hud.gui-half-width`（默认 240，对应 1920x1080 + GUI 比例 4）推算右上角坐标。其他分辨率/GUI 比例的玩家图标仍在顶行，只是水平位置偏移；若服上另有 BossBar（末影龙、其他插件），整行会随原版堆叠规则下移一格。corner 风格占用 YELLOW BossBar 颜色（tavern 资源包把 `boss_bar/yellow_*` 覆盖为全透明），服务器上其他黄色 BossBar 也会因此隐形。

如果服务端装有 PlaceholderAPI 与 CustomNameplates，可以把显示层交给 CustomNameplates：

1. 安装 PlaceholderAPI 与 CustomNameplates。
2. 把 JAR 内 `customnameplates/bossbar-tavern-effects.yml` 中的 `tavern_effects` 一节合并进 `plugins/CustomNameplates/configs/bossbar.yml`，然后 `/nameplates reload`（`color` 保持 `YELLOW` 以隐藏条本体）。
3. 本插件 `config.yml` 的 `effect-hud.mode` 默认为 `auto`：检测到 CustomNameplates 时自动停用内置 BossBar，避免重复显示；也可强制 `builtin` / `external`。

占位符：`%kaleidoscopetavern_effect_hud%`（完整 MiniMessage 行，随 `style` 输出 corner 或 line 排版，与内置 BossBar 内容一致）、`%kaleidoscopetavern_effect_count%`（酒效数量，用于隐藏条件）。字形来自 CraftEngine 分发的 `kaleidoscope_tavern:custom_effects`（line）与 `kaleidoscope_tavern:custom_effects_hud`（corner）位图字体，无需在 CustomNameplates 中另行注册 image。

### 命令

- `/kt status`：显示 CE 物品、方块、家具和玩法目录的加载数量；
- `/kt give <物品ID> [数量] [玩家]`：发放公共 CE 物品；
- `/kt reload`：重载本插件配置，并重载 CraftEngine 与 CustomCrops 内容；
- 权限：`kaleidoscopetavern.admin`（默认仅 OP）。

## 构建与校验

```bash
./gradlew clean build
```

成品位于 `build/libs/`。`build` 会同时执行：

- Java 25 编译与 JUnit 配方语义测试；
- `tools/validate_pack.py`，校验 CE 引用、模型、变体、配方、TSV 目录及 CustomCrops 阶段映射；
- `tools/verify_plugin_jar.py`，确认成品同时内置 CE 项目、资源包和托管作物配置，且没有误嵌入 CustomCrops/Sparrow-Heart 类；
- 打包完整 CraftEngine 项目、资源包与 CustomCrops 葡萄定义。

需要从保留的 Forge 数据生成器重新生成全部 CE 配置时：

```bash
./gradlew migrateLegacyContent validatePack
```

生成结果及数量清单位于 `src/paper/pack/configuration/migration-report.json`。不要直接修改生成的 JSON/TSV；应修改 `tools/migrate_legacy.py` 或迁移输入后重新生成。

## CustomCrops 职责边界

本项目按 CustomCrops 官方 Wiki 的 [Crop](https://mo-mi.gitbook.io/xiaomomi-plugins/customcrops/plugin-wiki/customcrops/format/crop)、[CraftEngine/其他方块系统接入](https://mo-mi.gitbook.io/xiaomomi-plugins/customcrops/plugin-wiki/customcrops/api/other-block-system) 与 [Random tick / Scheduled tick](https://mo-mi.gitbook.io/xiaomomi-plugins/customcrops/plugin-wiki/customcrops/random-tick-vs-scheduled-tick) 模型接入。CustomCrops 已有的成长、骨粉、收获、掉落和持久化不在 Tavern 中重复实现。

CustomCrops 也有 [Pot](https://mo-mi.gitbook.io/xiaomomi-plugins/customcrops/plugin-wiki/customcrops/format/pot)、[Watering Can](https://mo-mi.gitbook.io/xiaomomi-plugins/customcrops/plugin-wiki/customcrops/format/watering-can) 与 [Sprinkler](https://mo-mi.gitbook.io/xiaomomi-plugins/customcrops/plugin-wiki/customcrops/format/sprinkler) 的水分系统，但它表示的是作物灌溉水单位，并不是任意流体容器。果汁/酒液类型、1000 mB 压榨容量、酒桶发酵、龙头输送和瓶桶灌装仍属于 Tavern 酿造玩法，不与 CustomCrops 重复。

## TheBrewingProject

[TheBrewingProject](https://github.com/BreweryTeam/TheBrewingProject) 可以在未来作为可选配方/酿造桥接层，因为它能识别 CraftEngine 物品；本重写不把它设为依赖。它不能替代压榨桶、家具酒桶、调酒器、可放置酒瓶、告示板及自定义饮用效果，而且其当前公开兼容范围尚未覆盖这里锁定的 Paper 26.2 / CraftEngine 26.7.4 组合。

## 数据迁移说明

Forge 方块实体 NBT 与 CraftEngine 家具实体/PDC 的表示不同，因此旧 Forge 世界中的已放置设备不会原地转换。物品、模型、配方和玩法规则已经迁移；正式服升级前仍应备份世界，并在测试服重新放置关键设备完成验收。

代码使用 [BSD 3-Clause](LICENSE-CODE)，美术资源使用 [CC BY-NC-SA 4.0](LICENSE-ASSETS)。
