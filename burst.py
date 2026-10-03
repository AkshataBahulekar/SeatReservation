#!/usr/bin/env python3
import argparse
import base64
import concurrent.futures
import hashlib
import hmac
import json
import sys
import time
import urllib.error
import urllib.request
import uuid
from collections import Counter


def request(base_url, path, method="GET", payload=None, headers=None):
    body = None if payload is None else json.dumps(payload).encode()
    request_headers = {"Content-Type": "application/json"}
    if headers:
        request_headers.update(headers)
    req = urllib.request.Request(
        base_url.rstrip("/") + path, data=body, headers=request_headers, method=method
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as response:
        return response.code, response.read()
    except Exception as error:
        return 599, str(error).encode()


def token_for(user, secret):
    payload = user.encode()
    encoded = base64.urlsafe_b64encode(payload).rstrip(b"=")
    signature = hmac.new(secret.encode(), encoded, hashlib.sha256).digest()
    signed = base64.urlsafe_b64encode(signature).rstrip(b"=")
    return encoded.decode() + "." + signed.decode()


def main():
    parser = argparse.ArgumentParser(description="Concurrent hot-seat reservation burst")
    parser.add_argument("base_url")
    parser.add_argument("--requests", type=int, default=500)
    parser.add_argument("--admin-token", default="dev-admin-token")
    parser.add_argument("--token-secret", default="local-development-secret-change-me")
    args = parser.parse_args()
    if args.requests < 1 or args.requests > 20_000:
        parser.error("--requests must be between 1 and 20000")

    show_request = {
        "name": "burst-" + uuid.uuid4().hex[:12],
        "seats": ["HOT-SEAT", "OTHER-1", "OTHER-2", "OTHER-3"],
        "price_paise": 25000,
    }
    status, body = request(
        args.base_url, "/shows", "POST", show_request,
        {"X-Admin-Token": args.admin_token},
    )
    if status != 201:
        print(f"Could not create show: HTTP {status} {body.decode(errors='replace')}", file=sys.stderr)
        return 1
    show_id = json.loads(body)["id"]

    def reserve(index):
        user = "burst-" + str(index)
        result, response = request(
            args.base_url,
            f"/shows/{show_id}/reserve",
            "POST",
            {"seats": ["HOT-SEAT"], "idempotency_key": "hot-seat-" + str(index)},
            {"Authorization": "Bearer " + token_for(user, args.token_secret)},
        )
        try:
            error = json.loads(response).get("error", "")
        except (ValueError, AttributeError):
            error = ""
        return index, result, error, response

    started = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=min(args.requests, 500)) as pool:
        results = list(pool.map(reserve, range(args.requests)))
    elapsed = time.monotonic() - started

    summary = Counter()
    five_xx = []
    for _, status, error, response in results:
        if status == 201:
            summary["confirmed"] += 1
        elif status == 409:
            summary["declined/" + (error or "unknown")] += 1
        elif status >= 500:
            summary["5xx"] += 1
            if len(five_xx) < 5:
                five_xx.append(response.decode(errors="replace"))
        else:
            summary[f"unexpected-http-{status}"] += 1

    winner = next((result for result in results if result[1] == 201), None)
    idempotency_checks = {"replay_http_status": None, "different_body_http_status": None}
    if winner:
        index, _, _, original_body = winner
        headers = {"Authorization": "Bearer " + token_for("burst-" + str(index), args.token_secret)}
        replay_status, replay_body = request(
            args.base_url,
            f"/shows/{show_id}/reserve",
            "POST",
            {"seats": ["HOT-SEAT"], "idempotency_key": "hot-seat-" + str(index)},
            headers,
        )
        idempotency_checks["replay_http_status"] = replay_status
        different_status, _ = request(
            args.base_url,
            f"/shows/{show_id}/reserve",
            "POST",
            {"seats": ["OTHER-1"], "idempotency_key": "hot-seat-" + str(index)},
            headers,
        )
        idempotency_checks["different_body_http_status"] = different_status
        try:
            same_reservation = json.loads(original_body)["reservation_id"] == json.loads(replay_body)["reservation_id"]
        except (ValueError, KeyError, TypeError):
            same_reservation = False
        idempotency_checks["same_reservation"] = same_reservation

    status, body = request(args.base_url, f"/shows/{show_id}")
    if status != 200:
        print(f"Could not read show state: HTTP {status} {body.decode(errors='replace')}", file=sys.stderr)
        return 1
    state = json.loads(body)
    total = state["available"] + state["held"] + state["confirmed"]
    print(json.dumps({
        "requests": args.requests,
        "elapsed_seconds": round(elapsed, 3),
        "outcomes": dict(sorted(summary.items())),
        "idempotency_checks": idempotency_checks,
        "reconciliation": {
            "show_id": show_id,
            "available": state["available"],
            "held": state["held"],
            "confirmed": state["confirmed"],
            "total_seats": state["total_seats"],
            "counts_reconcile": total == state["total_seats"],
        },
    }, indent=2))
    if five_xx:
        print("Sample 5xx responses:", five_xx, file=sys.stderr)
    if (summary["confirmed"] != 1 or summary["5xx"] or
            sum(count for name, count in summary.items() if name.startswith("unexpected-")) or
            total != state["total_seats"] or state["confirmed"] != 1 or
            idempotency_checks["replay_http_status"] != 200 or
            idempotency_checks["different_body_http_status"] != 409 or
            not idempotency_checks.get("same_reservation")):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
