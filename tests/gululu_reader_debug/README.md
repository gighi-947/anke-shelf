# 骨碌碌专版阅读器调试区

本目录用于 Windows 端骨碌碌专版阅读器的独立实验与排版调试。这里的内容不参与
AnkeShelf 正式构建，也不属于 Android 工程。调试壳层复用现役 EPUB 解析器和
骨碌碌转换器，专版页面代码保留在本目录。

## 边界

- 调试目标：标准 EPUB 导入后的章节结构、正文样式、在线图片、折叠块、评论/弹幕、
  音乐、氛围背景、动态视效和分页/滚动表现。
- 正式实现仍位于 `app/` 与 `web/`；实验验证完成后，连同回归测试一起迁入对应目录。
- 公共 API 固件继续复用 `tests/fixtures/gululu/`，避免维护重复样本。
- 不在本目录保存登录态、Cookie、密钥、未脱敏用户数据或第三方插件源码。
- 不从 Android 工程引用本目录，也不在这里修改双端 JSON 契约。

## 本地工作区

`workspace/` 用于生成 EPUB、解包产物、截图、日志和临时网页。除其
`.gitignore` 外，该目录内容默认不提交。

## 启动

从仓库根目录生成测试书并启动阅读器：

```powershell
python -m tests.gululu_reader_debug.server --source 66905
```

打开 `http://127.0.0.1:8877/`。再次启动会复用 `workspace/gululu-66905.epub`；
需要重新获取时增加 `--refresh`。也可以用 `--epub <路径>` 打开其他标准 EPUB。

正式专版采用正文优先布局：目录、评论、书签、音乐与氛围、骰点揭示、设置均为右下角
一级入口，更多只保留全屏和重置等低频操作；独立调试壳继续用于验证布局原型。底部只
保留翻页与阅读位置。设置面板集中承载滚动/分页、字号、行距、版心、三种阅读
主题、楼末评论和弹幕；评论按当前章节或指定楼层从底部抽屉打开。正文楼层使用专版卡片
层级显示楼号、标题和评论数量；边框、左侧强调线、楼头分隔和紧凑间距参考 NGA 安科，
强调色降低饱和度并继续由 AnkeShelf 阅读主题控制。

调试壳读取 EPUB 内嵌评论：“楼末评论”控制原有可折叠评论区，“弹幕”把当前章节评论
投射到宿主层。正式 Windows 阅读器则默认按章节在线读取评论，每楼按钮可打开对应侧边
面板，也可切换为楼末折叠；动态注入的按钮与评论使用 `data-textpos-exclude` 排除在
`text_offset` 坐标之外。两种实现均为只读，不包含登录、发言、点赞等站点写操作。
服务器仅监听 `127.0.0.1`，章节脚本由 CSP 禁止执行。

本机已安装 Playwright 时，可以另开终端运行可选的浏览器冒烟检查：

```powershell
node tests/gululu_reader_debug/ui_smoke.js
```

正式 Windows 阅读器的在线评论/弹幕与沉浸效果冒烟检查使用独立进程启动：

```powershell
python -m tests.gululu_reader_debug.formal_server --port 8878
$env:GULULU_FORMAL_URL='http://127.0.0.1:8878/index.html?token=gululu-formal-debug'
node tests/gululu_reader_debug/formal_ui_smoke.js
```

脚本以可控假音频和 1px 图片响应验证一级入口、骰点/迷雾与持久化、播放状态、背景、
Canvas 像素、逐楼评论、楼末折叠、正文坐标不变、骨碌碌封面标签、切换 NGA 后的专版
功能隔离，以及返回书架清理。桌面和
窄屏截图写入 `workspace/screenshots/`，不会进入版本库；长章节还会验证分页模式进出
沉浸式阅读后页码与 `text_offset` 恢复一致。

调试结论应补充到 `AnkeShelf_DevLog.md`；可复现问题应先转成 `tests/` 下的失败测试，
再修改正式代码。
