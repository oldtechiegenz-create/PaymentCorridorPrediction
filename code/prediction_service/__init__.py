"""CorridorIQ prediction service (MVP 1 module 1C): route ranking, time-to-credit and cost models."""
import sys
from pathlib import Path

# engine.py and reference.py import each other as top-level modules, so their folder must be on the path.
_SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))
