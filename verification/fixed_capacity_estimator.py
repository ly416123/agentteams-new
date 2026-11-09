#!/usr/bin/env python3
"""Estimate a fixed single-concurrency Worker pool using an M/M/c queue."""

from __future__ import annotations

import argparse
import json
import math
from dataclasses import asdict, dataclass


@dataclass(frozen=True)
class CapacityRecommendation:
    worker_count: int
    utilization: float
    probability_of_wait: float
    queue_p95_seconds: float


class CapacityUnavailableError(ValueError):
    pass


def _erlang_c(offered_load: float, workers: int) -> float:
    terms = [offered_load**n / math.factorial(n) for n in range(workers)]
    last = offered_load**workers / math.factorial(workers)
    utilization = offered_load / workers
    tail = last / (1 - utilization)
    return tail / (sum(terms) + tail)


def _queue_wait_quantile(probability: float, offered_load: float,
                         service_rate: float, workers: int,
                         percentile: float) -> float:
    if probability <= 1 - percentile:
        return 0.0
    completion_margin = workers * service_rate - offered_load * service_rate
    return math.log(probability / (1 - percentile)) / completion_margin


def recommend_workers(*, arrival_per_minute: float,
                      mean_duration_seconds: float,
                      queue_p95_seconds: float,
                      max_utilization: float,
                      max_workers: int) -> CapacityRecommendation:
    if not math.isfinite(arrival_per_minute) or arrival_per_minute <= 0:
        raise ValueError("arrival_per_minute must be a finite positive number")
    if not math.isfinite(mean_duration_seconds) or mean_duration_seconds <= 0:
        raise ValueError("mean_duration_seconds must be a finite positive number")
    if not math.isfinite(queue_p95_seconds) or queue_p95_seconds < 0:
        raise ValueError("queue_p95_seconds must be a finite non-negative number")
    if not math.isfinite(max_utilization) or not 0 < max_utilization < 1:
        raise ValueError("max_utilization must be greater than 0 and less than 1")
    if isinstance(max_workers, bool) or not isinstance(max_workers, int) or not 1 <= max_workers <= 8:
        raise ValueError("max_workers must be an integer from 1 through 8")

    arrival_rate = arrival_per_minute / 60.0
    service_rate = 1.0 / mean_duration_seconds
    offered_load = arrival_rate / service_rate
    for workers in range(1, max_workers + 1):
        utilization = offered_load / workers
        if utilization >= 1:
            continue
        probability = _erlang_c(offered_load, workers)
        wait_p95 = _queue_wait_quantile(
            probability, offered_load, service_rate, workers, 0.95
        )
        if utilization <= max_utilization and wait_p95 <= queue_p95_seconds:
            return CapacityRecommendation(workers, utilization, probability, wait_p95)

    max_load = offered_load / max_workers
    raise CapacityUnavailableError(
        f"fixed pool of {max_workers} Workers cannot meet both queue p95 "
        f"{queue_p95_seconds:g}s and utilization <= {max_utilization:g}; "
        f"estimated utilization at the configured limit is {max_load:.3f}"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--arrival-per-minute", type=float, required=True)
    parser.add_argument("--mean-duration-seconds", type=float, required=True)
    parser.add_argument("--queue-p95-seconds", type=float, required=True)
    parser.add_argument("--max-utilization", type=float, default=0.70)
    parser.add_argument("--max-workers", type=int, default=8)
    args = parser.parse_args()
    try:
        result = recommend_workers(
            arrival_per_minute=args.arrival_per_minute,
            mean_duration_seconds=args.mean_duration_seconds,
            queue_p95_seconds=args.queue_p95_seconds,
            max_utilization=args.max_utilization,
            max_workers=args.max_workers,
        )
    except ValueError as error:
        parser.error(str(error))
    print(json.dumps(asdict(result), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
