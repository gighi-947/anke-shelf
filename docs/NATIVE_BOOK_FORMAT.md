# Anke Native Book 格式规范（ank-native/1）

> 用途：正式定义原生书容器格式，作为 Windows / Android / 导出链路共同遵守的
> 一等领域格式。配套结构校验见 [contracts/native-book](../contracts/native-book)，
> 字段表见 [DATA_CONTRACT.md](DATA_CONTRACT.md) 第 7 节。

## 1. 概览

- 格式标识：`format = "ank-native/1"`（`meta.json` 内）。
- 目录布局（一本书 = 一个目录）：

```text
<帖子目录>/book/
├── meta.json         元数据（tid/章节列表/分组参数/book_id）
├── floors.json       全部楼层原始数据（canonical source，含楼中楼）
└── chapters/         渲染缓存（XHTML），只追加、不重写
    ├── 0000.xhtml
    ├── 0001.xhtml
    └── ...
```

## 2. 核心不变量（invariants）

1. **章节正文前缀稳定**：已有 `chapters/*.xhtml` 一旦写入不得修改旧内容；
   热更新只能在末尾追加楼层（填满最后一个普通章节 = 在 `</body>` 前追加），
   或追加新的章节文件。这是 `text_offset` 稳定的前提。
2. **楼层编号单调**：`floors.json` 按 `lou` 升序追加，`last_lou` 单调不减；
   新增楼层以 `pid` 去重（同 pid 不重复写入）。
3. **text_offset 语义不变**：章节纯文本折叠规则见
   [TEXT_NORMALIZATION_SPEC.md](TEXT_NORMALIZATION_SPEC.md)。
4. **book_id 稳定**：首次下载生成后不再变化；书架、进度、标注均以它为键。
5. **chapter_index 稳定**：正常增量更新只追加章节，不改变已有章节的索引。
6. **schema 带版本**：`meta.format` 为格式版本；未来升级必须走迁移，不允许
   原位改写旧数据。

## 3. meta.json 字段

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `format` | string | ✓ | 恒为 `ank-native/1` |
| `book_id` | string | ✓ | 稳定书籍 ID（首建生成） |
| `tid` | int | ✓ | NGA 帖子 ID |
| `author_id` | int | ✓ | 帖子作者 uid（只看楼主） |
| `title` | string | ✓ | 帖子标题（可被用户重命名） |
| `author` | string | ✓ | 作者名 |
| `folder_name` | string | ✓ | 帖子目录名（`<tid>(<authorid>)` 形式） |
| `per_chapter` | int | ✓ | 每章楼层数（≥1） |
| `image_mode` | string | ✓ | `online` / `embedded` / `none` |
| `theme` | string | ✓ | 下载转换主题 `light` / `dark` |
| `toc_mode` | string | ✓ | `index`（仅索引）/ `split`（兼作分章） |
| `toc` | array | ✓ | 目录章节：`{title, entries:[[标题, pid], ...]}` |
| `chapters` | array | ✓ | 章节元数据（见下） |
| `last_lou` | int | ✓ | 已下载的最大楼号 |
| `created_time` / `updated_time` | string | ✓ | ISO/显示时间 |

章节元数据项：

| 字段 | 类型 | 说明 |
|---|---|---|
| `file` | string | `chapters/<4 位序号>.xhtml` |
| `title` | string | 章节标题（`第 X~Y 楼` / `序章 · 主楼` / 目录标题） |
| `floor_count` | int | 本章楼层数 |
| `first_lou` / `last_lou` | int | 本章首/末楼号 |
| `main` | bool | 是否主楼独占章节 |

## 4. floors.json 字段

楼层对象（`serialize_floor` / `NativeFloor` 一致，`comments` 递归同构）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `pid` | int | 楼层/回复 ID |
| `lou` | int | 楼号（主楼=0；无效楼=-1） |
| `timestamp` | int | Unix 秒 |
| `username` / `user_id` | string/int | 作者 |
| `like_num` | int | 赞数 |
| `raw_content` | string | 原始正文（BBCode/标记原文，canonical） |
| `comments` | array | 楼中楼引用（递归 Floor 结构） |

## 5. 章节分组规则

- 主楼（`pid == 0`）独占首章，标题 `序章 · 主楼`，正文含 `<h1>` 帖子标题。
- 其余楼层按 `per_chapter` 切章；单章标题 `第 X 楼`（X==Y）或 `第 X~Y 楼`。
- `toc_mode == "split"` 且提供目录时：按目录章节切分（从每章首个可定位条目
  所在楼层开始，到下一章首个条目之前结束）。
- 热更新追加：先填满最后一个**非主楼**章节的空位（`floor_count < per_chapter`），
  其余按 `per_chapter` 开新章节；主楼章节永不追加。

## 6. 读取与安全

- 章节相对路径必须通过 POSIX 归一化 + 目录穿越检查（拒绝 `..`、反斜杠、
  绝对路径）；Windows `_safe_rel` 与 Android `safeRel` 语义一致。
- 所有 JSON 均为 UTF-8、原子写（临时文件 + rename）。

## 7. 迁移

- `ank-native/1` 升级为 v2 时，必须提供 `load → detect version → migrate →
  validate → 原子写回` 的迁移路径，并保持旧正文前缀不变。
- 导出 EPUB 时以 `floors.json` 全量重建（在线图模式下无需联网重下图片）。

## 8. 配套资产

- JSON Schema：`contracts/native-book/meta.schema.json`、`floors.schema.json`。
- 最小 fixture：`contracts/fixtures/native-book/basic-nga/`（含期望纯文本）。
