# Kaleidoscope Tavern — Paper / CraftEngine 重写版

这是 [KaleidoscopeTavern](https://github.com/KaleidoscopeMods/KaleidoscopeTavern) 的 Paper 服务端重写版。插件保留原模组的葡萄种植、酿造、酒馆家具、饮品与特殊酒效，但运行时不需要 Forge。

## 主要内容

- 葡萄种植、野生葡萄藤与棚架玩法
- 压榨、酒桶发酵、调酒器与龙头灌装
- 可饮用、可摆放和可堆叠的酒瓶与杯具
- 桌椅、沙发、吧台、酒柜、灯具、告示板等酒馆装饰
- 自定义饮品效果及可选 HUD
- 可编辑的酒桶和鸡尾酒配方

## 运行要求

| 组件 | 支持版本 |
| --- | --- |
| Minecraft / Paper | **26.2 only** |
| Java | **25** |
| CraftEngine | **26.7.4** |
| CustomCrops | **3.6.52** |

PlaceholderAPI 与 CustomNameplates 仅在需要外部酒效 HUD 时安装。

## 安装

1. 使用 Java 25 启动 Paper 26.2 服务端。
2. 安装 CraftEngine 26.7.4 与 CustomCrops 3.6.52。
3. 将 `KaleidoscopeTavern-Paper-2.0.0-paper26.2.jar` 放入 `plugins/`。
4. 启动服务端。插件会自动安装 CraftEngine 内容与 CustomCrops 葡萄配置。
5. 按 CraftEngine 的方式部署生成的资源包，让客户端加载。
6. 执行 `/kt status` 检查内容是否正常加载。

插件只管理自己安装的文件，不会清理其他 CraftEngine 项目。若关闭 `pack.install-on-startup`，需要自行将 JAR 内的 `tavern-pack/` 安装到 CraftEngine 资源目录。

## 基本玩法

- 正常使用饮品会直接饮用；潜行使用时会将其摆放为家具。
- 压榨桶可放入原料并通过踩踏榨汁，装满后可用桶取出。
- 酒桶会根据配方和时间完成发酵，支持离线计时。
- 调酒器装入配方原料后可取回手中摇制鸡尾酒。
- 龙头可从水源、部分方块或酒桶取液，并向下方容器灌装。
- CustomCrops 负责葡萄的成长、骨粉、收获、掉落与持久化。

## 自定义配方

首次启动会生成：

- `plugins/KaleidoscopeTavern/recipes/barrel.yml`
- `plugins/KaleidoscopeTavern/recipes/shaker.yml`

修改后执行 `/kt reload`。无效配置不会替换当前生效的配方，具体错误会写入服务器日志。使用 `/kt recipes <barrel|pressing|shaker>` 可查看当前配置。

## 酒效 HUD

插件默认通过 BossBar 显示持续型酒效：

- `effect-hud.style: corner`：在右上角显示图标。
- `effect-hud.style: line`：在屏幕顶部显示图标、名称与倒计时。
- `effect-hud.mode: auto`：检测到 CustomNameplates 时自动停用内置 HUD。

使用 PlaceholderAPI 与 CustomNameplates 时，可将 JAR 内 `customnameplates/bossbar-tavern-effects.yml` 的 `tavern_effects` 配置合并到 `plugins/CustomNameplates/configs/bossbar.yml`。

可用占位符：

- `%kaleidoscopetavern_effect_hud%`
- `%kaleidoscopetavern_effect_count%`

注意：内置 `corner` 样式会将黄色 BossBar 贴图设为透明，因此其他黄色 BossBar 也可能不可见。

## 命令

- `/kt status`：检查插件内容加载状态。
- `/kt give <物品ID> [数量] [玩家]`：发放物品。
- `/kt recipes <barrel|pressing|shaker>`：查看当前酒类配方。
- `/kt reload`：重载插件配置、配方及相关内容。
- 权限：`kaleidoscopetavern.admin`，默认仅 OP 拥有。

## 构建

Windows：

```powershell
.\gradlew.bat clean build
```

Linux / CI：

```bash
./gradlew clean build
```

成品位于 `build/libs/`。需要重新生成迁移内容时运行：

```bash
./gradlew migrateLegacyContent validatePack
```

`src/paper/pack/configuration/*.json` 与 `src/paper/resources/catalog/*.tsv` 是生成文件，请修改迁移脚本或迁移输入后重新生成，不要直接编辑。

## 存档迁移

旧 Forge 世界中的已放置设备无法原地转换为 CraftEngine 对象；旧版 CraftEngine 中已放置的沙发、桌子、吧台、吧台柜和压榨桶也不会自动迁移。

正式服升级前请备份世界，并先在测试服检查关键设备、配方与资源包。

## 许可证与致谢

- 代码：[BSD 3-Clause](LICENSE-CODE)
- 美术资源：[CC BY-NC-SA 4.0](LICENSE-ASSETS)
- 素材来源与修改说明：[ASSET-CREDITS.md](ASSET-CREDITS.md)
- 第三方组件：[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)

本项目源自 [KaleidoscopeTavern](https://github.com/KaleidoscopeMods/KaleidoscopeTavern)，并依赖 [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) 与 [CustomCrops](https://github.com/Xiao-MoMi/Custom-Crops) 运行。
