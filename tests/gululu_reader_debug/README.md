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

调试页面提供目录、滚动/分页切换、字号、行距、版心、三种阅读主题，以及彼此独立的
“评论”和“弹幕”开关。“评论”控制 EPUB 内的可折叠评论区；“弹幕”把当前章节的
公开评论投射到阅读器上层，不改变 iframe 内的正文和文本坐标。两者均为只读，不包含
登录、发言、点赞等站点写操作。服务器仅监听 `127.0.0.1`，章节脚本由 CSP 禁止执行。

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

脚本以可控假音频和 1px 图片响应验证播放状态、背景、Canvas 像素、正文坐标不变、
返回书架清理，以及评论/弹幕共存。桌面和窄屏截图写入 `workspace/screenshots/`，
不会进入版本库。

调试结论应补充到 `AnkeShelf_DevLog.md`；可复现问题应先转成 `tests/` 下的失败测试，
再修改正式代码。
