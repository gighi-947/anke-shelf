"""轻量领域事件（B5）：EventBus 订阅/发布与异常隔离。"""
import unittest

from app.events import EventBus


class EventBusTest(unittest.TestCase):
    def test_on_emit_delivers_kwargs(self):
        bus = EventBus()
        got = []
        bus.on("book_updated", lambda book_id: got.append(book_id))
        bus.emit("book_updated", book_id="b1")
        bus.emit("book_updated", book_id="b2")
        self.assertEqual(got, ["b1", "b2"])

    def test_handler_exception_does_not_stop_others(self):
        bus = EventBus()
        got = []

        def boom(**kwargs):
            raise RuntimeError("boom")

        bus.on("ev", boom)
        bus.on("ev", lambda **kwargs: got.append(1))
        bus.emit("ev")
        self.assertEqual(got, [1])

    def test_unrelated_event_ignored(self):
        bus = EventBus()
        got = []
        bus.on("book_updated", lambda **kwargs: got.append(1))
        bus.emit("other")
        self.assertEqual(got, [])


if __name__ == "__main__":
    unittest.main()
