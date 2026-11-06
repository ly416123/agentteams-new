import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("fixed_capacity_estimator.py")
sys.path.insert(0, str(SCRIPT.parent))
import fixed_capacity_estimator as estimator


class FixedCapacityEstimatorTest(unittest.TestCase):
    def test_selects_smallest_pool_meeting_utilization_and_queue_slo(self) -> None:
        result = estimator.recommend_workers(
            arrival_per_minute=6,
            mean_duration_seconds=30,
            queue_p95_seconds=5,
            max_utilization=0.7,
            max_workers=8,
        )

        self.assertEqual(result.worker_count, 7)
        self.assertLessEqual(result.utilization, 0.7)
        self.assertLessEqual(result.queue_p95_seconds, 5)

    def test_adds_workers_when_utilization_is_safe_but_queue_slo_is_missed(self) -> None:
        result = estimator.recommend_workers(
            arrival_per_minute=6,
            mean_duration_seconds=30,
            queue_p95_seconds=30,
            max_utilization=0.75,
            max_workers=8,
        )

        self.assertEqual(result.worker_count, 5)
        self.assertGreater(result.queue_p95_seconds, 0)
        self.assertLessEqual(result.queue_p95_seconds, 30)

    def test_rejects_capacity_when_worker_limit_cannot_meet_utilization(self) -> None:
        with self.assertRaises(estimator.CapacityUnavailableError):
            estimator.recommend_workers(
                arrival_per_minute=10,
                mean_duration_seconds=60,
                queue_p95_seconds=30,
                max_utilization=0.7,
                max_workers=8,
            )

    def test_rejects_nonpositive_or_out_of_range_inputs(self) -> None:
        with self.assertRaises(ValueError):
            estimator.recommend_workers(
                arrival_per_minute=1,
                mean_duration_seconds=0,
                queue_p95_seconds=1,
                max_utilization=0.7,
                max_workers=8,
            )


if __name__ == "__main__":
    unittest.main()
