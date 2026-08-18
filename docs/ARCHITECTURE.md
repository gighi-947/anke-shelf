# 安科书架 · 架构说明

## 总体结构

```
run_app.py            入口（PyInstaller 目标脚本）
app/                  Python 后端
  main.py             窗口生命周期、服务装配、单实例、DPI
  server.py           本地 HTTP 服务（/api/<name> 分发 + /book/ 读取书内容 + /img/ NGA 图床代理）
  api/                前端唯一业务入口（api/ 包：registry + 按域 handler，方法名即接口名）
  settings.py         用户设置持久化（默认值 + 旧版迁移）
  shelf.py            书架/进度存储（原子 JSON）
  stats.py            阅读统计（全局 + 每书 + 按天）
  annotations.py      标注/书签
  search.py           全文搜索索引（按需构建；按章限量 + 续取 search_more）
  epub.py             自实现 EPUB 解析（container → OPF → spine → 目录）
  gululu_ast.py       骨碌碌富文本 mark 渲染
  gululu_assistant.py 全能助手折叠/秘密/线索/引用/骰点/迷雾协议与 CryptoJS AES 解密
  gululu_source.py    骨碌碌 URL / EPUB dc:identifier 来源识别
  gululu_comments.py  公开评论分页、子回复、前端最小字段与 EPUB 评论块
  gululu_immersive.py 音乐/背景/视效正文指令 → 安全 EPUB 语义标记
  gululu_images.py    正文图片三态、HTTPS 并发下载、格式校验与显式失败摘要
  gululu_epub.py      骨碌碌公开 API / AST → 标准 EPUB3
  gululu_service.py   紧凑导入、在线评论缓存与含评论 EPUB 导出
  native_book.py      原生增量书容器（meta.json + floors.json + chapters/）
  nga_service.py      NGA 下载/热更新服务（单飞任务 + 状态轮询）
  export_service.py   导出（EPUB/Markdown 复制 + 原生书重建 EPUB）
  fonts.py / dpi.py / instance_guard.py / dialogs.py / storage.py / paths.py
web/                  前端（纯静态，前后端分离）
  index.html + css/   书架/阅读器/设置/下载导出页
  js/
    bridge.js         fetch 封装（令牌鉴权 + 超时 + 调试 mock）
    api-client.js     前端 API 客户端（UI 统一走 Api.<method>()）
    app.js            应用状态、视图切换、顶底栏自动隐藏
    reader.js         阅读核心编排（章节加载/进度/快捷键/交互）
    reader-utils.js / reader-session.js / reader-navigation.js /
    reader-help.js / reader-image.js   reader.js 拆分的常量/会话/换章/帮助/图片模块
    gululu-comments.js 骨碌碌宿主层在线评论面板 / 只读弹幕
    gululu-assistant-reader.js 骨碌碌骰点/迷雾/折叠配置、解锁状态与点击音效
    gululu-immersive.js 骨碌碌宿主层音乐、氛围背景与动态视效
    gululu-secrets.js 骨碌碌线索本地状态、按需解密与宿主层秘密弹窗
    paged.js          CSS multi-column 分页核心（单页/自动双页/强制双页）
    nga_download.js + gululu-download.js   安科下载/导出/更新整合页
    settings.js       独立设置页
    stats.js / sidebar.js / toc.js / annotations.js / fullsearch.js
    bookshelf.js      书架网格/列表
    theme.js          主题与自定义配色
    textpos.js        DOM 文本坐标 ↔ text_offset
    util.js           公共小工具（快捷键展示、时长/日期格式化）
ngapost2md-python/    上游下载器（vendored，已做少量集成补丁）
tests/                单元测试 + UI 自动化
docs/                 架构与规划文档
```

## 数据流

- 前端所有业务调用走 `Api.<method>()`（`api-client.js`）→
  `Bridge.call(name, ...args)` → `POST /api/<name>`（本地随机令牌）。
- 书内容：`/book/<book_id>/<zip_path>`（EPUB zip 内路径）或原生书目录读取，
  章节由 iframe 加载（同源，可注入样式与交互）。
- NGA 图片/表情：章节内 NGA 图床 `src` 由 `server.py` 重写为
  `/img/<book_id>?u=<url>` 本地代理（NGA 域名白名单 + Referer/Cookie），
  规避防盗链 403；加载失败在双端显示占位卡（`data-textpos-exclude`，
  不影响 `text_offset`）。
- 阅读进度：统一 `text_offset`（纯文本字符偏移），滚动/分页模式都可精确恢复。
- NGA 下载：下载完成立即构建原生书容器并注册书架；热更新只拉新页、追加新楼层。
- 骨碌碌导入：公开 API 分批获取 → AST 转 XHTML → `.part` 原子替换紧凑 EPUB
  → 注册书架；阅读时按当前章节楼层获取评论，Windows 本地缓存 5 分钟，网络失败显式
  回退最近缓存。评论面板/弹幕均在宿主层，不修改 iframe 正文与 `text_offset`。
- 骨碌碌沉浸指令：转换器按正文结构识别音乐/背景/视效文本协议 → 仅保留无凭据 HTTPS
  外链和 EPUB `data-*` 语义标记 → Windows 宿主层读取当前章节标记并呈现音频、背景与
  Canvas/CSS 效果。运行时不改 iframe DOM，返回书架统一停止并清理。
- 骨碌碌全能助手：折叠、文本引用、骰点和迷雾在导入期转换为稳定 XHTML / `data-*`
  语义标记；运行时只切换遮罩/可见状态，不重写正文，保证 `text_offset` 不漂移。同书
  引用落 EPUB 楼层锚点，跨书引用回公开网页。秘密密文与线索保持 inert；线索按
  `bookId + title` 保存在本机，点击秘密时通过
  `gululu_decrypt_secret` 调用 PyCA cryptography 兼容解开 CryptoJS/OpenSSL salted
  AES，明文仅用 `textContent` 放入宿主层弹窗，不写回正文 DOM。
- 骨碌碌图片：导入/含评论导出均支持在线、内嵌、不含三态，默认在线。内嵌模式只接受
  HTTPS 位图，6 路并发、单图 25 MB 上限，按文件签名识别格式；失败图片转明确占位，
  失败数写入任务状态而不静默回退在线。在线图片写入 `loading="lazy" decoding="async"`；
  滚动模式先完成首屏排版，分页模式切回 eager 并按图片到达合并重排。音乐与背景媒体
  仍保持在线，作者章节边界保持不变。
- 骨碌碌热更新：`snapshot.json` 是 Windows 私有 sidecar，保存上次详情、楼层/章节索引、
  正文和图片模式，不进入双端数据契约。更新完整拉取索引，以旧楼层 ID 必须是远端严格
  前缀作为 append-only 不变量，只获取后缀新增正文；无新增且图片模式未变时不构建 EPUB。
  旧楼删除、重排、替换或基线损坏均显式失败并要求完整重导。旧 EPUB 无 sidecar 时读取
  `floor-*` 锚点并做一次全量远端核对；替换使用临时备份，失败恢复旧 EPUB 和书架登记，
  保持路径派生 `book_id`，从而保留进度与标注关联。
- 骨碌碌完整导出：重新获取全量公开评论 → 写入可折叠 XHTML 评论块 → 原子生成独立
  EPUB；不替换书架副本。两条链路均不写入 NGA / 双端 JSON 字段。
- 统计：前端 5 秒心跳 + 页面切换上报，后端按天聚合。

## 关键约定

- 设置项一律先加 `app/settings.py` 的 `DEFAULTS`；需要旧数据迁移时递增 `settings_version`。
- API 方法签名即接口契约：`Api.<name>(*args)`，返回可 JSON 序列化的 dict。
- 来源配色：NGA / 骨碌碌的近黑、近白默认文字映射到 `--reader-fg`；有色相文字与
  中间灰保持原样。
- 主题体系：`theme_mode` 支持 `system / light / sepia / dark`（空串=跟随 `theme`），
  预设色板是前端常量（`theme.js` 的 `PALETTES`），持久化仍只存
  `custom_bg / custom_text / custom_primary / custom_accent` 四色。
- 分页几何与 flow/epub.js 对齐：border-box、精确列宽、双页补偶数列。
- 发行版打包：`python -m PyInstaller --noconfirm --clean ankeshelf.spec`，
然后把 README/LICENSE/OFL（`assets/fonts/OFL.txt`）/使用说明复制进
`dist\AnkeShelf`，再压成目录版 zip。

## 测试

- 单元测试：`python -m unittest discover tests`
- UI 自动化（需桌面会话）：`python -m tests.ui.runner`
- 测试样本生成：`python -m tests.make_test_epub`
