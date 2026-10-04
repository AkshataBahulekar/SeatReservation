#!/usr/bin/env python3
"""Concurrent correctness checks against a running Seat Reservation API."""

import argparse
import concurrent.futures
import json
import sys
import threading
import time
import uuid

from burst import request, token_for


def decode(body):
    try:
        return json.loads(body)
    except (ValueError, TypeError):
        return None


def create_show(base_url, admin_token, name, seats, per_user_limit=None, timeout=60):
    payload = {
        "name": name,
        "seats": seats,
        "price_paise": 1000,
    }
    if per_user_limit is not None:
        payload["per_user_limit"] = per_user_limit
    status, body = request(
        base_url,
        "/shows",
        "POST",
        payload,
        {"X-Admin-Token": admin_token},
        timeout=timeout,
    )
    response = decode(body)
    if status != 201 or not isinstance(response, dict) or "id" not in response:
        raise RuntimeError(f"Show creation failed: HTTP {status} {body.decode(errors='replace')}")
    return response["id"]


def get_show(base_url, show_id, timeout):
    status, body = request(base_url, f"/shows/{show_id}", timeout=timeout)
    response = decode(body)
    if status != 200 or not isinstance(response, dict):
        raise RuntimeError(f"Show lookup failed: HTTP {status} {body.decode(errors='replace')}")
    return response


def reconcile(state):
    actual = {"available": 0, "held": 0, "confirmed": 0}
    for seat in state["seats"]:
        status = seat["status"]
        if status not in actual:
            return False
        actual[status] += 1
    return (
        state["available"] + state["held"] + state["confirmed"] == state["total_seats"]
        and state["total_seats"] == len(state["seats"])
        and actual["available"] == state["available"]
        and actual["held"] == state["held"]
        and actual["confirmed"] == state["confirmed"]
    )


def reservation_request(base_url, show_id, secret, spec, timeout):
    user, seats, key, extra = spec
    payload = {"seats": seats, "idempotency_key": key}
    payload.update(extra)
    status, body = request(
        base_url,
        f"/shows/{show_id}/reserve",
        "POST",
        payload,
        {"Authorization": "Bearer " + token_for(user, secret)},
        timeout=timeout,
    )
    return {
        "user": user,
        "seats_requested": seats,
        "status": status,
        "body": decode(body),
        "raw_body": body.decode(errors="replace"),
    }


def parallel_reservations(base_url, show_id, secret, specs, timeout):
    start = threading.Barrier(len(specs) + 1)

    def send(spec):
        start.wait(timeout=timeout)
        return reservation_request(base_url, show_id, secret, spec, timeout)

    with concurrent.futures.ThreadPoolExecutor(max_workers=len(specs)) as pool:
        futures = [pool.submit(send, spec) for spec in specs]
        start.wait(timeout=timeout)
        return [future.result() for future in futures]


def result_codes(results):
    codes = {}

    for result in results:
        body = result["body"]

        if isinstance(body, dict):
            if result["status"] in (200, 201):
                code = body.get("status", "success")
            else:
                code = body.get("error", "unknown")
        else:
            code = "invalid-response"

        key = f"http-{result['status']}/{code}"
        codes[key] = codes.get(key, 0) + 1

    return codes


def check_multiple_hot_seats(base_url, admin_token, secret, timeout, seat_count, per_seat):
    run_id = uuid.uuid4().hex
    hot_seats = [f"HOT-{index:02d}" for index in range(seat_count)]
    show_id = create_show(base_url, admin_token, "multi-hot-" + run_id[:12], hot_seats, timeout=timeout)
    specs = [
        (f"multi-{run_id}-{seat_index}-{buyer}", [seat], f"{run_id}-{seat_index}-{buyer}", {})
        for seat_index, seat in enumerate(hot_seats)
        for buyer in range(per_seat)
    ]
    results = parallel_reservations(base_url, show_id, secret, specs, timeout)
    winners = [
        result for result in results
        if result["status"] == 201
        and isinstance(result["body"], dict)
        and len(result["body"].get("seats", [])) == 1
    ]
    winner_seats = [result["body"]["seats"][0] for result in winners]
    losers_clean = all(
        result["status"] == 409
        and isinstance(result["body"], dict)
        and result["body"].get("error") == "seat-taken"
        for result in results if result["status"] != 201
    )
    state = get_show(base_url, show_id, timeout)
    passed = (
        len(winners) == seat_count
        and set(winner_seats) == set(hot_seats)
        and len(set(winner_seats)) == seat_count
        and losers_clean
        and state["confirmed"] == seat_count
        and reconcile(state)
    )
    return {
        "passed": passed,
        "show_id": show_id,
        "seats_stormed": seat_count,
        "requests": len(results),
        "outcomes": result_codes(results),
        "confirmed": state["confirmed"],
        "reconciles": reconcile(state),
    }


def check_multi_seat_overlap_race(base_url, admin_token, secret, timeout):
    run_id = uuid.uuid4().hex
    seats = ["A1", "A2", "A3"]
    show_id = create_show(
        base_url, admin_token, "overlap-" + run_id[:12], seats, timeout=timeout
    )
    specs = [
        ("overlap-user-a-" + run_id, ["A1", "A2"], run_id + "-user-a", {}),
        ("overlap-user-b-" + run_id, ["A2", "A3"], run_id + "-user-b", {}),
    ]
    results = parallel_reservations(base_url, show_id, secret, specs, timeout)
    winners = [result for result in results if result["status"] == 201]
    losers = [result for result in results if result["status"] != 201]
    winner_seats = []
    winner_user = None
    if len(winners) == 1 and isinstance(winners[0]["body"], dict):
        winner_seats = winners[0]["body"].get("seats", [])
        winner_user = winners[0]["body"].get("user_id")

    state = get_show(base_url, show_id, timeout)
    seat_status = {seat["seat"]: seat["status"] for seat in state["seats"]}
    expected_available = "A3" if winner_seats == ["A1", "A2"] else "A1"
    passed = (
        len(winners) == 1
        and len(losers) == 1
        and winner_seats in (["A1", "A2"], ["A2", "A3"])
        and winner_user == winners[0]["user"]
        and losers[0]["status"] == 409
        and isinstance(losers[0]["body"], dict)
        and losers[0]["body"].get("error") == "seat-taken"
        and state["confirmed"] == 2
        and state["available"] == 1
        and seat_status["A2"] == "confirmed"
        and seat_status[expected_available] == "available"
        and reconcile(state)
    )
    return {
        "passed": passed,
        "show_id": show_id,
        "requests": len(results),
        "outcomes": result_codes(results),
        "winner_user": winner_user,
        "winner_seats": winner_seats,
        "loser_user": losers[0]["user"] if len(losers) == 1 else None,
        "seat_status": seat_status,
        "reconciles": reconcile(state),
    }


def check_multi_seat_deadlock_race(base_url, admin_token, secret, timeout, request_count):
    run_id = uuid.uuid4().hex
    seats = ["DEADLOCK-A", "DEADLOCK-B"]
    show_id = create_show(
        base_url, admin_token, "deadlock-" + run_id[:12], seats, timeout=timeout
    )
    specs = [
        (
            f"deadlock-user-{run_id}-{index}",
            seats if index % 2 == 0 else list(reversed(seats)),
            f"{run_id}-deadlock-{index}",
            {},
        )
        for index in range(request_count)
    ]
    started = time.monotonic()
    results = parallel_reservations(base_url, show_id, secret, specs, timeout)
    elapsed = time.monotonic() - started
    winners = [result for result in results if result["status"] == 201]
    clean_losers = all(
        result["status"] == 409
        and isinstance(result["body"], dict)
        and result["body"].get("error") == "seat-taken"
        for result in results if result["status"] != 201
    )
    state = get_show(base_url, show_id, timeout)
    passed = (
        len(winners) == 1
        and clean_losers
        and state["confirmed"] == 2
        and state["available"] == 0
        and reconcile(state)
    )
    return {
        "passed": passed,
        "show_id": show_id,
        "requests": len(results),
        "outcomes": result_codes(results),
        "elapsed_seconds": round(elapsed, 3),
        "input_orders": ["A,B", "B,A"],
        "confirmed_seats": state["confirmed"],
        "reconciles": reconcile(state),
    }


def check_same_key_concurrency(base_url, admin_token, secret, timeout, retries):
    run_id = uuid.uuid4().hex
    show_id = create_show(base_url, admin_token, "same-key-" + run_id[:12], ["KEY-SEAT"], timeout=timeout)
    user = "same-key-user-" + run_id
    key = run_id + "-same-body"
    spec = (user, ["KEY-SEAT"], key, {})
    results = parallel_reservations(base_url, show_id, secret, [spec] * retries, timeout)
    responses = [result["body"] for result in results]
    reservation_ids = {
        response.get("reservation_id")
        for response in responses
        if isinstance(response, dict) and response.get("reservation_id")
    }
    all_successes_reference_same_reservation = all(
        isinstance(result["body"], dict)
        and result["body"].get("reservation_id") in reservation_ids
        for result in results if result["status"] in (200, 201)
    )
    state = get_show(base_url, show_id, timeout)
    passed = (
        sum(result["status"] == 201 for result in results) == 1
        and sum(result["status"] == 200 for result in results) == retries - 1
        and len(reservation_ids) == 1
        and all_successes_reference_same_reservation
        and state["confirmed"] == 1
        and reconcile(state)
    )
    return {
        "passed": passed,
        "show_id": show_id,
        "requests": retries,
        "outcomes": result_codes(results),
        "distinct_reservation_ids": len(reservation_ids),
        "reconciles": reconcile(state),
    }


def check_same_key_different_body(base_url, admin_token, secret, timeout, requests_per_body):
    run_id = uuid.uuid4().hex
    seats = ["BODY-A", "BODY-B"]
    show_id = create_show(base_url, admin_token, "different-body-" + run_id[:12], seats, timeout=timeout)
    user = "different-body-user-" + run_id
    key = run_id + "-shared-key"
    specs = (
        [(user, [seats[0]], key, {}) for _ in range(requests_per_body)]
        + [(user, [seats[1]], key, {}) for _ in range(requests_per_body)]
    )
    results = parallel_reservations(base_url, show_id, secret, specs, timeout)
    winners = [result for result in results if result["status"] == 201]
    winner_seat = None
    if len(winners) == 1 and isinstance(winners[0]["body"], dict):
        winner_seat = winners[0]["body"].get("seats", [None])[0]
    matching = [result for result in results if result["seats_requested"] == [winner_seat]]
    conflicting = [result for result in results if result["seats_requested"] != [winner_seat]]
    winning_reservation_ids = {
        result["body"].get("reservation_id")
        for result in matching
        if isinstance(result["body"], dict) and result["body"].get("reservation_id")
    }
    passed = (
        winner_seat in seats
        and len(winners) == 1
        and sum(result["status"] == 200 for result in matching) == requests_per_body - 1
        and len(winning_reservation_ids) == 1
        and all(
            isinstance(result["body"], dict)
            and result["body"].get("reservation_id") in winning_reservation_ids
            for result in matching
        )
        and all(
            result["status"] == 409
            and isinstance(result["body"], dict)
            and result["body"].get("error") == "idempotency-key-reused"
            for result in conflicting
        )
    )
    state = get_show(base_url, show_id, timeout)
    passed = passed and state["confirmed"] == 1 and reconcile(state)
    return {
        "passed": passed,
        "show_id": show_id,
        "requests": len(results),
        "winning_body_seat": winner_seat,
        "outcomes": result_codes(results),
        "reconciles": reconcile(state),
    }


def check_per_user_limit(base_url, admin_token, secret, timeout, request_count, limit):
    run_id = uuid.uuid4().hex
    seats = [f"LIMIT-{index:02d}" for index in range(request_count)]
    show_id = create_show(
        base_url, admin_token, "per-user-" + run_id[:12], seats,
        per_user_limit=limit, timeout=timeout,
    )
    user = "limit-user-" + run_id
    specs = [
        (user, [seat], f"{run_id}-limit-{index}", {})
        for index, seat in enumerate(seats)
    ]
    results = parallel_reservations(base_url, show_id, secret, specs, timeout)
    winners = sum(result["status"] == 201 for result in results)
    declines_clean = all(
        result["status"] == 409
        and isinstance(result["body"], dict)
        and result["body"].get("error") == "per-user-limit"
        for result in results if result["status"] != 201
    )
    state = get_show(base_url, show_id, timeout)
    passed = (
        winners == limit
        and declines_clean
        and state["confirmed"] == limit
        and reconcile(state)
    )
    return {
        "passed": passed,
        "show_id": show_id,
        "limit": limit,
        "requests": len(results),
        "outcomes": result_codes(results),
        "confirmed": state["confirmed"],
        "reconciles": reconcile(state),
    }


def check_identity_and_cancel(base_url, admin_token, secret, timeout):
    run_id = uuid.uuid4().hex
    show_id = create_show(base_url, admin_token, "identity-" + run_id[:12], ["OWNED-SEAT"], timeout=timeout)
    owner = "owner-" + run_id
    key = run_id + "-owner-booking"
    reservation = reservation_request(
        base_url, show_id, secret,
        (owner, ["OWNED-SEAT"], key, {"user_id": "spoofed-user"}),
        timeout,
    )
    body = reservation["body"]
    reservation_id = body.get("reservation_id") if isinstance(body, dict) else None
    response = {
        "passed": False,
        "show_id": show_id,
        "reservation_status": reservation["status"],
        "returned_user_id_matches_token": isinstance(body, dict) and body.get("user_id") == owner,
    }
    if reservation["status"] != 201 or not reservation_id:
        response["reason"] = "Could not create reservation for ownership test"
        return response

    wrong_status, wrong_body = request(
        base_url,
        f"/reservations/{reservation_id}/cancel",
        "POST",
        headers={"Authorization": "Bearer " + token_for("not-" + owner, secret)},
        timeout=timeout,
    )
    owner_status, _ = request(
        base_url,
        f"/reservations/{reservation_id}/cancel",
        "POST",
        headers={"Authorization": "Bearer " + token_for(owner, secret)},
        timeout=timeout,
    )
    released_state = get_show(base_url, show_id, timeout)
    rebooker = "rebooker-" + run_id
    rebook_status, rebook_body = request(
        base_url,
        f"/shows/{show_id}/reserve",
        "POST",
        {"seats": ["OWNED-SEAT"], "idempotency_key": run_id + "-rebook"},
        {"Authorization": "Bearer " + token_for(rebooker, secret)},
        timeout=timeout,
    )
    response.update({
        "non_owner_cancel_status": wrong_status,
        "non_owner_cancel_error": (
            wrong_body_json.get("error") if (wrong_body_json := decode(wrong_body)) else None
        ),
        "owner_cancel_status": owner_status,
        "released_seat_available": released_state["available"] == 1,
        "rebook_status": rebook_status,
        "rebook_user_id_matches_token": (
            isinstance(decode(rebook_body), dict)
            and decode(rebook_body).get("user_id") == rebooker
        ),
        "passed": (
            response["returned_user_id_matches_token"]
            and wrong_status == 403
            and isinstance(decode(wrong_body), dict)
            and decode(wrong_body).get("error") == "not-reservation-owner"
            and owner_status == 204
            and released_state["available"] == 1
            and released_state["confirmed"] == 0
            and reconcile(released_state)
            and rebook_status == 201
            and isinstance(decode(rebook_body), dict)
            and decode(rebook_body).get("user_id") == rebooker
        ),
    })
    return response


def check_reconciliation_during_load(
    base_url, admin_token, secret, timeout, seat_count, per_seat
):
    run_id = uuid.uuid4().hex
    seats = [f"OBS-{index:02d}" for index in range(seat_count)]
    show_id = create_show(base_url, admin_token, "reconcile-" + run_id[:12], seats, timeout=timeout)
    specs = [
        (f"observer-{run_id}-{seat_index}-{buyer}", [seat], f"{run_id}-{seat_index}-{buyer}", {})
        for seat_index, seat in enumerate(seats)
        for buyer in range(per_seat)
    ]
    stop = threading.Event()
    burst_active = threading.Event()
    sampler_ready = threading.Event()
    sample_errors = []
    sample_counts = []
    samples_during_burst = 0

    def sample():
        nonlocal samples_during_burst
        while not stop.is_set():
            try:
                state = get_show(base_url, show_id, timeout)
                sample_counts.append(
                    state["available"] + state["held"] + state["confirmed"]
                )
                if not reconcile(state):
                    sample_errors.append("Inventory counts did not reconcile")
                    return
                if burst_active.is_set():
                    samples_during_burst += 1
                sampler_ready.set()
            except (RuntimeError, KeyError, TypeError) as error:
                sample_errors.append(str(error))
                return
            stop.wait(0.001)

    observer = threading.Thread(target=sample, name="inventory-sampler", daemon=True)
    observer.start()
    if not sampler_ready.wait(timeout):
        stop.set()
        observer.join(timeout)
        raise RuntimeError("Inventory sampler could not read the show before the burst")

    start = threading.Barrier(len(specs) + 1)

    def send(spec):
        start.wait(timeout=timeout)
        return reservation_request(base_url, show_id, secret, spec, timeout)

    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(specs)) as pool:
            futures = [pool.submit(send, spec) for spec in specs]
            burst_active.set()
            start.wait(timeout=timeout)
            results = [future.result() for future in futures]
    finally:
        stop.set()
        observer.join(timeout)
    if observer.is_alive():
        sample_errors.append("Inventory sampler did not stop before its timeout")

    state = get_show(base_url, show_id, timeout)
    winner_count = sum(result["status"] == 201 for result in results)
    clean_declines = all(
        result["status"] == 409
        and isinstance(result["body"], dict)
        and result["body"].get("error") == "seat-taken"
        for result in results if result["status"] != 201
    )
    passed = (
        not sample_errors
        and samples_during_burst > 0
        and len(sample_counts) > 0
        and winner_count == seat_count
        and clean_declines
        and state["confirmed"] == seat_count
        and reconcile(state)
    )
    return {
        "passed": passed,
        "show_id": show_id,
        "requests": len(results),
        "outcomes": result_codes(results),
        "inventory_samples": len(sample_counts),
        "samples_during_burst": samples_during_burst,
        "sample_errors": sample_errors,
        "final_counts_reconcile": reconcile(state),
    }


def main():
    parser = argparse.ArgumentParser(description="Concurrent correctness checks for the reservation API")
    parser.add_argument("base_url")
    parser.add_argument("--admin-token", default="dev-admin-token")
    parser.add_argument("--token-secret", default="local-development-secret-change-me")
    parser.add_argument("--timeout", type=int, default=60, help="per-request timeout in seconds")
    parser.add_argument("--hot-seats", type=int, default=5)
    parser.add_argument("--requests-per-seat", type=int, default=10)
    parser.add_argument("--deadlock-requests", type=int, default=20)
    parser.add_argument("--same-key-requests", type=int, default=20)
    parser.add_argument("--requests-per-body", type=int, default=10)
    parser.add_argument("--limit-requests", type=int, default=10)
    parser.add_argument("--user-limit", type=int, default=4)
    args = parser.parse_args()
    if (
        args.timeout < 1
        or args.hot_seats < 1
        or args.requests_per_seat < 2
        or args.deadlock_requests < 2
        or args.same_key_requests < 2
        or args.requests_per_body < 2
        or args.user_limit < 1
        or args.limit_requests <= args.user_limit
    ):
        parser.error("Concurrency counts must be positive; each race needs at least two requests")

    checks = {}
    tests = [
        (
            "multiple_hot_seats",
            lambda: check_multiple_hot_seats(
                args.base_url, args.admin_token, args.token_secret, args.timeout,
                args.hot_seats, args.requests_per_seat,
            ),
        ),
        (
            "multi_seat_overlap_race",
            lambda: check_multi_seat_overlap_race(
                args.base_url, args.admin_token, args.token_secret, args.timeout,
            ),
        ),
        (
            "multi_seat_deadlock_race",
            lambda: check_multi_seat_deadlock_race(
                args.base_url, args.admin_token, args.token_secret, args.timeout,
                args.deadlock_requests,
            ),
        ),
        (
            "same_key_concurrency",
            lambda: check_same_key_concurrency(
                args.base_url, args.admin_token, args.token_secret, args.timeout,
                args.same_key_requests,
            ),
        ),
        (
            "same_key_different_body_concurrency",
            lambda: check_same_key_different_body(
                args.base_url, args.admin_token, args.token_secret, args.timeout,
                args.requests_per_body,
            ),
        ),
        (
            "per_user_limit_race",
            lambda: check_per_user_limit(
                args.base_url, args.admin_token, args.token_secret, args.timeout,
                args.limit_requests, args.user_limit,
            ),
        ),
        (
            "identity_and_cancellation",
            lambda: check_identity_and_cancel(
                args.base_url, args.admin_token, args.token_secret, args.timeout,
            ),
        ),
        (
            "reconciliation_during_load",
            lambda: check_reconciliation_during_load(
                args.base_url, args.admin_token, args.token_secret, args.timeout,
                args.hot_seats, args.requests_per_seat,
            ),
        ),
    ]

    for name, test in tests:
        try:
            checks[name] = test()
        except Exception as error:
            checks[name] = {"passed": False, "error": f"{type(error).__name__}: {error}"}
        print(json.dumps({"test": name, **checks[name]}, sort_keys=True), flush=True)

    passed = all(result.get("passed", False) for result in checks.values())
    print(json.dumps({"all_passed": passed, "checks": checks}, indent=2, sort_keys=True))
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
