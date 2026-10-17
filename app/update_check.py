"""进书更新检查服务：NGA 断点页估算 / 骨碌碌增量基线精确比对。

三态结果（对齐 Android service/BookUpdateChecker.kt）：
- available：检测到新楼层（NGA 为页数估算 approximate=True；骨碌碌为精确值）；
- none：已是最新；
- unavailable：无法检查（非可更新书型/未配置凭据/无断点或基线/网络失败），
  显式语义不吞错——异常记日志后归入 unavailable，调用方静默处理。

检查是只读探测：**绝不写 process.ini / 书目录 / 增量基线**（不调
download/save_process_info/write_baseline）。结果写 UpdateCheckStore
（书架徽标与阅读器红点共用）：available→set_available(n)；none→清条目；
unavailable 不动 store（瞬态失败不清既有徽标）。仅 available/none 记
会话节流（unavailable 允许下次 check_all 重试）。

分派：书架记录 nga_tid>0 → NGA；path 在骨碌碌库目录下 → 骨碌碌；
否则 unavailable（纯 EPUB 无更新概念）。
"""
import configparser
import logging
import re
import threading
from pathlib import Path
from typing import Callable, Optional

from .gululu_client import GululuClient
from .gululu_update import BaselineOk, load_baseline, plan_incremental_update
from .nga_config import ensure_nga_config, load_nga_config
from .nga_service import _import_nga
from .paths import gululu_library_dir, nga_library_dir
from .update_checks import UpdateCheckStore

log = logging.getLogger("update_check")

STATE_AVAILABLE = "available"
STATE_NONE = "none"
STATE_UNAVAILABLE = "unavailable"

NGA_FLOORS_PER_PAGE = 20
_FOLDER_AUTHOR_RE = re.compile(r"^\d+\((\d+)\)")


def _result(state: str, new_floors: int = 0, approximate: bool = False) -> dict:
    return {"state": state, "new_floors": int(new_floors), "approximate": bool(approximate)}


def _gululu_source_id(path: str, library_dir: Path) -> int:
    """书架记录路径 → 骨碌碌 sourceId（非骨碌碌书返回 0）。

    对齐 Android BookUpdateChecker.gululuSourceIdFor：只认骨碌碌库目录
    下的 <sourceId>/post.epub（父目录名即 sourceId）。
    """
    if not path:
        return 0
    try:
        book = Path(path).resolve()
        library = Path(library_dir).resolve()
    except OSError:
        return 0
    if not book.is_relative_to(library):
        return 0
    name = book.parent.name
    return int(name) if name.isdigit() else 0


def _read_nga_breakpoint(process_ini: Path) -> Optional[tuple[int, int]]:
    """读 process.ini 断点：[local] max_floor / max_page。

    文件缺失或字段不可解析返回 None——无断点基线不猜（对齐 Android
    NgaDownloader.checkUpdate 的语义）。字段来源：ngapost2md nga.py
    save_process_info 写入（[local] max_floor/max_page + [info]
    created_time/updated_time，共四字段）。
    """
    if not process_ini.is_file():
        return None
    ini = configparser.ConfigParser()
    ini.optionxform = str
    try:
        ini.read(process_ini, encoding="utf-8")
        max_floor = int(ini.get("local", "max_floor", fallback="-1"))
        max_page = int(ini.get("local", "max_page", fallback="0"))
    except (configparser.Error, ValueError, OSError):
        return None
    return max_floor, max_page


class UpdateCheckService:
    """进书更新统一检查入口 + 书架批量扫描（单后台线程，防重入）。"""

    def __init__(
        self,
        shelf,
        store: Optional[UpdateCheckStore] = None,
        *,
        gululu_client_factory: Callable[[], GululuClient] = None,
    ):
        self._shelf = shelf
        self._store = store or UpdateCheckStore()
        self._gululu_client_factory = gululu_client_factory or GululuClient
        self._lock = threading.Lock()
        self._sweep_running = False

    @property
    def store(self) -> UpdateCheckStore:
        return self._store

    # ---------- 单书检查 ----------

    def check_book(self, book_id: str) -> dict:
        """单书轻量检查：{"state", "new_floors", "approximate"}。

        结果写 store（available/none）；unavailable 不动 store。
        """
        rec = self._shelf.get(book_id) if self._shelf is not None else None
        if rec is None:
            return _result(STATE_UNAVAILABLE)
        try:
            if int(rec.nga_tid or 0) > 0:
                result = self._check_nga(rec)
            else:
                source_id = _gululu_source_id(rec.path, gululu_library_dir())
                if source_id > 0:
                    result = self._check_gululu(source_id)
                else:
                    result = _result(STATE_UNAVAILABLE)
        except Exception:  # noqa: BLE001 - 显式失败语义：检查失败归入 unavailable
            log.exception("进书更新检查失败：%s", book_id)
            return _result(STATE_UNAVAILABLE)
        if result["state"] in (STATE_AVAILABLE, STATE_NONE):
            self._store.set_available(book_id, result["new_floors"])
            self._store.mark_checked(book_id)
        return result

    def _check_nga(self, rec) -> dict:
        """NGA 轻量检查：只拉断点页，新楼 = 页内 lou>max_floor 数 + 超出页数×20。

        approximate=True（页数估算，非精确值）。绝不写本地状态。
        """
        cfg = load_nga_config()
        if not cfg["configured"]:
            return _result(STATE_UNAVAILABLE)
        folder = Path(rec.path).parent.name
        m = _FOLDER_AUTHOR_RE.match(folder)
        author_id = int(m.group(1)) if m else 0
        local_state = _read_nga_breakpoint(nga_library_dir() / folder / "process.ini")
        if local_state is None:
            return _result(STATE_UNAVAILABLE)
        max_floor, max_page = local_state
        if max_page >= 1:
            page_no = max_page
        elif max_floor >= 0:
            # process.ini 缺页号：按每页 20 楼估断点页
            page_no = max_floor // NGA_FLOORS_PER_PAGE + 1
        else:
            page_no = 1

        client_mod, config_mod, nga_mod, Tiezi = _import_nga()
        download_cfg = config_mod.load_config(str(ensure_nga_config()))
        download_cfg.thread = 1
        download_cfg.page_download_limit = 0  # 取真实 totalPage，不做页数截断
        download_cfg.max_floors = 0
        download_cfg.no_images = True
        download_cfg.no_media = True
        download_cfg.epub_enabled = False
        download_cfg.output_path = str(nga_library_dir())
        client = client_mod.NgaClient(download_cfg)
        try:
            nga_mod.init_nga(client, download_cfg)
            tiezi = Tiezi(tid=int(rec.nga_tid), author_id=author_id)
            nga_mod.page(tiezi, page_no)  # 只拉该页；不调 download，不落盘
        finally:
            client.close()
        on_page = sum(1 for f in tiezi.floors if f.lou >= 0 and f.lou > max_floor)
        extra_pages = max(0, int(tiezi.web_max_page) - page_no)
        new_floors = on_page + extra_pages * NGA_FLOORS_PER_PAGE
        if new_floors > 0:
            return _result(STATE_AVAILABLE, new_floors, approximate=True)
        return _result(STATE_NONE)

    def _check_gululu(self, source_id: int) -> dict:
        """骨碌碌精确检查：增量基线 vs 远端索引的 append-only 前缀比对。

        基线缺失/损坏（旧书走手动更新的一次性迁移）与远端冲突/网络异常
        均 unavailable，不做提示。
        """
        folder = gululu_library_dir() / str(source_id)
        baseline = load_baseline(folder / "snapshot.json", source_id)
        if not isinstance(baseline, BaselineOk):
            return _result(STATE_UNAVAILABLE)
        with self._gululu_client_factory() as client:
            remote = client.fetch_index(source_id)
        plan = plan_incremental_update(baseline, remote)
        if plan.new_floor_ids:
            return _result(STATE_AVAILABLE, len(plan.new_floor_ids), approximate=False)
        return _result(STATE_NONE)

    # ---------- 书架批量扫描 ----------

    def check_all(self) -> dict:
        """后台顺序检查（可更新书 ∩ 自动检查开 ∩ 本会话未查），结果写 store。

        单后台线程防重入：已在跑返回 {"started": False}。
        """
        with self._lock:
            if self._sweep_running:
                return {"started": False}
            self._sweep_running = True

        def sweep() -> None:
            try:
                library = gululu_library_dir()
                for rec in self._shelf.list_books():
                    updatable = (
                        int(rec.nga_tid or 0) > 0
                        or _gululu_source_id(rec.path, library) > 0
                    )
                    if not updatable:
                        continue
                    if not self._store.is_auto_check_enabled(rec.id):
                        continue
                    if self._store.checked_this_session(rec.id):
                        continue
                    self.check_book(rec.id)
            except Exception:  # noqa: BLE001 - 后台线程兜底，单本失败已在 check_book 内显式化
                log.exception("批量更新检查线程异常")
            finally:
                with self._lock:
                    self._sweep_running = False

        threading.Thread(target=sweep, daemon=True, name="update-check-all").start()
        return {"started": True}
