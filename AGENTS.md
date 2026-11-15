# 项目工作区

- 中文名：厨升核心
- 英文名：Cookchuter Core
- Mod ID：`cookchuter_core`
- 目标版本：Minecraft 1.21.1、NeoForge、Java 21
- 模组源码许可证：GPL-3.0-only；保留 `TEMPLATE_LICENSE.txt` 中 NeoForge MDK 模板文件原有的 MIT 许可声明。
- 共同作者：water2H2O（https://github.com/water2H2O）。
- 使用项目自带的 Gradle Wrapper；Windows 下执行 `.\gradlew.bat`。
- Java 和资源文件使用 UTF-8。模组元数据修改 `src/main/templates/META-INF/neoforge.mods.toml`，不要手改 `build/` 下的生成文件。
- 每次完成修改后更新 `README.md` 的版本信息和变更记录，并使用 JDK 21 执行 `.\gradlew.bat build` 验证。
- 新功能将语义版本补丁号加 `0.0.1`；修复或在既有功能上增加机制时，在当前版本后递增 `-hotfixN`。
- 每次完成修改后，将旧版 `cookchuter_core` JAR 从 `D:\MC\PCL2\.minecraft\versions\1.21.1 NeoForge 地牢餐厅\mods` 移到 `D:\MC\PCL2\.minecraft\versions\1.21.1 NeoForge 地牢餐厅\待清除`，然后将新构建的 JAR 放入 `mods`。
- GitHub 仓库设为公开；只提交源码、资源、构建脚本、Gradle Wrapper、必要文档和许可证，不提交 `.gradle/`、`build/`、`run/` 或本地缓存。
