"""
E2E: Webhook creation is refused for an internal target.

This file used to create a webhook pointing at testbin and assert an end-to-end
delivery. That cannot work, and should not: SsrfGuard rejects such a target
twice over — at write time it requires an ``https`` scheme, and at delivery time
it re-resolves the host and refuses any private, loopback, link-local or CGNAT
address. testbin is all of those things, so the old test was asserting behaviour
the product deliberately prevents, and it failed with ``invalid_request_body``.

There is no bypass, by design. Delivery against a genuinely public https
endpoint is therefore out of reach of this stack, and what is worth asserting
here instead is that the guard holds — a regression that silently allowed
internal webhook targets would be an SSRF hole, and this is the cheapest place
to notice it.

Depends on test_service_lifecycle (uses h.admin_token, h.proj_id).
"""

import time

import test_helpers as h

_webhook_id = None
_binding_id = None
_svc_id = None


@h.log_test("Webhook creation refuses a plain-http internal target", reset_db_before=False)
def test_create_webhook_refuses_internal_target():
    if not h.admin_token or not h.proj_id:
        h.skip_test("No admin_token/proj_id -- test_service_lifecycle must run first")

    status, body = h.api("POST", "/api/v1/webhooks", {
        "name": "E2E Webhook",
        "url": "http://testbin:20780/status/200",
        "config": '{"headers": {"X-E2E": "1"}}',
    }, h.admin_token)

    # Write-time guard: the scheme alone is enough to refuse this, before the
    # host is ever resolved.
    assert status == 400, f"Expected the SSRF guard to refuse this, got {status}: {body}"
    print("  refused as expected: an internal http target cannot be registered")


@h.log_test("Webhook creation accepts a public https target", reset_db_before=False)
def test_create_webhook_accepts_public_https():
    """The other side of the guard — it must not refuse everything."""
    global _webhook_id
    if not h.admin_token or not h.proj_id:
        h.skip_test("No admin_token/proj_id -- test_service_lifecycle must run first")

    status, body = h.api("POST", "/api/v1/webhooks", {
        "name": "E2E Webhook",
        "url": "https://example.com/hook",
        "config": '{"headers": {"X-E2E": "1"}}',
    }, h.admin_token)
    assert status in (200, 201), f"Expected 200/201, got {status}: {body}"
    _webhook_id = body["id"]
    assert body.get("config") and "X-E2E" in body["config"], f"config not stored: {body.get('config')}"
    print(f"  created webhook {_webhook_id[:8]}... with config headers")


@h.log_test("Bind webhook to a failing service", reset_db_before=False)
def test_bind_webhook():
    global _svc_id, _binding_id
    if not _webhook_id:
        h.skip_test("No webhook id")

    # Failing service: expects 200 but testbin returns 503 -> assertion fails
    # -> notification emitted. Yearly cron so only /run drives it.
    status, body = h.api("POST", "/api/v1/services",
                         {"projectId": h.proj_id, "name": "E2E WebhookProbe", "schedule": "0 0 1 1 *"},
                         h.admin_token)
    h.assert_status(status, 200, f"body={body}")
    _svc_id = body["id"]

    status, body = h.api("PATCH", f"/api/v1/services/{_svc_id}/script",
                         {"script": 'get("http://testbin:20780/status/503").expect(status: 200)',
                          "version": 1}, h.admin_token)
    h.assert_status(status, 200, f"body={body}")

    status, body = h.api("PATCH", f"/api/v1/services/{_svc_id}/toggle",
                         {"isActive": True}, h.admin_token)
    h.assert_status(status, 200, f"body={body}")

    status, body = h.api("POST", f"/api/v1/webhooks/bindings/service/{_svc_id}",
                         {"webhookId": _webhook_id}, h.admin_token)
    h.assert_status(status, 200, f"body={body}")
    _binding_id = body.get("id")
    print(f"  Bound webhook to service {_svc_id[:8]}... (binding={str(_binding_id)[:8]}...)")

    # The binding should be listable for the resource.
    status, body = h.api("GET", f"/api/v1/webhooks/bindings/service/{_svc_id}", token=h.admin_token)
    h.assert_status(status, 200, f"body={body}")
    items = body if isinstance(body, list) else body.get("items", [])
    assert any(b.get("webhookId") == _webhook_id for b in items), \
        f"binding not listed for the service: {body}"


@h.log_test("Failing probe delivers to the bound webhook", reset_db_before=False)
def test_webhook_delivered_on_failure():
    if not _svc_id:
        h.skip_test("No service id")

    # Drive the failing probe on demand.
    status, _ = h.api("POST", f"/api/v1/services/{_svc_id}/run", token=h.admin_token)
    assert status in (200, 202), f"run failed: {status}"

    # The dispatcher consumes the outbox event, builds the notification and
    # POSTs to the bound webhook. testbin returns 200, so delivery succeeds and
    # the webhook's attempt_count is incremented.
    for i in range(30):
        rows = h.query_db(
            f"SELECT attempt_count FROM webhook_deliveries WHERE id = '{_webhook_id}'"
        )
        attempts = int(rows[0]) if rows and rows[0].isdigit() else 0
        if attempts > 0:
            print(f"  Webhook delivered after {i * 2}s (attempt_count={attempts})")
            log = h.query_db(
                "SELECT channel, status FROM notification_log "
                "WHERE channel = 'webhook' ORDER BY created_at DESC LIMIT 1"
            )
            if log:
                print(f"  notification_log: {log[0]}")
            return
        time.sleep(2)
    raise AssertionError("Webhook attempt_count stayed 0 after 60s -- delivery did not occur")


@h.log_test("Unbind and delete webhook", reset_db_before=False)
def test_cleanup():
    if _binding_id:
        status, body = h.api("DELETE", f"/api/v1/webhooks/bindings/{_binding_id}",
                             token=h.admin_token)
        h.assert_status(status, 200, f"unbind body={body}")
    if _webhook_id:
        status, body = h.api("DELETE", f"/api/v1/webhooks/{_webhook_id}", token=h.admin_token)
        h.assert_status(status, 200, f"delete body={body}")
    print("  Cleaned up webhook + binding")


def get_tests():
    return [
        test_create_webhook_refuses_internal_target,
        test_create_webhook_accepts_public_https,
        test_bind_webhook,
        test_webhook_delivered_on_failure,
        test_cleanup,
    ]
