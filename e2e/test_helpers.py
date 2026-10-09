"""
Shared helpers for e2e tests.

Provides HTTP client, DB/Redis query helpers, assertion utilities,
and the test decorator. Each test module imports this as `h`.
"""

import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://localhost:18080")
COMPOSE_FILE = os.path.join(os.path.dirname(__file__), "docker-compose.e2e.yml")

# ── Mutable state (set by login/setup, shared across tests) ──
admin_token = None
ws_id = None
proj_id = None
svc_id = None

# ── Counters ──
tests_passed = 0
tests_failed = 0
tests_skipped = 0


# ── HTTP ──

def api(method, path, body=None, token=None, headers=None):
    """Make an HTTP request to the gateway API. `headers` are sent as well."""
    url = f"{GATEWAY_URL}{path}"
    extra = headers or {}
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    headers.update(extra)

    data = json.dumps(body).encode() if body else None
    req = urllib.request.Request(url, data=data, headers=headers, method=method)

    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        body_text = e.read().decode() if e.fp else ""
        try:
            return e.code, json.loads(body_text)
        except json.JSONDecodeError:
            return e.code, {"raw": body_text}


# ── DB / Redis ──

def query_db(sql):
    """Execute a SQL query via docker exec on the postgres container."""
    result = subprocess.run(
        ["docker", "compose", "-f", COMPOSE_FILE,
         "exec", "-T", "postgres", "psql", "-U", "tracedown", "-t", "-A", "-c", sql],
        capture_output=True, text=True
    )
    if result.returncode != 0:
        raise RuntimeError(f"DB query failed: {result.stderr}")
    lines = [l.strip() for l in result.stdout.strip().split("\n") if l.strip()]
    return lines


def query_redis(command):
    """Execute a Redis command via docker exec. Command is split on spaces."""
    result = subprocess.run(
        ["docker", "compose", "-f", COMPOSE_FILE,
         "exec", "-T", "redis", "redis-cli"] + command.split(),
        capture_output=True, text=True
    )
    return result.stdout.strip()


def query_redis_args(args):
    """Execute a Redis command via docker exec with pre-split args list."""
    result = subprocess.run(
        ["docker", "compose", "-f", COMPOSE_FILE,
         "exec", "-T", "redis", "redis-cli"] + args,
        capture_output=True, text=True
    )
    return result.stdout.strip()


# ── Assertions ──

class TestFailure(Exception):
    pass


def assert_status(status, expected, context=""):
    if status != expected:
        raise AssertionError(f"Expected status {expected}, got {status}. {context}")


def assert_field(data, field, expected, context=""):
    actual = data.get(field)
    if actual != expected:
        raise AssertionError(f"Expected {field}={expected!r}, got {actual!r}. {context}")


def skip_test(reason):
    global tests_skipped
    tests_skipped += 1
    print(f"  SKIP: {reason}")
    raise _SkipException()


class _SkipException(Exception):
    pass


# ── Test decorator ──

def log_test(name, reset_db_before=True):
    """Decorator that wraps a test function with logging and error handling."""
    def decorator(fn):
        def wrapper():
            global tests_passed, tests_failed
            print(f"\n{'-' * 60}")
            print(f"TEST: {name}")
            print(f"{'-' * 60}")
            try:
                fn()
                print(f"  PASS")
                tests_passed += 1
            except _SkipException:
                pass
            except AssertionError as e:
                print(f"  FAIL: {e}")
                tests_failed += 1
            except TestFailure as e:
                print(f"  FAIL: {e}")
                tests_failed += 1
            except Exception as e:
                print(f"  ERROR: {type(e).__name__}: {e}")
                tests_failed += 1
        wrapper.__name__ = fn.__name__
        wrapper._test = True
        wrapper._reset_db_before = reset_db_before
        return wrapper
    return decorator
