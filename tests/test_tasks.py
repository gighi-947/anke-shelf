"""任务基础设施（B7）：lane 单飞、取消、进度、多 lane 并行。"""
import threading
import time
import unittest

from app.tasks import TaskManager, TaskProgress, TaskStatus


class TaskManagerTest(unittest.TestCase):
    def test_same_lane_single_flight(self):
        tm = TaskManager(lanes={"nga": 1})
        entered = threading.Event()
        release = threading.Event()

        def task(report):
            entered.set()
            release.wait(5)

        t = threading.Thread(target=lambda: tm.run("nga", "t1", task), daemon=True)
        t.start()
        entered.wait(5)
        self.assertFalse(tm.start("nga", "t2"))  # lane 被占用
        release.set()
        t.join(5)
        self.assertTrue(tm.start("nga", "t3"))
        tm.finish("nga", "t3")

    def test_different_lanes_parallel(self):
        tm = TaskManager(lanes={"a": 1, "b": 1})
        started = threading.Event()
        results = []

        def task_a(report):
            started.set()
            time.sleep(0.2)
            results.append("a")

        def task_b(report):
            started.wait(5)
            results.append("b")

        ta = threading.Thread(target=lambda: tm.run("a", "ta", task_a), daemon=True)
        tb = threading.Thread(target=lambda: tm.run("b", "tb", task_b), daemon=True)
        ta.start()
        tb.start()
        ta.join(5)
        tb.join(5)
        self.assertEqual(sorted(results), ["a", "b"])

    def test_cancel_and_progress(self):
        tm = TaskManager(lanes={"x": 1})
        seen = []

        def task(report):
            for i in range(5):
                report(TaskProgress(current=i, total=5, stage="work"))
                seen.append(i)
                if i == 1:
                    tm.cancel("tx")
                time.sleep(0.01)

        status = tm.run("x", "tx", task, on_progress=lambda p: seen.append(("p", p.current)))
        self.assertEqual(status, TaskStatus.CANCELLED)
        self.assertIn(("p", 0), seen)
        self.assertIn(0, seen)

    def test_pending_when_lane_busy(self):
        tm = TaskManager(lanes={"x": 1})
        tm.start("x", "busy")
        status = tm.run("x", "other", lambda report: None)
        self.assertEqual(status, TaskStatus.PENDING)
        tm.finish("x", "busy")


if __name__ == "__main__":
    unittest.main()
