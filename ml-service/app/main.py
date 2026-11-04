"""
AquaTrack - FastAPI Application Mount
Re-exports the FastAPI application from root main.py for compatibility
with commands like 'uvicorn app.main:app'.
"""

import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

# Import application from main.py
from main import app

__all__ = ["app"]
