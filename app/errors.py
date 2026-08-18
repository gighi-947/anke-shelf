"""结构化 API 错误（B6）：handler 抛出，server 统一转 HTTP 错误响应。"""


class ErrorCode:
    BOOK_NOT_FOUND = "BOOK_NOT_FOUND"
    BOOK_INVALID = "BOOK_INVALID"
    SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE"
    ANNOTATION_INVALID = "ANNOTATION_INVALID"
    EXPORT_FAILED = "EXPORT_FAILED"
    STORAGE_ERROR = "STORAGE_ERROR"


class ApiError(Exception):
    """API 层业务错误：由 server 捕获并转为 HTTP 4xx/5xx 响应。

    message 保持用户可读；code 供前端按 error_code 做差异化展示。
    """

    def __init__(self, code: str, message: str, status: int = 400):
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status
