"""骨碌碌公开评论抓取与 EPUB 评论块渲染。"""
from __future__ import annotations

import html
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Callable, Optional


_COMMENT_PAGE_SIZE = 100
_CHILD_PAGE_SIZE = 1000
_COMMENT_WORKERS = 8


def _comment_page(
    request_data: Callable,
    book_id: int,
    *,
    floor_id: Optional[int] = None,
    parent_id: Optional[int] = None,
    check_cancelled: Callable[[], None],
) -> list[dict]:
    path = "/reader/opus/comment/page-children" if parent_id is not None else "/reader/opus/comment/page"
    size = _CHILD_PAGE_SIZE if parent_id is not None else _COMMENT_PAGE_SIZE
    current = 1
    records: list[dict] = []
    while True:
        check_cancelled()
        params = {"opusId": book_id, "current": current, "size": size}
        if floor_id is not None:
            params["floorId"] = floor_id
        if parent_id is not None:
            params["parentId"] = parent_id
        data = request_data("GET", path, params=params)
        if not isinstance(data, dict):
            raise ValueError("骨碌碌评论分页格式错误")
        page_records = data.get("records")
        total = data.get("total")
        if not isinstance(page_records, list) or not isinstance(total, int) or total < 0:
            raise ValueError("骨碌碌评论分页缺少 records 或 total")
        for item in page_records:
            if not isinstance(item, dict) or not isinstance(item.get("id"), int):
                raise ValueError("骨碌碌评论条目格式错误")
            if not isinstance(item.get("content"), str):
                raise ValueError(f"骨碌碌评论 {item.get('id')} 正文格式错误")
            records.append(dict(item))
        if len(records) >= total:
            return records
        if not page_records:
            raise ValueError("骨碌碌评论分页提前结束")
        current += 1


def _comment_scope(
    request_data: Callable,
    book_id: int,
    floor_id: int,
    check_cancelled: Callable[[], None],
) -> list[dict]:
    records = _comment_page(
        request_data,
        book_id,
        floor_id=floor_id or None,
        check_cancelled=check_cancelled,
    )
    with_children = []
    for item in records:
        check_cancelled()
        children_num = item.get("childrenNum") or 0
        if not isinstance(children_num, int) or children_num < 0:
            raise ValueError(f"骨碌碌评论 {item['id']} 的 childrenNum 格式错误")
        if children_num:
            item["childrenComment"] = _comment_page(
                request_data,
                book_id,
                parent_id=item["id"],
                check_cancelled=check_cancelled,
            )
        else:
            item["childrenComment"] = []
        with_children.append(item)
    return with_children


def fetch_comment_scopes(
    request_data: Callable,
    book_id: int,
    floor_ids: list[int],
    *,
    check_cancelled: Optional[Callable[[], None]] = None,
    on_scope: Optional[Callable[[int, int], None]] = None,
) -> dict[int, list[dict]]:
    """并发读取指定评论作用域；floor_id=0 表示作品评论。"""
    check = check_cancelled or (lambda: None)
    scopes = list(dict.fromkeys(floor_ids))
    if not scopes:
        return {}
    if any(not isinstance(floor_id, int) or floor_id < 0 for floor_id in scopes):
        raise ValueError("骨碌碌评论楼层 ID 格式错误")
    comments: dict[int, list[dict]] = {}
    with ThreadPoolExecutor(max_workers=min(_COMMENT_WORKERS, len(scopes))) as executor:
        pending = {
            executor.submit(
                _comment_scope,
                request_data,
                book_id,
                floor_id,
                check,
            ): floor_id
            for floor_id in scopes
        }
        for current, future in enumerate(as_completed(pending), 1):
            check()
            floor_id = pending[future]
            comments[floor_id] = future.result()
            if on_scope is not None:
                on_scope(current, len(scopes))
    return comments


def fetch_comments_by_floor(
    request_data: Callable,
    book_id: int,
    floors: list[dict],
    *,
    report: Callable[[str, int, int, str], None],
    check_cancelled: Callable[[], None],
) -> dict[int, list[dict]]:
    """并发读取作品评论和所有有评论楼层，key 0 表示作品评论。"""
    floor_ids = [
        floor["id"]
        for floor in floors
        if isinstance(floor.get("id"), int)
        and isinstance(floor.get("commentNum"), int)
        and floor["commentNum"] > 0
    ]
    scopes = [0, *floor_ids]
    total = len(scopes)
    report("comments", 0, total, f"正在获取评论 0/{total}")
    return fetch_comment_scopes(
        request_data,
        book_id,
        scopes,
        check_cancelled=check_cancelled,
        on_scope=lambda current, count: report(
            "comments",
            current,
            count,
            f"正在获取评论 {current}/{count}",
        ),
    )


def comment_to_public(comment: dict) -> dict:
    """保留阅读器所需字段，避免把原始用户对象写入本地缓存或前端响应。"""
    content = comment.get("content")
    comment_id = comment.get("id")
    if not isinstance(comment_id, int) or not isinstance(content, str):
        raise ValueError("评论缺少 id 或 content")
    children = comment.get("childrenComment") or []
    if not isinstance(children, list):
        raise ValueError(f"评论 {comment_id} 的 childrenComment 格式错误")
    likes = comment.get("likeNum")
    paragraph_id = comment.get("paragraphId")
    return {
        "id": comment_id,
        "content": content,
        "author": _user_name(comment),
        "reply_user": _user_name(comment, "replyUser", ""),
        "created_at": str(comment.get("createTime") or ""),
        "likes": likes if isinstance(likes, int) else 0,
        "paragraph_id": str(paragraph_id) if paragraph_id not in (None, 0, "", "0") else "",
        "children": [comment_to_public(child) for child in children],
    }


def _text(value: str) -> str:
    return html.escape(value, quote=True).replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br/>")


def _user_name(comment: dict, field: str = "fromUser", fallback: str = "匿名用户") -> str:
    user = comment.get(field)
    if not isinstance(user, dict):
        return fallback
    return str(user.get("nickName") or fallback).strip() or fallback


def _render_comment(comment: dict, *, child: bool = False) -> str:
    content = comment.get("content")
    comment_id = comment.get("id")
    if not isinstance(comment_id, int) or not isinstance(content, str):
        raise ValueError("评论缺少 id 或 content")
    author = html.escape(_user_name(comment), quote=True)
    reply_user = _user_name(comment, "replyUser", "")
    reply = f' 回复 <span class="comment-reply-user">@{html.escape(reply_user, quote=True)}</span>' if reply_user else ""
    created = html.escape(str(comment.get("createTime") or ""), quote=True)
    likes = comment.get("likeNum") if isinstance(comment.get("likeNum"), int) else 0
    paragraph_id = comment.get("paragraphId")
    paragraph_attr = ""
    if paragraph_id not in (None, 0, "", "0"):
        paragraph_attr = f' data-paragraph-id="{html.escape(str(paragraph_id), quote=True)}"'
    children = comment.get("childrenComment") or []
    if not isinstance(children, list):
        raise ValueError(f"评论 {comment_id} 的 childrenComment 格式错误")
    child_html = "".join(_render_comment(item, child=True) for item in children)
    classes = "gululu-comment gululu-comment-reply" if child else "gululu-comment"
    replies = f'<div class="gululu-comment-replies">{child_html}</div>' if child_html else ""
    return (
        f'<article class="{classes}" data-comment-id="{comment_id}"{paragraph_attr}>'
        f'<header class="gululu-comment-head"><strong>{author}</strong>{reply}'
        f'<span>{created} · 赞 {likes}</span></header>'
        f'<div class="gululu-comment-text">{_text(content)}</div>'
        f"{replies}"
        "</article>"
    )


def render_comment_block(comments: list[dict], *, label: str, opus: bool = False) -> str:
    if not comments:
        return ""
    total = sum(1 + len(comment.get("childrenComment") or []) for comment in comments)
    articles = "".join(_render_comment(comment) for comment in comments)
    classes = "gululu-comments gululu-opus-comments" if opus else "gululu-comments"
    return (
        f'<details class="{classes}" data-comment-count="{total}">'
        f"<summary>{html.escape(label)} {total}</summary>"
        f'<div class="gululu-comment-list">{articles}</div></details>'
    )
