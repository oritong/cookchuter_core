# 厨升核心

**Cookchuter Core**

Minecraft 1.21.1 的 NeoForge 核心模组项目，使用 Java 21。

| 项目 | 值 |
| --- | --- |
| 中文名称 | 厨升核心 |
| 英文名称 | Cookchuter Core |
| Mod ID | `cookchuter_core` |
| Minecraft | `1.21.1` |
| NeoForge | `21.1.265` |
| Java | `21` |
| 模组版本 | `0.1.0-hotfix2` |
| 作者 | [oritong](https://github.com/oritong)、[water2H2O](https://github.com/water2H2O) |
| 许可证 | GPL-3.0-only |

## 变更记录

### 0.1.0-hotfix2

- 新生成的鸡会随机获得 12–18 点最大生命值。
- 提高鸡接近玩家的速度；受伤后逃跑 6 秒，受伤后满 15 秒才开始回血。
- 降低逃跑速度并缩短逃跑寻路距离。
- 鸡会忽略创造模式和旁观模式玩家，也不会因他们造成的伤害进入逃跑状态。
- 将本地 `AGENTS.md` 加入 `.gitignore`，不再发布本地工作指引。

### 0.1.0-hotfix1

- 原版鸡会缓慢接近玩家，并每秒造成 1 点近战伤害。
- 鸡受伤后会高速逃跑 15 秒，随后每秒恢复最大生命值的 5%，满血后恢复接近玩家的行为。
- 项目改用 GPL-3.0-only，并加入 water2H2O 共同作者署名。

## 开发

使用 JDK 21，通过项目自带的 Gradle Wrapper 构建：

```powershell
.\gradlew.bat build
```

开发客户端、服务端和数据生成任务分别使用 `runClient`、`runServer` 和 `runData`。

构建产物位于 `build/libs/cookchuter_core-0.1.0-hotfix2.jar`。

项目基于 [NeoForge 官方 Minecraft 1.21.1 ModDevGradle MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle) 创建。模组源码以 GPL-3.0-only 发布；NeoForge MDK 模板文件继续遵循 `TEMPLATE_LICENSE.txt` 中的 MIT 许可。
