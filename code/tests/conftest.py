import sqlite3
import sys
from contextlib import closing
from pathlib import Path

import pytest

CODE_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CODE_DIR))
sys.path.insert(0, str(CODE_DIR / "scripts"))

from fastapi.testclient import TestClient  # noqa: E402

from prediction_service import features as F  # noqa: E402
from prediction_service.api import app  # noqa: E402


@pytest.fixture(scope="session")
def client():
    with TestClient(app) as c:
        yield c


@pytest.fixture(scope="session")
def db():
    """Read-only connection to the same database, for checking the API against the raw tables."""
    with closing(sqlite3.connect(F.DB_PATH.resolve().as_uri() + "?mode=ro", uri=True)) as conn:
        yield conn
