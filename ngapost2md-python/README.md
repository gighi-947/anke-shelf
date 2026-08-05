# ngapost2md (Python 重写版)

将 NGA 论坛帖子转换为 Markdown 的 Python 实现。功能与
[Go 版 ngapost2md](https://github.com/ludoux/ngapost2md) 核心部分等价。

## 特性

- 单帖下载转 Markdown（含标题、热门回复、楼层、下挂评论）
- 多页并发下载（1~3 线程，限速防反爬）
- 增量更新：二次运行只追加新楼层，不重复下载媒体
- 内容格式化：匿名 ID、表情、引用、回复、折叠、骰子、图片/视频/音频

## 安装

```bash
pip install -r requirements.txt
```

## 配置

复制 `config.ini.example` 为 `config.ini`，填写必需项：

| 配置项 | 说明 |
|---|---|
| `ua` | 浏览器 User-Agent |
| `ngaPassportUid` | NGA 登录 Cookie |
| `ngaPassportCid` | NGA 登录 Cookie |

获取 Cookie：登录 `https://bbs.nga.cn` 后按 F12 → Application → Cookies。

也可直接运行 `python -m ngapost2md --gen-config-file` 生成默认配置。

## 使用

```bash
# 用 tid 下载
python -m ngapost2md 12345678

# 用链接下载
python -m ngapost2md https://bbs.nga.cn/read.php?tid=12345678

# 只看某用户发言
python -m ngapost2md 12345678 --authorid 99887766

# 只下载前 N 个有效楼层（如只取前 5 楼）
python -m ngapost2md 12345678 --max-floors 5

# 内容中不包含图片（移除图片标记）
python -m ngapost2md 12345678 --no-images

# 同时导出 EPUB（还原 NGA 风格排版）
python -m ngapost2md 12345678 --epub

# EPUB 图片在线引用（不嵌入）
python -m ngapost2md 12345678 --epub --epub-images online

# 显示版本
python -m ngapost2md -v
```

## 参数一览

| 参数 | 说明 |
|---|---|
| `tid_or_url` | 帖子 tid 或 NGA 链接 |
| `--authorid N` | 只看某用户的发言 |
| `--max-floors N` | 只下载前 N 个有效楼层（0 为不限制） |
| `--no-images` | Markdown 内容中不包含图片 |
| `--epub` | 同时导出 EPUB |
| `--epub-images {embedded,online}` | EPUB 图片模式：embedded（嵌入，默认）/ online（在线引用） |
| `--epub-per-chapter N` | EPUB 每章楼层数（默认 20） |
| `--gen-config-file` | 生成默认配置文件 |
| `-v, --version` | 显示版本 |

## EPUB 导出

`--epub` 会在生成 Markdown 的同时产出 `post.epub`，并**直接从 NGA 原始内容渲染 HTML**（而非经 Markdown 转换），因此最大程度还原 NGA 网页排版：

- 楼层卡片（楼号 · 赞 · 作者 · 时间 · pid，左侧主题色竖线）
- 引用块（`blockquote.nga-quote`）、回复引用、折叠（`<details>`）
- NGA 颜色标签（red / skyblue / crimson / silver 等）
- 图片：默认**嵌入**进 EPUB（离线可读），可切换 `--epub-images online`
- 表情、骰子、删除线等特殊元素

图片嵌入通过 `curl_cffi` 模拟 Chrome 浏览器指纹，以通过 NGA 图床防盗链。

## 图片去重

同一张图片在帖子中多处引用时，**只下载/嵌入一次**，多页共享同一资源：

- **URL 哈希**：以图片 URL 的 SHA-256 短哈希为键去重（Markdown 的 `assets.json`、EPUB 的资源表）
- **URL 规范化**：NGA 同一图片的缩略图变体（`xxx.png.thumb.jpg`、`xxx.png.medium.jpg`）会先归一为原图 URL，因此原图/缩略图/中图只保留一份

这样长篇安科里大量重复出现的人物立绘不会浪费重复下载的时间和空间。

## 输出

- `<tid>/post.md`：帖子 Markdown
- `<tid>/assets.json`：媒体资源去重映射
- `<tid>/process.ini`：增量进度（max_page / max_floor）
- `<tid>/assets/`：下载的媒体文件

## 目录结构

```
ngapost2md/
├── __init__.py   版本号
├── __main__.py   python -m 入口
├── cli.py        命令行解析
├── config.py     配置加载
├── client.py     httpx 客户端
├── models.py     数据模型（Floor/Tiezi）
├── nga.py        核心流程（抓页/并发/增量/写文件）
├── format.py     BBCode→Markdown 格式化
└── smile_map.py  表情与匿名表（从 Go 源码自动提取）
```

## 与 Go 版的差异

- 暂未实现：Server 模式、md 切分、IP 定位、引用原文补全、本地表情
- 配置：`output_path` 默认 `./`（可配置）
