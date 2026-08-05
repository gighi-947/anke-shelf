"""章节 DOM 派生纯文本提取 —— 与 web/js/textpos.js 的 buildPlainText 逐字符对齐。

统一规则（两端必须一致，差分测试守护）：
1. 仅提取 <body> 内的文本内容（跳过 <script>/<style> 块）；无 <body> 标签的
   片段退化为提取全部（与浏览器解析器自动补 body 的语义一致）；
2. 每个标签（开始/结束/自闭合）视为相邻文本块之间的一个分隔空格；
3. 连接后用 `\\s+` → 单个空格 折叠；
4. 首尾 trim。

等价性论证：浏览器把 HTML 解析为 DOM 后，任意两个相邻文本节点之间必然
隔着元素边界；「每标签一个空格 + 全局折叠」与「文本节点间一个空格 + 全局
折叠」产生相同结果。HTMLParser 的 convert_charrefs=True 使实体在数据阶段
即解码（与浏览器解析一致），两端均无需维护实体表。

注意：本模块输出的偏移坐标 **包含折叠后的空白字符**，搜索/进度/标注统一
使用此坐标；snippet 等展示层可再做显示美化（不影响坐标）。
"""
import re
from html.parser import HTMLParser

_RE_WS = re.compile(r"\s+")

_SKIP_TAGS = {"script", "style"}


class _TextBuilder(HTMLParser):
    """按「仅 body 内」规则收集文本块；无 body 时回退全部。"""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._all_chunks: list[str] = []
        self._body_chunks: list[str] = []
        self._skip_depth = 0
        self._body_depth = 0
        self._saw_body = False

    def _tag_space(self) -> None:
        self._all_chunks.append(" ")
        if self._body_depth > 0:
            self._body_chunks.append(" ")

    def handle_starttag(self, tag: str, attrs) -> None:
        if tag in _SKIP_TAGS:
            self._skip_depth += 1
        if tag == "body":
            self._saw_body = True
            self._body_depth += 1
        elif self._body_depth > 0:
            self._body_depth += 1
        self._tag_space()

    def handle_startendtag(self, tag: str, attrs) -> None:
        self._tag_space()

    def handle_endtag(self, tag: str) -> None:
        if tag in _SKIP_TAGS and self._skip_depth > 0:
            self._skip_depth -= 1
        if tag == "body":
            self._body_depth -= 1
        elif self._body_depth > 0:
            self._body_depth -= 1
        self._tag_space()

    def handle_data(self, data: str) -> None:
        if self._skip_depth == 0:
            self._all_chunks.append(data)
            if self._body_depth > 0:
                self._body_chunks.append(data)

    def result(self) -> str:
        chunks = self._body_chunks if self._saw_body else self._all_chunks
        return "".join(chunks)


def extract_dom_text(html_text: str) -> str:
    """HTML 字符串 → 折叠后的纯文本（与 JS buildPlainText 输出一致）。"""
    if not html_text:
        return ""
    builder = _TextBuilder()
    try:
        builder.feed(html_text)
        builder.close()
    except Exception:
        pass
    return _RE_WS.sub(" ", builder.result()).strip()
