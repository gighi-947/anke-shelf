"""骨碌碌全能助手正文协议与秘密解锁回归测试。"""
import unittest

from app.gululu_assistant import (
    GululuSecretError,
    decrypt_cryptojs_secret,
    prepare_assistant_nodes,
)
from app.gululu_epub import render_ast


CRYPTOJS_CIPHER = (
    "U2FsdGVkX1+5H7Gx48HorblxhULBPlXtE11y6qTOMa4caaekW4/fZFQlbBlH2/p8"
)


def _paragraph(text: str) -> dict:
    return {
        "type": "paragraph",
        "attrs": {},
        "content": [{"type": "text", "text": text, "marks": [], "content": []}],
    }


class GululuAssistantProtocolTest(unittest.TestCase):
    def test_secret_and_clue_become_inert_semantic_markers(self):
        nodes = [
            _paragraph("\u200b<发现秘密>[炉心]薪火-63299</发现秘密>\u200b"),
            _paragraph(f"前缀<秘密>[炉心]{CRYPTOJS_CIPHER}</秘密>后缀"),
        ]

        rendered = render_ast(prepare_assistant_nodes(nodes))

        self.assertIn('data-gululu-secret-password="薪火-63299"', rendered)
        self.assertIn(f'data-gululu-secret-cipher="{CRYPTOJS_CIPHER}"', rendered)
        self.assertIn('data-gululu-secret-title="炉心"', rendered)
        self.assertIn("前缀", rendered)
        self.assertIn("后缀", rendered)
        self.assertNotIn("&lt;秘密&gt;", rendered)
        self.assertNotIn("&lt;发现秘密&gt;", rendered)

    def test_text_fold_protocol_uses_author_title_and_hides_raw_tags(self):
        nodes = [
            _paragraph("<折叠>[系统介绍]"),
            _paragraph("炉温与煤炭储备"),
            _paragraph("</折叠结束>"),
        ]

        rendered = render_ast(prepare_assistant_nodes(nodes))

        self.assertIn('<details class="gululu-fold gululu-assistant-fold">', rendered)
        self.assertIn("<summary>系统介绍</summary>", rendered)
        self.assertIn("炉温与煤炭储备", rendered)
        self.assertNotIn("&lt;折叠&gt;", rendered)
        self.assertNotIn("折叠结束", rendered)

    def test_malformed_or_unclosed_protocol_is_explicit(self):
        rendered = render_ast(prepare_assistant_nodes([
            _paragraph("<折叠>"),
            _paragraph("没有结束标记"),
        ]))
        self.assertIn("折叠指令缺少标题", rendered)
        self.assertIn("没有结束标记", rendered)

    def test_jump_floor_and_known_sensitive_nodes_are_not_unknown_placeholders(self):
        rendered = render_ast(
            [
                {
                    "type": "jumpFloorComponent",
                    "attrs": {"floorNumber": "7", "description": "跳过系统介绍"},
                    "content": [],
                },
                {"type": "sensitive", "attrs": {}, "content": []},
            ],
            jump_floor_resolver=lambda floor: f"chapter.xhtml#floor-{floor * 10}",
        )
        self.assertIn('href="chapter.xhtml#floor-70"', rendered)
        self.assertIn("跳过系统介绍", rendered)
        self.assertIn("敏感内容不可用", rendered)
        self.assertNotIn("暂不支持的内容", rendered)


class CryptoJsSecretCompatibilityTest(unittest.TestCase):
    def test_decrypts_cryptojs_aes_passphrase_format(self):
        self.assertEqual(
            decrypt_cryptojs_secret(CRYPTOJS_CIPHER, "薪火-63299"),
            "风雪之后，炉火仍在。",
        )

    def test_wrong_password_and_invalid_payload_are_explicit(self):
        with self.assertRaisesRegex(GululuSecretError, "密码错误或秘密数据损坏"):
            decrypt_cryptojs_secret(CRYPTOJS_CIPHER, "错误密码")
        with self.assertRaisesRegex(GululuSecretError, "秘密数据格式错误"):
            decrypt_cryptojs_secret("not-base64", "密码")


if __name__ == "__main__":
    unittest.main()
