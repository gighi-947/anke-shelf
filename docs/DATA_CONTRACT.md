# 双端数据契约（Data Contract）

> Windows 与 Android 两端共享同一套 JSON 数据契约，用于将来数据互通与
> 交叉验证。任何一端扩展字段都必须遵守本文档的兼容规则。
>
> 机器可验证资产见 `contracts/`（JSON Schema + fixtures）；原生书格式规范见
> [NATIVE_BOOK_FORMAT.md](NATIVE_BOOK_FORMAT.md)，文本坐标规范见
> [TEXT_NORMALIZATION_SPEC.md](TEXT_NORMALIZATION_SPEC.md)。

## 1. 存储位置与写入方式

| 端 | 数据根目录 |
| --- | --- |
| Windows | `%APPDATA%\AnkeShelf\`（源码运行同目录） |
| Android | `filesDir/AnkeShelf/`（应用私有目录） |

- 所有 JSON 均为 UTF-8、原子写（临时文件 + rename），不落中间态。
- 两端独立存储各自的数据文件；字段为两端并集，**未知字段一律忽略**。

## 2. shelf.json

```json
{"version": 1, "books": [ { ...BookRecord } ]}
```

`BookRecord` 字段（两端一致）：

| 字段 | 类型 | 缺省 | 说明 |
| --- | --- | --- | --- |
| `id` | string | — | 稳定书籍 ID |
| `path` | string | — | 书籍文件/目录路径 |
| `title` | string | — | 显示标题 |
| `author` | string | `""` | 作者 |
| `language` | string | `""` | 语言 |
| `chapter_count` | int | `0` | 章节数 |
| `cover_rel` | string? | `null` | 相对数据目录的封面缓存路径 |
| `file_size` | int/long | `0` | 文件大小 |
| `file_mtime` | string | `""` | 文件修改时间 ISO |
| `added_at` | string | `""` | 加入时间 ISO |
| `last_read_at` | string | `""` | 最近阅读时间 ISO |
| `nga_tid` | int | `0` | >0 表示 NGA 帖子下载书 |

`progress_pct` 为运行时合成字段，**不落盘**。

## 3. progress.json

```json
{"version": 2, "progress": { "<book_id>": { ...ProgressEntry } }}
```

`ProgressEntry` 字段：

| 字段 | 类型 | 缺省 | 归属 | 说明 |
| --- | --- | --- | --- | --- |
| `chapter_index` | int | `0` | 两端 | 章索引（0 基） |
| `text_offset` | int | `0` | 两端 | 章内折叠纯文本字符偏移（唯一坐标） |
| `updated_at` | string | `""` | 两端 | 最后更新 ISO |
| `page_index` | int | `-1` | 安卓扩展 | 分页模式页码（0 基；-1=无） |
| `page_total` | int | `-1` | 安卓扩展 | 分页模式总页数（-1=无） |
| `scroll_ratio` | double | `-1.0` | 安卓扩展 | 滚动模式全图页比例（0..1；-1=文本锚点） |

兼容规则：

- `text_offset` 永远非负；`0` 表示章首/无进度。
- `text_offset` 计数语义：**UTF-16 code unit**（emoji 等星形字符占 2；
  与 DOM/JS/Kotlin 字符串索引一致；Python 对外输出做换算）。
- 安卓扩展字段缺省 `-1/-1.0`，旧数据与 Windows 数据读入后保持缺省；
  Windows 端读取安卓数据时忽略这三个字段。
- 模式隔离：`page_index/page_total` 只属于分页模式；`scroll_ratio` 只属于
  滚动模式；同一章节同时出现时以最近一次写入模式为准（安卓端写入时
  显式清除另一模式字段）。

## 4. settings.json

```json
{"settings_version": 3, ...}
```

两端字段并集（未知字段忽略，缺省值向后兼容）：

| 字段 | 类型 | 缺省 | 说明 |
| --- | --- | --- | --- |
| `settings_version` | int | `3` | 迁移版本，只增不删 |
| `theme` | string | `"dark"` | 主题（dark/light/sepia） |
| `theme_mode` | string | `""` | system=跟随系统 |
| `font_size` | int | `18` | 正文字号 |
| `line_height` | double | `1.8` | 行高 |
| `ui_font_scale` | double | `1.0` | 安卓界面字号缩放 |
| `font_family` | string | `"reader"` | 字体族 |
| `custom_font` | string | `""` | 自定义字体文件名 |
| `book_fonts` | map | `{}` | 按书字体覆盖 |
| `custom_bg/primary/accent/text` | string | `""` | 自定义配色（空=跟随主题） |
| `page_width` | double | `1.0` | 页面宽度系数 |
| `pagination` | bool | `false` | 分页/滚动模式 |
| `dual_page` | bool | `false` | 强制双页 |
| `auto_dual` | bool | `true` | 自动双页 |
| `shelf_view` | string | `"grid"` | grid/list |
| `shelf_sort` | string | `"recent"` | 排序方式 |
| `margin_px` | int | `40` | 页边距 px |
| `gap_px` | int | `28` | 列间隙 px |
| `brightness` | double | `0.0` | 亮度调节 |
| `rsvp_rate` / `autoscroll_speed` / `show_ruler` / `show_statusbar` | — | — | Windows 阅读辅助 |
| `shortcuts` | map | `{}` | 快捷键（Windows） |
| `window_size` | list | `[1024,720]` | 窗口尺寸（Windows） |
| `last_open_book` | string? | `null` | 最近打开书 |

## 5. annotations.json

```json
{"version": 1, "books": { "<book_id>": {
  "highlights": [ { "id", "chapter_index", "start_offset", "end_offset",
                    "text", "color", "note", "created_at", "updated_at" } ],
  "bookmarks":  [ { "id", "chapter_index", "offset", "text", "created_at" } ]
}}}
```

- `start_offset/end_offset/offset` 为章内折叠纯文本字符偏移（与 text_offset 同坐标系）。
- 高亮色取值：`yellow/green/blue/pink/purple/cyan`（6 色）。

## 6. statistics.json

```json
{"version": 1,
 "books": { "<book_id>": { "total_seconds": int, "sessions": int,
   "pages_flipped": int, "last_read_at": "ISO",
   "days": { "YYYY-MM-DD": { "seconds": int, "pages": int } } } },
 "global": { "total_seconds": int, "days": { ... } }}
```

## 7. 原生书容器（NGA 连载热更新格式）

> 权威规范见 [NATIVE_BOOK_FORMAT.md](NATIVE_BOOK_FORMAT.md)——字段明细、核心
> 不变量与迁移规则以该文档为唯一权威，本节仅为契约摘要。

目录：`<帖子目录>/book/`，格式标识 `format = "ank-native/1"`。

### meta.json

| 字段 | 说明 |
| --- | --- |
| `format` | `"ank-native/1"` |
| `book_id` / `tid` / `author_id` | 帖子身份 |
| `title` / `author` / `folder_name` | 展示信息 |
| `per_chapter` | 每章楼层数 |
| `image_mode` | online/embedded/none |
| `theme` | light/dark |
| `toc_mode` | index/split 等 |
| `toc` | 目录树（title + pid 列表） |
| `chapters` | `[{file, title, floor_count, first_lou, last_lou, main}]` |
| `last_lou` / `created_time` / `updated_time` | 进度信息 |

### floors.json

楼层数组：`{pid, lou, timestamp, username, user_id, like_num, raw_content, comments[]}`
（comments 为引用楼递归结构）。

### chapters/*.xhtml

- 章节文件**只追加、不重写**，保证 text_offset 稳定（热更新语义）。
- 正文为 NGA 排版 HTML（楼层卡片、引用、骰子、颜色内联样式）。

## 8. 备份包（ank-backup/1）

- 统一备份包为 zip：`manifest.json`（format=ank-backup/1、created_at、app_version、
  files[{name, version, size, sha256}]）+ 五个 JSON 存储
  （shelf / progress / settings / annotations / statistics）。
- 导入流程：先只读验证（清单 / 校验和 / 可解析性 / 版本字段），失败不写；
  目标已有数据时默认不覆盖，需显式确认（overwrite=true）。
- 实现：`app/backup.py`；入口：设置页「备份数据 / 验证备份包 / 导入备份」。

## 9. 新增字段流程（必须遵守）

1. 默认值向后兼容（新字段缺省等价旧行为）；
2. 同步更新本文档；
3. 另一端读入时忽略未知字段（不得崩溃）；
4. 涉及进度/标注坐标的字段，双端坐标系保持一致（章内折叠纯文本偏移）。
