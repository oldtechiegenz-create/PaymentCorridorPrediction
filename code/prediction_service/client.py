"""HTTP client for the prediction API, for the CorridorIQ console and other services."""
import requests


class PredictionAPIError(RuntimeError):
    pass


class PredictionClient:
    def __init__(self, base_url: str = "http://127.0.0.1:8000", timeout: float = 10.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.session = requests.Session()

    def _call(self, method: str, path: str, **kw) -> dict:
        r = self.session.request(method, self.base_url + path, timeout=self.timeout, **kw)
        if not r.ok:
            raise PredictionAPIError(f"{method} {path} -> {r.status_code}: {r.text}")
        return r.json()

    def health(self) -> dict:
        return self._call("GET", "/health")

    def rank_routes(self, corridor_id: str, bene_bank_id: str, client_type: str, amount_usd: float,
                    priority: int, released_at: str, has_deadline: bool = False) -> dict:
        """released_at: ISO 8601 with time zone, e.g. '2026-04-01T13:01:00Z'."""
        return self._call("POST", "/v1/routes/rank", json={
            "corridor_id": corridor_id, "bene_bank_id": bene_bank_id, "client_type": client_type,
            "amount_usd": amount_usd, "priority": priority, "released_at": released_at,
            "has_deadline": has_deadline,
        })
