"""The failure profiles one run of the experiment can be configured with.

A profile is the independent variable. It is applied to the system by setting
the provisioning service's environment, so the arms are compared under
identical injected conditions rather than under whatever each happened to see.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class BenchProfile:
    """How the network misbehaves, and whether the orchestrator gets killed."""

    name: str
    failure_rate: float = 0.0
    lost_response_rate: float = 0.0
    mean_latency_ms: int = 0
    kill_orchestrator: bool = False

    def __post_init__(self) -> None:
        if not 0.0 <= self.failure_rate <= 1.0:
            raise ValueError(f"failure_rate must be between 0 and 1, got {self.failure_rate}")
        if not 0.0 <= self.lost_response_rate <= 1.0:
            raise ValueError(f"lost_response_rate must be between 0 and 1, got {self.lost_response_rate}")
        if self.failure_rate + self.lost_response_rate > 1.0:
            raise ValueError(
                "failure_rate + lost_response_rate must not exceed 1, got "
                f"{self.failure_rate + self.lost_response_rate}"
            )

    def environment(self, seed: int) -> dict[str, str]:
        """The environment variables that put the system into this profile."""
        return {
            "NETWORK_PROFILE_FAILURE_RATE": str(self.failure_rate),
            "NETWORK_PROFILE_LOST_RESPONSE_RATE": str(self.lost_response_rate),
            "NETWORK_PROFILE_MEAN_LATENCY_MS": str(self.mean_latency_ms),
            "NETWORK_PROFILE_SEED": str(seed),
        }


PROFILES: dict[str, BenchProfile] = {
    "clean": BenchProfile(name="clean", failure_rate=0.0),
    "fail5": BenchProfile(name="fail5", failure_rate=0.05),
    "fail10": BenchProfile(name="fail10", failure_rate=0.10),
    "fail25": BenchProfile(name="fail25", failure_rate=0.25),
    # Lost responses on top of outright failures: the mode that separates arm C
    # from arm B, because a compensation can be lost the same way a command can.
    "lossy": BenchProfile(name="lossy", failure_rate=0.10, lost_response_rate=0.10),
    # Same injected failures as fail10, plus the orchestrator dying mid-saga.
    "kill": BenchProfile(name="kill", failure_rate=0.10, kill_orchestrator=True),
}
