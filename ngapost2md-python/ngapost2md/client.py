"""HTTP 客户端：基于 httpx，携带 NGA Cookie 与 UA。

对应 Go 源码 nga/client.go 的 NgaClient。
"""
import httpx

from .config import Config


class NgaClient:
    def __init__(self, cfg: Config):
        self._client = httpx.Client(
            base_url=cfg.base_url,
            headers={
                "Cookie": cfg.cookie_header(),
                "User-Agent": cfg.ua,
            },
            timeout=30.0,
            follow_redirects=True,
        )

    def post_form(self, url: str, data: dict) -> dict:
        resp = self._client.post(url, data=data)
        resp.raise_for_status()
        return resp.json()

    def close(self) -> None:
        self._client.close()
