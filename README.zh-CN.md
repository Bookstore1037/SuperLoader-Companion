[English](README.md) | 简体中文

# SuperLoader Companion

一个 NeoForge 侧的配套 mod，用来在 Minecraft 1.21.1 上填补 Sinytra Connector
与 Fabric 原生 mod 之间的缺口。任何 NeoForge 整合包都能用，SuperLoader 不是必需的。

它做三件事：

1. **refmap 修复。** 有些 Fabric mod 在 mixin 配置里声明了 refmap，却没有把文件打进 jar。
   Connector 无法重映射它们的 mixin，required mixin 会以 `No refMap loaded` 失败，
   导致这一轮 Fabric 加载整体中止。companion 在游戏启动前扫描 `mods/`，用 Connector 的
   `mappings.tiny`（可叠加 Yarn）合成缺失的 refmap，逐条校验，并把结果写进报告。
   被修补的 jar 会备份到 `mods/.superloader-refmap/`。

2. **bclib 去皮桥。** NeoForge 21.1 把斧头去皮改成了数据映射路径
   （`BlockState.getToolModifiedState` / `NeoForgeDataMaps.STRIPPABLES`）。
   bclib 对 `AxeItem.getStripped` 的 mixin 变成了死代码，于是 BetterEnd 和 BetterNether
   的木头无法去皮。companion 监听 `BlockToolModificationEvent`，把所有实现 bclib
   `AxeCanStrip` 的方块重新接回去皮流程。一个桥覆盖所有使用该接口的 mod，而不是针对某个 mod id。

3. **运行时数据采集。** 在加载和游戏过程中，把 `runtime.json` 和 `events.json` 写进实例的
   `logs/superloader-report/`。这些是可选诊断数据，由 SuperLoader 的报告渲染器消费；
   不装 SuperLoader 时 mod 也能正常工作。

## 环境

除 Minecraft 1.21.1 和 NeoForge 21.1.265 外，还需要：

- **Sinytra Connector**（`connector-2.0.0-beta.17+1.21.1-full.jar` 或兼容版本）
- **Forgified Fabric API**（`forgified-fabric-api-0.116.15+2.3.6+1.21.1.jar` 或兼容版本）

两者都是硬依赖：refmap 修复要读 Connector 的 `mappings.tiny`，桥和运行时数据采集依赖
FFAPI 的兼容层。缺了它们 mod 不会工作。

它们发布在 Modrinth 上（`connector`、`forgified-fabric-api`），任何启动器都能安装。
用 SuperLoader 的话会自动装好。

## 构建

需要 JDK 21。

```bat
gradlew.bat build --offline
```

产物：`build/libs/superloader_companion-0.6.3.jar`。

`gradlew.bat build`（联网）也可以，CI 跑的就是这个。

## 安装

把 jar 丢进任意 NeoForge 1.21.1 实例的 `mods/` 目录即可。不需要启动器或加载器集成：
它就是一个普通 NeoForge mod。用 SuperLoader 的话，`install` / `launch` 会自动拷贝它，
但那只是便利，不是必需。

## 开关

| 变量 | 作用 |
|---|---|
| `SL_NO_REFMAP_REPAIR=1` | 关闭启动前的 refmap 修复 |
| `SL_NO_AXE_BRIDGE=1` | 关闭 bclib 去皮桥 |
| `-Dsuperloader.axeBridge.disabled=true` | 同上，JVM 参数形式 |

每个桥默认开启，可以单独关闭而不必删掉 mod。

## 日志行

- `[refmap] missing N, repaired N jars, entries N, verified N, unresolved N, yarn=loaded`
- `[refmap-repair] ...`：修复工具自己的输出
- `[sl-bridge] axe strip bridge installed (bclib AxeCanStrip -> BlockToolModificationEvent)`
- `[sl-bridge] axe strip: Block{betterend:lacugrove_log} -> Block{betterend:lacugrove_stripped_log}`

## 单独运行 refmap 修复工具

通常由 SuperLoader 在启动前调用。需要手动排查时：

```bat
java -cp build\libs\superloader_companion-0.6.3.jar com.superloader.companion.repair.RefmapRepair ^
  --mods  "<instance>\mods" ^
  --connector "<instance>\mods\connector-2.0.0-beta.17+1.21.1-full.jar" ^
  --report "out\refmap_repair.json" ^
  --mc-jar "<instance>\libraries\net\minecraft\client\1.21.1-20240808.144430\client-1.21.1-20240808.144430-srg.jar" ^
  --yarn-version 1.21.1+build.3 ^
  --yarn-sha1 53c8c9729c0dd52e3549c04f16eca89a15818822 ^
  --cache ".cache\mappings" ^
  --dry-run
```

说明：

- `--dry-run` 只计算不写入。去掉它才会修改 jar（备份在 `mods/.superloader-refmap/`）。
- `--restore` 用备份还原 jar。
- 报告里每条映射都带 `verified: true/false`；未通过校验的条目会列为 unresolved，
  绝不会被静默写入。
- `--yarn-version` 和 `--yarn-sha1` 是可选的；不提供时，裸 Yarn 名无法解析。

## 已知限制

- refmap 修复只解决“声明了 refmap 但文件缺失”这一种情况。它修不了 mixin 目标在
  NeoForge 侧被移动或改写的 mixin（`Language.loadFromJson`、`CropBlock.getGrowthSpeed`、
  `AxeItem.getStripped`）。那些是 Connector 层面的缺口，不是 refmap 缺口。
- 去皮桥只覆盖实现 bclib `AxeCanStrip` 的方块。其他 mod 自带的去皮逻辑仍然走
  NeoForge 数据映射。
- 版本号是写死的：`gradle.properties` 的 `mod_version` 与
  `SuperLoaderCompanion.VERSION` 必须一起改。
- 只支持 1.21.1。Sinytra Connector 已结束对 1.21.1 的 LTS 支持，因此不指望上游
  再修剩余的缺口。

## License

MIT，见 [LICENSE](LICENSE)。
