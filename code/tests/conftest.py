import sys
from pathlib import Path

import pytest

CODE_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CODE_DIR))

from fastapi.testclient import TestClient  # noqa: E402

from prediction_service.api import app  # noqa: E402


@pytest.fixture(scope="session")
def client():
    with TestClient(app) as c:
        yield c
