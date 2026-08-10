"""统一日志字段（B7）：log_event 输出结构行。"""
import io
import logging
import unittest

from app.logutil import log_event


class LogUtilTest(unittest.TestCase):
    def test_log_event_format(self):
        buf = io.StringIO()
        handler = logging.StreamHandler(buf)
        logger = logging.getLogger("test.logutil")
        logger.setLevel(logging.INFO)
        logger.addHandler(handler)
        try:
            log_event(logger, "search", "index_built", book_id="b1", chapters=3, skipped=None)
        finally:
            logger.removeHandler(handler)
        line = buf.getvalue().strip()
        self.assertIn("search index_built", line)
        self.assertIn("book_id=b1", line)
        self.assertIn("chapters=3", line)
        self.assertNotIn("skipped=None", line)


if __name__ == "__main__":
    unittest.main()
