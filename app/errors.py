"""结构化 API 错误码（B6）：与错误消息并存，前端可据 error_code 决定展示。"""


class ErrorCode:
    BOOK_NOT_FOUND = "BOOK_NOT_FOUND"
    BOOK_INVALID = "BOOK_INVALID"
    SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE"
    ANNOTATION_INVALID = "ANNOTATION_INVALID"
    EXPORT_FAILED = "EXPORT_FAILED"
    STORAGE_ERROR = "STORAGE_ERROR"


def api_error(code: str, message: str) -> dict:
    """向后兼容的错误响应：message 保持原样，新增 ok/error_code。"""
    return {"ok": False, "error": message, "error_code": code}
