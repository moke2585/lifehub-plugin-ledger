# 记账插件（LifeHub）

[LifeHub](https://github.com/moke2585/lifehub) 的第一方记账插件。插件提供 manifest、声明式页面与 Kotlin action/data handler，由宿主统一渲染页面、校验能力并保存数据。

| 项 | 值 |
|---|---|
| 插件 ID | `com.lifehub.plugin.ledger` |
| 名称 | 记账 |
| 版本 | 1.7.0 |
| 插件 SDK | 1（`minHostVersion=1`） |
| 数据 schema | 1 |
| 页面 | 10 |
| 能力 | `ui.action`、`storage.private` |

## 功能

- 收支记录：新增、编辑、删除、30 天回收站与恢复；金额以最小货币单位保存。
- 周期视图：日/月/年切换、左右翻页、回到今天、按日期分组与结余摘要。
- 统计：支出柱状图、分类环图、较上月、日均、最大单笔、Top 分类，可下钻到当日/当月/分类明细。
- 预算：按月独立预算、结转三档（关闭/仅结余/双向）、分类预算，可批量应用到连续月份。
- 借还台账：区分我借出（应收）与我借入（应付），还款可关联原借款并冲减剩余金额。
- 分类：40 个 Emoji 图标、自定义分类与“新建类型”，分类库按使用频率排序并支持折叠展开。
- 搜索与筛选：顶栏搜索页、方向筛选、最近关键词。
- 宿主集成：主页小组件“本月支出”“本月预算”，全局搜索数据源 `ledger_search`。

## 页面

| 页面 ID | 标题 |
|---|---|
| `list` | 记账 |
| `edit` | 记账记录 |
| `stats` | 统计分析 |
| `budget` | 月度预算 |
| `budget-category` | 分类预算 |
| `search` | 搜索账目 |
| `category-pick` | 选择分类 |
| `category-new` | 新建类型 |
| `loans` | 借还台账 |
| `loan-match` | 关联借款 |

页面结构遵循宿主的 [页面 schema v1](https://github.com/moke2585/lifehub/blob/main/docs/plugin-schema-v1.md)；宿主负责样式、主题与动效，插件只返回节点与数据。

## 数据

内置形态通过宿主 `PluginStorage` 保存，随宿主备份一起导出（备份 v1，口令加密）：

| 集合 | 内容 |
|---|---|
| `entries` | 收支记录 |
| `entries_trash` | 回收站（30 天） |
| `budget` | 月度预算、结转模式与分类预算 |
| `categories` | 自定义分类与图标注册表 |

APK 形态使用插件自己的 SQLite，数据库不暴露给宿主；宿主备份通过 provider 的 `exportBackup` / `importBackup` 读写 `PluginDataSnapshot`。内置数据在首次发现 APK 时自动迁移，旧数据库保留作为回滚保险。

插件不声明网络能力，也不读取插件外部数据。

## 目录

```text
lifehub-plugin-ledger/
├── build.gradle.kts
├── src/main/java/com/lifehub/app/plugins/LedgerPlugin.kt
├── src/main/assets/plugins/com.lifehub.plugin.ledger/
│   ├── manifest.json
│   └── pages/*.json
├── src/test/java/com/lifehub/app/plugins/LedgerPluginTest.kt
└── apk/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        └── java/com/lifehub/plugin/ledger/apk/
            ├── LedgerApkProvider.kt
            └── LedgerApkStorage.kt
```

## 构建与测试

本仓库不能单独构建：`plugin-api`、`plugin-runtime` 与宿主构建脚本位于同级宿主仓库，插件模块由宿主 `settings.gradle.kts` 以 `projectDir` 引入。请把本仓库与 `lifehub` 放在同一父目录。

```powershell
cd ..\lifehub

# 插件单元测试（35 项）
.\gradlew.bat :plugins:ledger:testDebugUnitTest

# 插件包校验
.\gradlew.bat :dev-harness:validatePlugins

# 构建包含本插件的调试 APK
.\gradlew.bat :app:assembleDebug

# 构建独立的记账插件 APK（headless provider）
.\gradlew.bat :plugins:ledger-apk:assembleDebug
```

## 接入方式

宿主 `settings.gradle.kts` 将本仓库映射为 `:plugins:ledger` 与 `:plugins:ledger-apk`。阶段 1 保留内置 `LedgerPlugin`，同时在插件市场提供独立 APK：

- 未安装 APK 时使用内置实现，数据保存在宿主 `PluginStorage`。
- 安装 APK 后，宿主把内置四个集合导出为 `PluginDataSnapshot`，通过 provider 导入插件数据库；导入成功后才切换到 APK 入口。
- 卸载 APK 或迁移失败（例如数据超过 512 KiB Binder 上限）时自动回退内置实现，旧数据库不被删除。
- 阶段 2 将移除宿主内置注册，默认通过插件市场安装。

开发文档：[插件开发指南](https://github.com/moke2585/lifehub/blob/main/docs/plugin-development.md)
