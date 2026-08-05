"""数据模型：对应 Go 源码 nga/nga.go 中的 Floor / Floors / Tiezi。"""
from dataclasses import dataclass, field


@dataclass
class Floor:
    lou: int = -1          # 楼层号，-1 表示被抽楼
    pid: int = 0
    timestamp: int = 0
    username: str = ""
    ip_location: str = ""
    user_id: int = 0
    content: str = ""
    like_num: int = 0
    append_pid: list = field(default_factory=list)
    comments: list = field(default_factory=list)  # list[Floor]
    raw_content: str = ""                          # NGA 原始 content（未转 Markdown），供 HTML/EPUB 用


@dataclass
class Tiezi:
    tid: int = 0
    author_id: int = 0
    title: str = ""
    title_folder_safe: str = ""
    category: str = ""
    username: str = ""
    user_id: int = 0
    web_max_page: int = 0
    local_max_page: int = 0
    local_max_floor: int = 0
    floor_count: int = 0
    floors: list = field(default_factory=list)    # list[Floor]，下标对齐楼层号
    hot_posts: list = field(default_factory=list)  # list[Floor]
    timestamp: int = 0
    version: str = ""
    assets: dict = field(default_factory=dict)    # 短哈希 -> 文件名
    created_time: str = ""
    updated_time: str = ""
    folder_name: str = ""                          # 本次输出文件夹名（init 时计算）
    max_lou: int = -1                              # max_floors 限制下的最大楼层号，-1=不限制
