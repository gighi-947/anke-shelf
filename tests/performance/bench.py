"""Windows 端性能基准（B8）。

运行：python -m tests.performance.bench
输出：tests/performance/baseline.json（章节文本提取 / 原生书打开读取 /
      搜索索引构建+查询，含耗时与 tracemalloc 峰值）。
"""
import json
import time
import tracemalloc
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent.parent
FIXTURE = PROJECT / "contracts" / "fixtures" / "native-book" / "basic-nga"


def measure(fn, repeat: int = 3) -> dict:
    best_ms = None
    peak_mb = 0.0
    for _ in range(repeat):
        tracemalloc.start()
        t0 = time.perf_counter()
        fn()
        dt = (time.perf_counter() - t0) * 1000
        _, peak = tracemalloc.get_traced_memory()
        tracemalloc.stop()
        best_ms = dt if best_ms is None else min(best_ms, dt)
        peak_mb = max(peak_mb, peak / 1048576)
    return {"ms": round(best_ms, 3), "peak_mb": round(peak_mb, 2)}


def main() -> None:
    from app.native_book import NativeBook
    from app.search import SearchService
    from app.text import extract_dom_text

    chapters = {
        p.name: p.read_text(encoding="utf-8")
        for p in sorted((FIXTURE / "chapters").glob("*.xhtml"))
    }
    results: dict = {
        "extract_dom_text": {
            name: measure(lambda html=html: extract_dom_text(html))
            for name, html in chapters.items()
        }
    }

    def open_and_read() -> None:
        book = NativeBook(str(FIXTURE)).open()
        for i in range(len(book.chapters)):
            book.chapter_text(i)
        book.close()

    results["native_book_open_read"] = measure(open_and_read)

    def search_build_and_query() -> None:
        book = NativeBook(str(FIXTURE)).open()
        svc = SearchService()
        svc.ensure_index(book)
        svc.search(book.id, "楼")
        book.close()

    results["search_build_and_query"] = measure(search_build_and_query)

    out = {
        "version": 1,
        "fixture": "native-book/basic-nga",
        "results": results,
    }
    dest = Path(__file__).resolve().parent / "baseline.json"
    dest.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(out, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
