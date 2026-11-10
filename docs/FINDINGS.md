# Findings

Every number below comes from a file in `bench/results/`, and every table is
preceded by the command that reproduces it. Three runs per cell, 100
activations per run, submitted at 20 per second through the gateway, each run
on a different seed. Values are the mean over three runs with the standard
error of that mean.

Nothing here is simulated except the network itself. The three services are
real Spring Boot over real PostgreSQL, one database each, talking over HTTP.

---

## 1. What the naive arm leaks

```bash
./mvnw -DskipTests package && docker compose up -d --build
python -m bench.run_experiment --arm naive --profile clean  --runs 3 --n 100 --rate 20
python -m bench.run_experiment --arm naive --profile fail5  --runs 3 --n 100 --rate 20
python -m bench.run_experiment --arm naive --profile fail10 --runs 3 --n 100 --rate 20
python -m bench.run_experiment --arm naive --profile fail25 --runs 3 --n 100 --rate 20
```

| Injected step-failure rate | Defects per 1,000 activations | As a percentage |
|---|---|---|
| 0%  | **0.0** (SE 0.0)   | 0.0% |
| 5%  | **43.3** (SE 8.8)  | 4.3% |
| 10% | **113.3** (SE 13.3)| 11.3% |
| 25% | **286.7** (SE 28.5)| 28.7% |

Every defect was an orphaned reservation. Phantom activations, lost
compensations and inventory drift were zero in every run.

**The leak rate is the failure rate.** 4.3, 11.3 and 28.7 percent against 5, 10
and 25 percent injected. That is what makes it a baseline rather than a number:
with no compensation, one activation that fails after reserving a line holds
that line forever, so the leak is not merely correlated with the failure rate,
it is the failure rate. A pool of 2,000 lines serving this traffic at a 10
percent failure rate is empty after roughly 18,000 activations, and nothing in
the system reports why.

---

## 2. What compensation buys

```bash
python -m bench.run_experiment --arm saga   --profile fail10 --runs 3 --n 100 --rate 20
python -m bench.run_experiment --arm outbox --profile fail10 --runs 3 --n 100 --rate 20
python -m bench.run_experiment --arm saga   --profile fail25 --runs 3 --n 100 --rate 20
python -m bench.run_experiment --arm outbox --profile fail25 --runs 3 --n 100 --rate 20
```

| Arm | 10% failures | 25% failures |
|---|---|---|
| A, naive | 113.3 (SE 13.3) | 286.7 (SE 28.5) |
| B, compensating saga | **0.0** (SE 0.0) | **0.0** (SE 0.0) |
| C, saga + transactional outbox | **0.0** (SE 0.0) | 6.7 (SE 6.7) |

The arms ran on the same seeds, so they met the same injected failures: 10, 14
and 10 of 100 in the three 10-percent runs, in every arm. The failed
activations are the same failed activations. The only difference is what
happened afterwards.

**113.3 to 0.0.** Compensating in reverse order, with a bounded retry per
compensation, removes the leak entirely at both failure rates tested.

---

## 3. What the outbox costs, and the benefit it did not show

This is the result the repository was most likely to get wrong, so it is stated
plainly: **arm C was not better than arm B on any measurement taken here, and at
a 25 percent failure rate it was worse.**

At 25 percent, arm C showed 6.7 defects per 1,000 against arm B's 0.0. The
defect was a reservation still held at the moment the checker ran. Arm C does
not compensate inline; it commits an intent and lets a relay apply it on a 200
ms poll. Under a heavy failure rate the relay runs a backlog, and a check taken
the instant the last activation settles can catch an intent that is owed and
not yet paid. The leak is transient rather than permanent, which is a real
difference from arm A, but it is a defect at measurement time and it is
reported as one.

### The kill profile: a null result

```bash
python -m bench.run_experiment --arm saga   --profile kill --runs 3 --n 100 --rate 20
python -m bench.run_experiment --arm outbox --profile kill --runs 3 --n 100 --rate 20
```

| Arm | Defects per 1,000 | Activations created | Refused at submission |
|---|---|---|---|
| B, saga | **0.0** (SE 0.0) | 130 of 300 | 170 |
| C, outbox | **0.0** (SE 0.0) | 116 of 300 | 184 |

The kill profile SIGKILLs the order service halfway through each run. Arm C
exists for exactly this case: it commits what is owed before trying to pay it,
so a replacement process can finish the job. The measurement does not show that
advantage, and the reason is architectural rather than a flaw in either arm.

Orchestration here is synchronous. When the orchestrator dies, most of the
offered work is simply refused at the door, 170 and 184 of 300 activations
respectively, rather than accepted and abandoned halfway. The window in which a
saga is half-complete and its process disappears is a few milliseconds wide per
activation, and 300 activations at 20 per second did not land in it.

So the honest summary is: **arm C's cost is measurable and its benefit is not,
at this scale and with synchronous orchestration.** What the unit tests do show,
directly, is that the mechanism works:
`aReplacementRelayAppliesACompensationTheOriginalProcessNeverRan` builds a relay
with no memory of the failing request and watches it pay a compensation from
nothing but a committed row. The mechanism is sound. The experiment that would
make its benefit visible needs either asynchronous orchestration or a kill timed
into the saga rather than into the run.

---

## 4. What compensation costs in latency

Taken from the same runs; percentiles over all settled activations in a cell.

| Arm and profile | p50 | p95 |
|---|---|---|
| naive, clean   | 100 ms | 134 ms |
| naive, fail10  | 112 ms | 165 ms |
| saga, fail10   | 109 ms | 180 ms |
| outbox, fail10 | 108 ms | 153 ms |

**Compensation is close to free in latency terms here.** The spread between
arms at p50 is 4 ms on a 110 ms activation, which is inside the run-to-run
noise. This is expected rather than surprising: compensation only runs on the
failing minority of activations, and the saga's cost is dominated by two
sequential HTTP calls either way. It is worth stating because "safer but
slower" is the assumption a reader arrives with, and on this workload the
slower half is not there to find.

---

## 5. A side finding: SKIP LOCKED can report a pool as empty when it is not

Not an arm of the experiment. It came out of a concurrency test and is the kind
of thing that would be a production incident rather than a benchmark number.

Reserving a line claims one row with `FOR UPDATE SKIP LOCKED`, which is what
keeps concurrent reservations from serializing behind each other. SKIP LOCKED
skips rows another transaction is holding. A caller that arrives while every
free line happens to be locked therefore gets back nothing at all, and cannot
distinguish that from a pool with nothing left in it.

With eight concurrent callers against five free lines, three were told the pool
was exhausted while five reservations were merely in flight. The fix is a
non-locking count before reporting exhaustion, so contention and exhaustion are
separated. The lesson generalises: **SKIP LOCKED buys throughput and hands back
a false-exhaustion mode in exchange**, and the exchange is invisible until
something concurrent tests for it.

Covered by `InventoryServiceIT.concurrentDuplicateActivationIdReservesExactlyOneLine`.

---

## 6. Kubernetes and autoscaling

Manifests are in `k8s/`, and the experiment is
`bench/run_scaling_experiment.py`. **This experiment has not been run, so no
number is published for it.** The manifests apply and the HPA is configured
against CPU with requests and limits set, but the measurement comparing fixed
replicas against an HPA under a 5x burst has not been executed on a cluster, and
an unrun experiment gets no row in this document.

---

## Reproducing all of it

```bash
./mvnw -DskipTests package
docker compose up -d --build
./mvnw test                      # 47 Java tests
python -m pytest bench/tests -q  # 17 Python tests
for arm in naive saga outbox; do
  for profile in clean fail5 fail10 fail25; do
    python -m bench.run_experiment --arm "$arm" --profile "$profile" --runs 3 --n 100 --rate 20
  done
done
```

Each run recreates the provisioning, order and gateway containers, because the
fake network draws its seed once at startup. A profile applied without a restart
would leave the previous run's generator in place and the seed would stop
meaning anything.
