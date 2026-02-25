"""
dependencies.py
================
Shared application-level singletons imported by routers.
Keeps routers decoupled from main.py to avoid circular import issues.
"""

from store import DataStore

# Single shared instance — all routers import `store` from here
store = DataStore()
