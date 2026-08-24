"""NGA 外链音乐（[audio] BBCode）转换回归（方案 A，2026-08-23）。

[audio]https://…[/audio] 此前在 EPUB 路径零处理，读者看到裸 BBCode 文本。
修复后转为骨碌碌同款音乐 cue 按钮（复用双端宿主层播放器与样式）；
cue 文本进坐标（与骨碌碌一致：提取器与 JS TextPos 同源提取，搜索
索引与渲染坐标不漂移）。非 https 外链保留原文（双端一致降级）。
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "ngapost2md-python"))

from ngapost2md.format_html import render_content_html


class NgaAudioCueTest(unittest.TestCase):
    def _render(self, raw: str) -> str:
        return render_content_html(raw, None, lambda u: u)

    def test_audio_becomes_music_cue(self):
        out = self._render("前文[audio]https://music.example.com/song.mp3[/audio]后文")
        self.assertIn('class="gululu-music-cue"', out)
        self.assertIn('data-gululu-music-url="https://music.example.com/song.mp3"', out)
        self.assertIn("gululu-music-kind", out)
        # 原始 BBCode 不得残留
        self.assertNotIn("[audio]", out)

    def test_cue_text_enters_coordinates(self):
        """cue 文本进坐标：不加 data-textpos-exclude（骨碌碌同款，
        提取器与 JS TextPos 同源提取，搜索索引与渲染一致）。"""
        out = self._render("[audio]https://music.example.com/song.mp3[/audio]")
        self.assertNotIn("data-textpos-exclude", out)

    def test_cleartext_http_kept_as_plain_text(self):
        """明文 http 外链保留原文（宿主播放桥只接受 https，双端一致降级）。"""
        raw = "[audio]http://music.example.com/song.mp3[/audio]"
        out = self._render(raw)
        self.assertIn(raw, out)
        self.assertNotIn("gululu-music-cue", out)

    def test_plain_text_untouched(self):
        self.assertEqual(self._render("普通正文"), "普通正文")


class NgaAttachmentAudioCueTest(unittest.TestCase):
    """NGA 附件音频（<span class="audio"><audio src=…>）转换回归（方案 A）。

    附件音频原始形态是 NGA 接口返回的 HTML 片段（见
    docs/NGA_ATTACHMENT_AUDIO_RESEARCH.md），双端渲染层把它转为骨碌碌
    同款音乐 cue 按钮，复用宿主播放器在线播放。规则与 [audio] 一致：
    仅 https 转换；cue 文本进坐标；非 https 保留原文。
    """

    RAW = (
        '<span class="audio" onclick="audioClick(event)"> <audio '
        'src="https://img.nga.cn/attachments/mon_202410/09/lsQtoqh-1i29Xu.mp3'
        '?filename=01%2e%20a.mp3" onended="audioEnd(event)" '
        'onerror="audioError(event)" ></audio></span>'
    )

    def _render(self, raw: str) -> str:
        return render_content_html(raw, None, lambda u: u)

    def test_attachment_audio_becomes_music_cue(self):
        out = self._render("前文" + self.RAW + "后文")
        self.assertIn('class="gululu-music-cue"', out)
        self.assertIn(
            'data-gululu-music-url="https://img.nga.cn/attachments/mon_202410/09/'
            'lsQtoqh-1i29Xu.mp3?filename=01%2e%20a.mp3"',
            out,
        )
        self.assertIn("gululu-music-kind", out)
        self.assertIn("附件音频", out)
        # 标题显示 filename 查询参数解码后的可读文件名，而不是整段 URL
        self.assertIn("01. a.mp3", out)
        self.assertNotIn('<span class="audio"', out)

    def test_attachment_audio_title_falls_back_to_path_name(self):
        raw = (
            '<span class="audio" onclick="audioClick(event)"> <audio '
            'src="https://img.nga.cn/attachments/mon_1/abc.mp3"></audio></span>'
        )
        out = self._render(raw)
        self.assertIn('data-gululu-music-url="https://img.nga.cn/attachments/mon_1/abc.mp3"', out)
        self.assertIn('<span class="gululu-music-title">abc.mp3</span>', out)

    def test_attachment_audio_cue_text_enters_coordinates(self):
        out = self._render(self.RAW)
        self.assertNotIn("data-textpos-exclude", out)

    def test_attachment_audio_self_closing(self):
        raw = (
            '<span class="audio" onclick="audioClick(event)">'
            '<audio src="https://img.nga.cn/x.mp3" /></span>'
        )
        out = self._render(raw)
        self.assertIn('class="gululu-music-cue"', out)
        self.assertIn('data-gululu-music-url="https://img.nga.cn/x.mp3"', out)

    def test_attachment_audio_http_kept(self):
        raw = (
            '<span class="audio" onclick="audioClick(event)"> <audio '
            'src="http://img.nga.cn/x.mp3"></audio></span>'
        )
        out = self._render(raw)
        self.assertIn(raw, out)
        self.assertNotIn("gululu-music-cue", out)


if __name__ == "__main__":
    unittest.main()
