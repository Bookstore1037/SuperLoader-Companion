# SuperLoader Companion

NeoForge 侧的配套 mod，给 [SuperLoader](https://github.com/Bookstore1037/SuperLoader) 用。它做三件事：

1. **补 refmap**：Fabric mod 在 mixin 配置里声明了 refmap，但 jar 里没有这个文件时，Connector 找不到映射，required mixin 会直接失败（`No refMap loaded`），整轮 Fabric 加载被跳过。companion 在游戏启动前扫一遍 `mods/`，用 Connector 自带的 `mappings.tiny`（可选叠加 Yarn 映射）把 refmap 合成出来，再逐条校验，结果写进报告。
2. **bclib 去皮桥**：NeoForge 21.1 把斧头去皮改成走数据映射（`BlockState.getToolModifiedState` / `NeoForgeDataMaps.STRIPPABLES`），bclib 注入的 `AxeItem.getStripped` 变成了死代码，于是 BetterEnd / BetterNether 的木头用斧头去不了皮。companion 监听 `BlockToolModificationEvent`，把实现了 bclib `AxeCanStrip` 的方块接回去皮流程。
3. **运行时数据采集**：把加载阶段和事件数据写进实例的 `logs/superloader-report/`（`runtime.json`、`events.json`），供 SuperLoader 渲染成人类可读的报告。

## 环境

- Minecraft **1.21.1**
- NeoForge **21.1.265**
- Java **21**

## 构建

需要 JDK 21。离线构建：

```bat
gradlew.bat build --offline
```

产物：`build/libs/superloader_companion-0.6.3.jar`。

也可以直接 `gradlew.bat build`（会联网拉依赖），CI 里就是这么跑的。

## 安装

把 jar 丢进实例的 mods 目录：

```
<你的 SuperLoader 根目录>\instances\1.21.1\mods\
```

SuperLoader 的 `install` / `launch` 会自动把对应版本的 companion 放进去，通常不需要手动拷。

## 开关

| 变量 | 作用 |
|---|---|
| `SL_NO_REFMAP_REPAIR=1` | 关掉启动前的 refmap 修补 |
| `SL_NO_AXE_BRIDGE=1` | 关掉 bclib 去皮桥 |
| `-Dsuperloader.axeBridge.disabled=true` | 同上（JVM 参数形式） |

## 日志里看什么

- `[refmap] 缺失 refmap N 个，修补 N 个 jar，条目 N，校验通过 N，未解析 N，yarn=loaded`
- `[refmap-repair] …`：修补工具自己的输出行
- `[sl-bridge] axe strip bridge installed (bclib AxeCanStrip -> BlockToolModificationEvent)`：去皮桥装上了
- `[sl-bridge] axe strip: Block{betterend:lacugrove_log} -> Block{betterend:lacugrove_stripped_log}`：去皮真的命中

## 单独跑 refmap 修补工具

平时不用手动跑，SuperLoader 启动前会调。要排查时：

```bat
java -cp build\libs\superloader_companion-0.6.3.jar com.superloader.companion.repair.RefmapRepair ^
  --mods  "D:\SLData\instances\1.21.1\mods" ^
  --connector "D:\SLData\instances\1.21.1\mods\connector-2.0.0-beta.17+1.21.1-full.jar" ^
  --report "out\refmap_repair.json" ^
  --mc-jar "D:\SLData\instances\1.21.1\libraries\net\minecraft\client\1.21.1-20240808.144430\client-1.21.1-20240808.144430-srg.jar" ^
  --yarn-version 1.21.1+build.3 ^
  --yarn-sha1 53c8c9729c0dd52e3549c04f16eca89a15818822 ^
  --cache ".cache\mappings" ^
  --dry-run
```

- `--dry-run` 只算不写；去掉就会真的改 jar（改动前备份到 `mods\.superloader-refmap\`）。
- `--restore` 用备份把 jar 还原。
- 报告里每条映射都带 `verified: true/false`：`false` 的进 unresolved，不会静默写错映射。
- 没有 `--yarn-version/--yarn-sha1` 也能跑，只是裸 Yarn 名（`getNeighboringEntry(...)` 这类）解析不出来。

## 已知限制

- 只修"缺 refmap"这一类缺口。refmap 存在但内容错、或者 mixin 目标被 NeoForge 改掉的（`Language.loadFromJson`、`CropBlock.getGrowthSpeed`、`AxeItem.getStripped` 这类目标迁移），这个 mod 管不了。
- 去皮桥只认实现 bclib `AxeCanStrip` 的方块；别的 mod 自带的去皮逻辑仍然走 NeoForge 数据映射。
- 版本是硬编码的（`gradle.properties` 的 `mod_version` 与 `SuperLoaderCompanion.VERSION` 要同时改）。

## License

MIT，见 [LICENSE](LICENSE)。
