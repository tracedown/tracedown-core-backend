"""
E2E: API key lifecycle through the gateway HTTP API.

A key is minted by the user it acts as, authenticates the key-authenticated
API (and nothing else), and stops when it is revoked.
Depends on test_service_lifecycle having run first (uses h.admin_token).
"""

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


# Module-level state
_key_id = None
_key = None


def get_tests():
    return [
        test_create_api_key,
        test_list_api_keys,
        test_api_key_authenticates,
        test_revoke_api_key,
        test_delete_api_key,
    ]
