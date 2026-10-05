"""
E2E: API key lifecycle through the gateway HTTP API.

A key is minted by the user it acts as, authenticates the key-authenticated
API (and nothing else), and stops when it is revoked. A write key then builds,
runs, reads and removes a service through that API alone.
Depends on test_service_lifecycle having run first (uses h.admin_token).
"""

import time

import test_helpers as h

# The admin account the suite signs in with (see test_service_lifecycle).
# Minting a key asks for the password again.
_ADMIN_PASSWORD = "Down2trace!"


@h.log_test("Create API key", reset_db_before=False)
def test_create_api_key():
    if not h.admin_token:
        h.skip_test("No admin_token -- test_service_lifecycle must run first")

    # A session alone is not enough to mint a credential.
    status, body = h.api("POST", "/api/v1/me/api-keys",
                         {"name": "E2E Key", "access": "write", "password": "not the password"},
                         h.admin_token)
    h.assert_status(status, 400, f"body={body}")
    assert body.get("error") == "incorrect_password", f"Expected incorrect_password, got {body}"

    status, body = h.api("POST", "/api/v1/me/api-keys",
                         {"name": "E2E Key", "access": "write", "password": _ADMIN_PASSWORD},
                         h.admin_token)
    h.assert_status(status, 201, f"body={body}")

    # The plaintext key is returned only on creation
    assert "key" in body and body["key"], f"Expected key field, got {body}"
    assert body["key"].startswith("td_"), f"Unexpected key shape: {body['key'][:6]}..."
    assert body.get("name") == "E2E Key", f"Expected name='E2E Key', got {body.get('name')}"
    assert body.get("access") == "write", f"Expected access='write', got {body.get('access')}"

    # Store for subsequent tests
    global _key_id, _key
    _key_id = body["id"]
    _key = body["key"]
    print(f"  Created API key id={_key_id[:8]}..., key present=True")


@h.log_test("List API keys -- key field is redacted", reset_db_before=False)
def test_list_api_keys():
    status, body = h.api("GET", "/api/v1/me/api-keys", token=h.admin_token)
    h.assert_status(status, 200, f"body={body}")

    items = body.get("items", [])
    our_key = next((k for k in items if k.get("id") == _key_id), None)
    assert our_key is not None, f"Created key {_key_id} not found in list"

    # The plaintext key must NOT be returned on list
    assert our_key.get("key") is None, f"Expected key=null on list, got {our_key.get('key')}"
    assert our_key.get("state") == "active", f"Expected state='active', got {our_key.get('state')}"

    # The organization's own list shows it too, attributed to its user.
    status, body = h.api("GET", "/api/v1/api-keys", token=h.admin_token)
    h.assert_status(status, 200, f"body={body}")
    assert any(k.get("id") == _key_id for k in body.get("items", [])), \
        f"Created key {_key_id} not found in the organization's list"
    print(f"  List contains {len(items)} key(s), key field correctly null")


@h.log_test("API key authenticates the public API and nothing else", reset_db_before=False)
def test_api_key_authenticates():
    status, body = h.api("GET", "/api/public/v1/key", token=_key)
    h.assert_status(status, 200, f"body={body}")
    assert body.get("id") == _key_id, f"Expected the calling key, got {body}"

    # A key is not a session...
    status, body = h.api("GET", "/api/v1/auth/me", token=_key)
    h.assert_status(status, 401, f"body={body}")
    assert body.get("error") == "session_required", f"Expected session_required, got {body}"
    # ...and a session is not a key.
    status, body = h.api("GET", "/api/public/v1/key", token=h.admin_token)
    h.assert_status(status, 401, f"body={body}")
    assert body.get("error") == "invalid_api_key", f"Expected invalid_api_key, got {body}"
    print("  Key accepted on /api/public, refused on /api/v1; session the reverse")


@h.log_test("Revoke API key", reset_db_before=False)
def test_revoke_api_key():
    status, body = h.api("POST", f"/api/v1/me/api-keys/{_key_id}/revoke",
                         token=h.admin_token)
    h.assert_status(status, 200, f"body={body}")
    assert body.get("ok") is True, f"Expected ok=true, got {body}"

    status, body = h.api("GET", "/api/public/v1/key", token=_key)
    h.assert_status(status, 401, f"body={body}")
    assert body.get("error") == "api_key_revoked", f"Expected api_key_revoked, got {body}"
    print(f"  Revoked key {_key_id[:8]}..., no longer accepted")


@h.log_test("Delete API key", reset_db_before=False)
def test_delete_api_key():
    status, body = h.api("DELETE", f"/api/v1/me/api-keys/{_key_id}",
                         token=h.admin_token)
    h.assert_status(status, 200, f"body={body}")
    assert body.get("ok") is True, f"Expected ok=true, got {body}"

    status, body = h.api("GET", "/api/v1/me/api-keys", token=h.admin_token)
    h.assert_status(status, 200, f"body={body}")
    assert all(k.get("id") != _key_id for k in body.get("items", [])), "Deleted key still listed"
    status, body = h.api("GET", "/api/public/v1/key", token=_key)
    h.assert_status(status, 401, f"body={body}")
    assert body.get("error") == "invalid_api_key", f"Expected invalid_api_key, got {body}"
    print(f"  Deleted key {_key_id[:8]}...")


# ── The key-authenticated API, end to end ──
#
# A write key creates a workspace, a project and a service, runs the service,
# reads the result back and removes all of it again — nothing but the key, no
# session, from the first call to the last.

_PUBLIC = "/api/public/v1"


@h.log_test("Public API: a write key builds a service", reset_db_before=False)
def test_public_api_creates_resources():
    if not h.admin_token:
        h.skip_test("No admin_token -- test_service_lifecycle must run first")
    global _flow_key_id, _flow_key, _flow_read_key_id, _flow_read_key, _flow_ws, _flow_proj, _flow_svc

    status, body = h.api("POST", "/api/v1/me/api-keys",
                         {"name": "E2E Flow Key", "access": "write", "password": _ADMIN_PASSWORD},
                         h.admin_token)
    h.assert_status(status, 201, f"body={body}")
    _flow_key_id, _flow_key = body["id"], body["key"]
    _minted.append(_flow_key_id)

    status, body = h.api("POST", "/api/v1/me/api-keys",
                         {"name": "E2E Flow Read Key", "access": "read", "password": _ADMIN_PASSWORD},
                         h.admin_token)
    h.assert_status(status, 201, f"body={body}")
    _flow_read_key_id, _flow_read_key = body["id"], body["key"]
    _minted.append(_flow_read_key_id)

    status, body = h.api("POST", f"{_PUBLIC}/workspaces", {"name": "E2E Public WS"}, _flow_key)
    h.assert_status(status, 200, f"body={body}")
    _flow_ws = body["id"]

    status, body = h.api("POST", f"{_PUBLIC}/projects",
                         {"workspaceId": _flow_ws, "name": "E2E Public Project"}, _flow_key)
    h.assert_status(status, 200, f"body={body}")
    _flow_proj = body["id"]

    # Yearly cron (Jan 1): only the run asked for below makes a result. Bodies
    # are saved, and the probed call answers with one, so the body read runs.
    status, body = h.api("POST", f"{_PUBLIC}/services",
                         {"projectId": _flow_proj, "name": "E2E Public Service", "schedule": "0 0 1 1 *",
                          "saveResponseBodies": True},
                         _flow_key)
    h.assert_status(status, 200, f"body={body}")
    _flow_svc = body["id"]
    version = body["version"]

    # A run is refused while there is nothing to run.
    status, body = h.api("POST", f"{_PUBLIC}/services/{_flow_svc}/run", token=_flow_key)
    h.assert_status(status, 409, f"body={body}")
    assert body.get("error") == "script_missing", f"Expected script_missing, got {body}"

    status, body = h.api("PATCH", f"{_PUBLIC}/services/{_flow_svc}/script",
                         {"script": 'get("http://testbin:20780/status/200?body=public-api-e2e").expect(status: 200)',
                          "version": version}, _flow_key)
    h.assert_status(status, 200, f"body={body}")

    status, body = h.api("PATCH", f"{_PUBLIC}/services/{_flow_svc}/toggle", {"isActive": True}, _flow_key)
    h.assert_status(status, 200, f"body={body}")
    assert body.get("isActive") is True, f"Expected the service enabled, got {body}"

    # The same lists the dashboard shows, through the key.
    status, body = h.api("GET", f"{_PUBLIC}/services?projectId={_flow_proj}", token=_flow_key)
    h.assert_status(status, 200, f"body={body}")
    assert any(s.get("id") == _flow_svc for s in body.get("items", [])), f"Service not listed: {body}"
    print(f"  Built ws={_flow_ws[:8]}..., project={_flow_proj[:8]}..., service={_flow_svc[:8]}...")


@h.log_test("Public API: a read key reads and changes nothing", reset_db_before=False)
def test_public_api_read_key():
    if not _flow_svc:
        h.skip_test("No service -- the create step must run first")
    status, body = h.api("GET", f"{_PUBLIC}/services/{_flow_svc}", token=_flow_read_key)
    h.assert_status(status, 200, f"body={body}")
    status, body = h.api("POST", f"{_PUBLIC}/services/{_flow_svc}/run", token=_flow_read_key)
    h.assert_status(status, 403, f"body={body}")
    assert body.get("error") == "api_key_read_only", f"Expected api_key_read_only, got {body}"

    # Paging is page/pageSize only, and capped.
    status, body = h.api("GET", f"{_PUBLIC}/workspaces?pageSize=101", token=_flow_read_key)
    h.assert_status(status, 400, f"body={body}")
    status, body = h.api("GET", f"{_PUBLIC}/workspaces?filters=%5B%5D", token=_flow_read_key)
    h.assert_status(status, 400, f"body={body}")
    print("  Read key reads; refused a run, an oversized page and a filter")


@h.log_test("Public API: run a service and read its result", reset_db_before=False)
def test_public_api_run_and_results():
    if not _flow_svc:
        h.skip_test("No service -- the create step must run first")

    status, body = h.api("POST", f"{_PUBLIC}/services/{_flow_svc}/run", token=_flow_key)
    h.assert_status(status, 202, f"body={body}")
    requested_at = body.get("requestedAt")
    assert requested_at, f"Expected requestedAt, got {body}"

    result = None
    for i in range(20):
        status, body = h.api("GET", f"{_PUBLIC}/services/{_flow_svc}/results?pageSize=10&since={requested_at}",
                             token=_flow_key)
        h.assert_status(status, 200, f"body={body}")
        if body.get("items"):
            result = body["items"][0]
            print(f"  Result listed {i * 2}s after the run")
            break
        time.sleep(2)
    assert result is not None, "No result appeared within 40s of the run"

    status, body = h.api("GET", f"{_PUBLIC}/services/{_flow_svc}/results/{result['id']}", token=_flow_key)
    h.assert_status(status, 200, f"body={body}")
    steps = body.get("steps", [])
    assert steps, f"Expected the result to carry its steps, got {body}"

    # A stored body is text in the response, never a link to where it is kept.
    with_body = next((s for s in steps if s.get("hasBody")), None)
    assert with_body is not None, f"The service saves bodies, but no step stored one: {steps}"
    status, body = h.api("GET",
                         f"{_PUBLIC}/services/{_flow_svc}/results/{result['id']}/steps/{with_body['id']}/body",
                         token=_flow_key)
    h.assert_status(status, 200, f"body={body}")
    assert "content" in body and "url" not in body, f"Expected content and no url, got {body}"
    assert "public-api-e2e" in body["content"], f"Expected the probed body, got {body}"
    print("  Step body returned as content")

    status, body = h.api("GET", f"{_PUBLIC}/services/{_flow_svc}/metrics/history", token=_flow_key)
    h.assert_status(status, 200, f"body={body}")
    print(f"  Result {result['id'][:8]}... read with {len(steps)} step(s)")


@h.log_test("Public API: delete what the key built", reset_db_before=False)
def test_public_api_deletes_resources():
    if not (_flow_svc and _flow_proj and _flow_ws):
        h.skip_test("Nothing was built -- the create step must run first")
    for path in (f"/services/{_flow_svc}", f"/projects/{_flow_proj}", f"/workspaces/{_flow_ws}"):
        status, body = h.api("DELETE", f"{_PUBLIC}{path}", token=_flow_key)
        h.assert_status(status, 200, f"{path}: body={body}")
        assert body.get("ok") is True, f"{path}: expected ok=true, got {body}"
        status, body = h.api("GET", f"{_PUBLIC}{path}", token=_flow_key)
        h.assert_status(status, 404, f"{path} after delete: body={body}")

    # The audit log records the key the work came through.
    rows = h.query_db(f"SELECT count(*) FROM org_audit_log WHERE api_key_id = '{_flow_key_id}'")
    assert rows and int(rows[0]) > 0, "Expected audit entries attributed to the key"
    print("  Service, project and workspace deleted through the key")


@h.log_test("Public API: remove the keys the flow minted", reset_db_before=False)
def test_public_api_removes_keys():
    # Runs whatever happened before it: a key minted by a step that then
    # failed must not outlive the suite.
    if not h.admin_token or not _minted:
        h.skip_test("No key was minted")
    for key_id in list(_minted):
        status, body = h.api("DELETE", f"/api/v1/me/api-keys/{key_id}", token=h.admin_token)
        h.assert_status(status, 200, f"body={body}")
        _minted.remove(key_id)
    print("  Flow keys removed")


# Module-level state
_key_id = None
_key = None
_flow_key_id = None
_flow_key = None
_flow_read_key_id = None
_flow_read_key = None
_flow_ws = None
_flow_proj = None
_flow_svc = None
# Every key the flow minted, until it is removed.
_minted = []


def get_tests():
    return [
        test_create_api_key,
        test_list_api_keys,
        test_api_key_authenticates,
        test_revoke_api_key,
        test_delete_api_key,
        test_public_api_creates_resources,
        test_public_api_read_key,
        test_public_api_run_and_results,
        test_public_api_deletes_resources,
        test_public_api_removes_keys,
    ]
