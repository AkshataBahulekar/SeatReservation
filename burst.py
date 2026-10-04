#!/usr/bin/env python3
import argparse
import base64
import concurrent.futures
import http.client
import hashlib
import hmac
import json
import sys
import time
import urllib.error
import urllib.request
import uuid
from collections import Counter


def request(base_url, path, method="GET", payload=None, headers=None, timeout=600, retries=0):
    body = None if payload is None else json.dumps(payload).encode()
    request_headers = {"Content-Type": "application/json"}
    if headers:
        request_headers.update(headers)
    for attempt in range(retries + 1):
        req = urllib.request.Request(
            base_url.rstrip("/") + path, data=body, headers=request_headers, method=method
        )
        try:
            with urllib.request.urlopen(req, timeout=timeout) as response:
                status = response.status
                try:
                    response_body = response.read()
                except (OSError, http.client.HTTPException) as error:
                    if attempt < retries:
                        time.sleep(0.25 * (2 ** attempt))
                        continue
                    return 599, f"Incomplete HTTP {status} response: {error}".encode()
                return status, response_body
        except urllib.error.HTTPError as response:
            try:
                response_body = response.read()
            except (OSError, http.client.HTTPException) as error:
                response.close()
                if attempt < retries:
                    time.sleep(0.25 * (2 ** attempt))
                    continue
                return 599, f"Incomplete HTTP {response.code} response: {error}".encode()
            return response.code, response_body
        except (OSError, http.client.HTTPException) as error:
            if attempt < retries:
                time.sleep(0.25 * (2 ** attempt))
                continue
            return 599, str(error).encode()
    return 599, b"Request retries exhausted"


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
    parser.add_argument("--workers", type=int, default=100,
                        help="maximum simultaneous requests (default: 100)")
    parser.add_argument("--timeout", type=int, default=600,
                        help="per-request timeout in seconds (default: 600)")
    args = parser.parse_args()
    if args.requests < 1 or args.requests > 20_000:
        parser.error("--requests must be between 1 and 20000")
    if args.workers < 1 or args.timeout < 1:
        parser.error("--workers and --timeout must be positive")

    run_id = uuid.uuid4().hex
    timings = {}
    total_started = time.monotonic()
    show_request = {
        "name": "burst-" + run_id[:12],
        "seats": ["HOT-SEAT", "OTHER-1", "OTHER-2", "OTHER-3"],
        "price_paise": 25000,
    }
    phase_started = time.monotonic()
    status, body = request(
        args.base_url, "/shows", "POST", show_request,
        {"X-Admin-Token": args.admin_token},
        timeout=args.timeout,
    )
    timings["create_show_seconds"] = round(time.monotonic() - phase_started, 3)
    if status != 201:
        print(f"Could not create show: HTTP {status} {body.decode(errors='replace')}", file=sys.stderr)
        return 1
    show_id = json.loads(body)["id"]

    def reserve(index):
        user = f"burst-{run_id}-{index}"
        idempotency_key = f"{run_id}-hot-seat-{index}"
        request_started = time.monotonic()
        result, response = request(
            args.base_url,
            f"/shows/{show_id}/reserve",
            "POST",
            {"seats": ["HOT-SEAT"], "idempotency_key": idempotency_key},
            {"Authorization": "Bearer " + token_for(user, args.token_secret)},
            timeout=args.timeout,
            retries=2,
        )
        request_elapsed = time.monotonic() - request_started
        try:
            error = json.loads(response).get("error", "")
        except (ValueError, AttributeError):
            error = ""
        return index, result, error, response, request_elapsed

    started = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=min(args.requests, args.workers)) as pool:
        results = list(pool.map(reserve, range(args.requests)))
    elapsed = time.monotonic() - started
    timings["reservation_burst_seconds"] = round(elapsed, 3)
    request_latencies = sorted(result[4] for result in results)

    def percentile(values, fraction):
        return values[min(int((len(values) - 1) * fraction), len(values) - 1)]

    request_latency_summary = {
        "p50_seconds": round(percentile(request_latencies, 0.50), 3),
        "p95_seconds": round(percentile(request_latencies, 0.95), 3),
        "max_seconds": round(request_latencies[-1], 3),
    }

    phase_started = time.monotonic()
    summary = Counter()
    five_xx = []
    for _, status, error, response, _ in results:
        if status == 201:
            summary["confirmed"] += 1
        elif status == 409:
            summary["declined/" + (error or "unknown")] += 1
        elif status == 599:
            summary["transport-error"] += 1
            if len(five_xx) < 5:
                five_xx.append(response.decode(errors="replace"))
        elif status >= 500:
            summary["5xx"] += 1
            if len(five_xx) < 5:
                five_xx.append(response.decode(errors="replace"))
        else:
            summary[f"unexpected-http-{status}"] += 1

    timings["outcome_aggregation_seconds"] = round(time.monotonic() - phase_started, 3)

    phase_started = time.monotonic()
    winner = next((result for result in results if result[1] == 201), None)
    idempotency_checks = {"replay_http_status": None, "different_body_http_status": None}
    if winner:
        index, _, _, original_body, _ = winner
        user = f"burst-{run_id}-{index}"
        idempotency_key = f"{run_id}-hot-seat-{index}"
        headers = {"Authorization": "Bearer " + token_for(user, args.token_secret)}
        replay_status, replay_body = request(
            args.base_url,
            f"/shows/{show_id}/reserve",
            "POST",
            {"seats": ["HOT-SEAT"], "idempotency_key": idempotency_key},
            headers,
            timeout=args.timeout,
            retries=2,
        )
        idempotency_checks["replay_http_status"] = replay_status
        different_status, _ = request(
            args.base_url,
            f"/shows/{show_id}/reserve",
            "POST",
            {"seats": ["OTHER-1"], "idempotency_key": idempotency_key},
            headers,
            timeout=args.timeout,
            retries=2,
        )
        idempotency_checks["different_body_http_status"] = different_status
        try:
            same_reservation = json.loads(original_body)["reservation_id"] == json.loads(replay_body)["reservation_id"]
        except (ValueError, KeyError, TypeError):
            same_reservation = False
        idempotency_checks["same_reservation"] = same_reservation
    timings["idempotency_checks_seconds"] = round(time.monotonic() - phase_started, 3)

    phase_started = time.monotonic()
    status, body = request(args.base_url, f"/shows/{show_id}", timeout=args.timeout)
    if status != 200:
        print(f"Could not read show state: HTTP {status} {body.decode(errors='replace')}", file=sys.stderr)
        return 1
    state = json.loads(body)
    total = state["available"] + state["held"] + state["confirmed"]
    timings["final_reconciliation_seconds"] = round(time.monotonic() - phase_started, 3)
    timings["total_seconds"] = round(time.monotonic() - total_started, 3)
    print(json.dumps({
        "requests": args.requests,
        "timings_seconds": timings,
        "reservation_request_latency": request_latency_summary,
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
        print("Sample server/transport errors:", five_xx, file=sys.stderr)
    if (summary["confirmed"] != 1 or summary["5xx"] or summary["transport-error"] or
            sum(count for name, count in summary.items() if name.startswith("unexpected-")) or
            total != state["total_seats"] or state["confirmed"] != 1 or
            idempotency_checks["replay_http_status"] != 200 or
            idempotency_checks["different_body_http_status"] != 409 or
            not idempotency_checks.get("same_reservation")):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
