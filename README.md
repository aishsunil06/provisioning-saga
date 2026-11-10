# provisioning-saga: what each consistency mechanism actually buys

**An activation that fails after reserving a line holds that line forever. At a
10 percent step-failure rate that is 113 lines lost per 1,000 activations, and
nothing in the system reports it. Compensating transactions take it to zero.
Adding a transactional outbox on top measured worse, not better, and the crash
case it exists for never showed up in 300 activations.**

Activating a customer's service is not one write. It reserves a line from a
finite pool, provisions it on the network, and only then is the order complete.
Any step can fail, and the process can die between them. This repository builds
that workflow three times over as three Spring Boot microservices, injects
failures at a controlled rate, and counts what the three databases end up
disagreeing about.

The domain is simplified from telecom service activation, including a fake
network adapter modelled on the Iridium Telnet command set.

The repository exists to answer one question with measurements:

> When a multi-step activation fails partway through, what does each consistency
> mechanism actually buy, and what does it cost?

Full numbers, with the command that reproduces each, are in
[docs/FINDINGS.md](docs/FINDINGS.md).

## Headline results

Three runs per cell, 100 activations each at 20 per second, every arm on the
same seeds so every arm meets the same injected failures.

| Injected failure rate | Arm A, naive | Arm B, saga | Arm C, saga + outbox |
|---|---|---|---|
| 0%  | 0.0 | — | — |
| 5%  | 43.3 (SE 8.8) | — | — |
| 10% | **113.3** (SE 13.3) | **0.0** (SE 0.0) | **0.0** (SE 0.0) |
| 25% | **286.7** (SE 28.5) | **0.0** (SE 0.0) | 6.7 (SE 6.7) |
| orchestrator killed | — | 0.0 (SE 0.0) | 0.0 (SE 0.0) |

Defects per 1,000 activations. Every one was an orphaned reservation: a line
held for an order that will never activate.

Three things worth reading the findings for:

1. **The naive leak rate is the failure rate.** 4.3, 11.3 and 28.7 percent
   against 5, 10 and 25 injected. Not correlated with it, equal to it, because
   every failed activation keeps what it took.
2. **Compensation costs almost nothing in latency.** 109 ms at p50 against the
   naive arm's 112 ms. The "safer but slower" trade is not there to find on this
   workload, because compensation only runs on the failing minority.
3. **The outbox did not win.** It was worse at a 25 percent failure rate, where
   its relay runs a backlog and a check can catch an intent owed and not yet
   paid, and its crash-survival advantage never appeared because synchronous
   orchestration mostly refuses work when the orchestrator dies rather than
   abandoning it halfway. Its mechanism is proven by unit test; its benefit is
   not proven by this experiment. Both facts are in the findings.

## What is in here

```
order-service/         the saga orchestrator, owns the order state machine
inventory-service/     a finite pool of lines, where leaks become observable
provisioning-service/  the network-facing service and its fake adapter
gateway/               Spring Cloud Gateway, one entry point, request-id injection
common/                cross-cutting infrastructure only, no domain types
bench/                 the load driver, the failure profiles, the consistency checker
k8s/                   manifests and an HPA for the autoscaling experiment
docs/FINDINGS.md       every number, with the command that reproduces it
```

Each service owns its own PostgreSQL database and no service reads another's
tables. Only the checker in `bench/` connects to more than one, and only
read-only.

### The three arms

The mechanism is chosen by the `SAGA_MECHANISM` environment variable, and all
three arms run the same step definitions through the same orchestration code.
Only the behaviour on failure differs, which is what makes the comparison a
comparison of mechanisms rather than of three separately written programs.

- **`naive`** runs the steps and, on failure, stops. Completed steps stay done.
- **`saga`** undoes completed steps in reverse order, with bounded retries. A
  line is deprovisioned before it is handed back to the pool.
- **`outbox`** commits a compensation intent in the same transaction as the
  state change that made it necessary, and a polling relay applies it. A
  replacement process can finish a compensation the original never ran.

## Running it

Requires Java 21, Docker, and Python 3.

```bash
./mvnw -DskipTests package          # the Dockerfiles copy jars, they do not build them
docker compose up -d --build        # postgres, three services, gateway on :8080
curl -XPOST localhost:8080/api/activations \
  -H 'content-type: application/json' \
  -d '{"customerId":"c1","plan":"BASIC","lineType":"VOICE"}'
```

Tests:

```bash
./mvnw test                      # 47 Java tests, Testcontainers-backed, needs Docker
python -m pytest bench/tests -q  # 17 Python tests, needs the system up
```

One cell of the experiment:

```bash
python -m bench.run_experiment --arm naive --profile fail10 --runs 3 --n 100 --rate 20
```

Note the module form. `bench` is a package, and running the file directly puts
the wrong directory on `sys.path`.

## Honest limits

- **The Kubernetes autoscaling experiment has not been run.** The manifests in
  `k8s/` apply and the HPA is configured against CPU with requests and limits
  set, but no number is published for it, because an unrun experiment does not
  get a row in the findings.
- **Orchestration is synchronous.** That is why the kill profile mostly refuses
  work instead of abandoning it, and it is the main reason arm C's advantage did
  not show. An asynchronous orchestrator would be a different experiment and
  would probably be a fairer one to the outbox.
- **Only the network is simulated.** The services, the databases, the HTTP hops
  and the failures they produce are real; the thing that decides whether a
  network command succeeds is a seeded generator.
- **100 activations per run** is enough to separate 113 from 0 and not enough to
  resolve 6.7 from 0. The standard errors say so, and they are published next to
  every number for that reason.
