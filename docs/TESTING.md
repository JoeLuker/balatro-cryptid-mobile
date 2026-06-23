# Testing the scoring engine

The engine is a 1:1 port of Balatro's scoring cascade, so correctness means **score parity with the
Lua source**. Coverage is layered: one end-to-end oracle plus three unit harnesses, each closing a
blind spot the others structurally cannot reach. Every layer here has caught a real, shipped bug.

## The four layers

| Layer | Drives | Asserts | Catches | Run |
|-------|--------|---------|---------|-----|
| **Oracle** — `Oracle.kt` (277 cases) | `Score.score()` on a pre-set board | the final chip total vs Balatro | the scoring cascade, edition/seal/enhancement integration, `jokerMain` read effects | `cd rebuild && test/kt-oracle.sh` |
| **`RunLoopReducerTest`** | real `GameEvent`s through `JOKER_MANIFEST` reducers | accumulated `FJokerState` | wrong trigger / constant / floor / reset in a `reduce` hook | `./gradlew :app:testDebugUnitTest` |
| **`ScoreHookTest`** | each `individual`/`held`/`otherJoker` hook + `dispatchManifest` | the exact `Effect`, and the routed `Fx` fields | wrong per-card/per-joker logic, `perCard`-on-wrong-pass, individual-vs-`*Mod` field errors, self-exclusion | `./gradlew :app:testDebugUnitTest` |
| **`RetriggerHookTest`** | `dispatchManifest` on both retrigger routes | the emitted repetition count | wrong target/position, missing cap, missing self-exclusion | `./gradlew :app:testDebugUnitTest` |

### Why the oracle is not enough

The oracle scores with **pre-set joker state** — it constructs an `FJoker`, hands it to `Score.score()`,
and checks the total. It therefore exercises only the *read* path. It never:

- drives a reducer's **accrual trigger** (the run-loop `reduce` hook), or
- sees a hook's **Effect in isolation** (a wrong Effect can cancel out in the final arithmetic), or
- inspects a **retrigger count** directly (it only sees the count folded into the score).

Every latent bug found in the June 2026 audits passed its oracle case *by coincidence* and was caught
only by the harnesses:

- `j_square` accrued on a 5-card hand instead of 4 — its oracle case pre-set `chips=12`.
- `j_cry_eternalflame` gated its sell-scaling on `sellValue>=2` — its oracle case pre-set `x=1.3`.
- the `perCard` double-count (#44) — masked by a wrong asserted value.

## The rule: a new or changed joker needs a Case **and** a harness test

The oracle alone will let an accrual/routing/retrigger bug through. When you add or change a joker, add:

1. an **Oracle `Case`** — the score it produces in a representative hand (the integration check), **and**
2. the **matching harness test** for the hook(s) it actually uses:
   - has a `reduce` hook → **`RunLoopReducerTest`** (apply its `GameEvent`s, assert the accrued state)
   - has an `individual`/`held`/`otherJoker` hook → **`ScoreHookTest`** (assert the exact `Effect`)
   - has a `retrigger` hook or an `individual` hook returning `Effect.Retrigger` → **`RetriggerHookTest`**

A reducer-trigger or retrigger-count bug is invisible to the oracle; the harness is where it shows.

## Mutation-validate the test, not just the fix

A harness test is only worth its green check if it would go **red** on the bug it targets. After
writing one, prove it: revert the fix (or introduce the bug), run the single test, confirm it fails,
then restore. Examples that must fail on reversion:

- `RunLoopReducerTest` — `j_square` reducer back to `playedCount == 5`
- `ScoreHookTest` — re-add `|| ctx.repetition` to the `dispatchManifest` `perCard` guard
- `RetriggerHookTest` — drop `minOf(s.n, 40)` from `j_cry_mstack`

A test that stays green under its own bug is documenting a coincidence, not pinning behaviour.
