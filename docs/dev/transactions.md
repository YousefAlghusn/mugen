# Transactions

Examples are real mugen-auth code. Both transaction bugs this project hit are in here.

---

## 1. The model

> A bracket around some database statements. Everything inside becomes visible to
> everyone else at one instant, or none of it ever happened.

Two consequences do all the work:

- **Atomic** — no partial result survives or is observable.
- **Held on one pooled connection**, for the bracket's entire life. This explains §7.

---

## 2. The boundary is a proxy

`@Transactional` is not compiled into your method. Spring wraps the bean:

```
caller → [proxy: begin] → your method → [proxy: commit / rollback]
```

**Only calls arriving from outside the bean go through it.**

```java
public TokenPair complete(...) {
    linkOrCreate(provider, profile);   // `this.` call → no proxy → NO TRANSACTION
}

@Transactional
public SsoUser linkOrCreate(...) { ... }   // never once evaluated
```

That shipped. Each write committed alone, so a failed sign-in left a linked account
with no `mugen.user.registered` event — no profile in mugen-user, forever.
**Self-invocation is a silent no-op.** Same for `@Async`, `@Cacheable`, `@PreAuthorize`.

Also never proxied: `private` methods, `final` methods/classes, and anything you
build with `new` in a test.

**Fixes, in order:** move the annotation to the caller → `TransactionTemplate` at the
call site (what `complete` uses, because it must stay non-transactional across its two
network calls) → self-injection, as a last resort.

---

## 3. The other half: the persistence context

The `EntityManager` is bound to the transaction. Entities loaded inside are **managed**
— Hibernate diffs them at commit and writes the changes itself:

```java
@Transactional
public Session rotate(...) {
    Session session = sessions.findForRotation(sessionId).orElseThrow(...);
    session.rotate();     // no save() needed — dirty checking writes it at commit
    return session;
}
```

Outside the bracket the entity is **detached**: mutations go nowhere, and an
un-fetched lazy association throws `LazyInitializationException`. That was the second
bug — roles read after the transaction closed. Hence `@EntityGraph(attributePaths =
"roles")` on `findWithRolesById`, and `open-in-view: false` so this fails in dev
instead of hiding.

> **Load everything the caller will read before the bracket closes.**

`flush` sends the SQL; `commit` makes it permanent. `register` uses `saveAndFlush`
so a unique-index violation arrives as a catchable exception (→ 409) instead of at
commit (→ 500).

---

## 4. Propagation

| Value | Meaning | Use |
|---|---|---|
| **`REQUIRED`** (default) | Join, or start one | ~95% of cases |
| **`MANDATORY`** | Throw unless one exists | A fragment that must never commit alone |
| **`REQUIRES_NEW`** | Suspend caller's, start a real second one | Must survive the caller's rollback |

(`SUPPORTS`, `NOT_SUPPORTED`, `NEVER`, `NESTED` exist. You won't need them.)

**`MANDATORY` is not a stricter `REQUIRED`.** `REQUIRED` already guarantees a
transaction exists; `MANDATORY` adds *"and it must not be mine"*. It's about who owns
the boundary:

```java
// The outbox row must commit with the user row, or the dual write is back.
@Transactional(propagation = Propagation.MANDATORY)
public void userRegistered(User user) { ... }
```

It can't be the default — something must *open* the transaction. And it doesn't catch
self-invocation: the interceptor that reads `propagation` is the one being skipped.

**`REQUIRES_NEW` takes a second connection** while the first is still held. Two nested
on a pool of 10 is a deadlock under load, and it can't see the outer's uncommitted
writes — including rows the outer just locked.

---

## 5. Rollback

**Default: rolls back on `RuntimeException` and `Error`. Commits on checked exceptions.**

```java
@Transactional
public void doIt() throws IOException {
    repo.save(x);
    throw new IOException();   // COMMITS
}
```

Use `rollbackFor = Exception.class` if you throw checked exceptions. mugen throws only
unchecked `AppException` subclasses, so the default is right here — by design, not luck.

**Catching cancels the rollback.** The interceptor only sees what escapes the method.

**`noRollbackFor` must be repeated on the outer method:**

```java
// SessionService.rotate — the revocation must survive the throw
@Transactional(noRollbackFor = SessionReplayDetected.class)

// AuthService.refresh — rotate() is REQUIRED, so it JOINS this transaction.
// One bracket, and the outer one decides.
@Transactional(noRollbackFor = SessionReplayDetected.class)
```

Without it, an inner `REQUIRED` method's rollback rule calls `setRollbackOnly()` — a
one-way latch — and the outer's commit then throws `UnexpectedRollbackException`.

> **Rollback belongs to the outermost transaction. An inner method can only doom it.**

---

## 6. readOnly, isolation, locking

`@Transactional(readOnly = true)` on every query method: skips dirty-check snapshots,
flags the connection, and makes a stray write fail. Free.

**Isolation — never change it.** Defaults (`READ_COMMITTED` on SQL Server and Postgres)
are right; raising it globally buys one narrow fix and pays in contention everywhere.
The three anomalies, so you know the words: **dirty read** (you see uncommitted data),
**non-repeatable read** (same row, different value on re-read), **phantom** (same
`WHERE`, new rows). When you need more than the default, you need it for one query —
use a lock.

**Optimistic** (`@Version`): no lock; the UPDATE carries `WHERE version = :seen`, and a
conflict throws at flush. Right default when a retry is acceptable.

**Pessimistic** (`SELECT … FOR UPDATE`): the row is locked until commit.

```java
// Two concurrent refreshes would both read the same version, both pass, and fork
// the session into two valid token chains.
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<Session> findForRotation(@Param("id") UUID id);
```

Pessimistic when the loser must be *serialised*, not just detected — a replay must be
told it's a replay, not asked to retry. **A lock outside a transaction is released
immediately: all cost, no protection.**

---

## 7. Keep it short, keep it local

The bracket holds a pooled connection. Never inside: **HTTP calls, Kafka sends, MinIO
uploads, file IO, sleeps, retry loops.**

```java
// OAuthService.complete — deliberately not @Transactional
exchange(...);                                        // network
loadProfile(...);                                     // network
transactionTemplate.execute(s -> linkOrCreate(...));  // ← bracket starts here
```

> **Slow work first. Bracket last. Writes only.**

`OutboxPoller` spans a Kafka round trip and is the one exception — defensible only
because its locks are contended by other pollers alone (`READPAST` skips them) and
`send-timeout` bounds the wait. It needed a written justification. That's the bar.

---

## 8. Transactions stop at the database

They cover one connection to one database. Not Redis, not Kafka, not MinIO. Writing to
two systems is the **dual write**, and every version leaks:

| Approach | Failure mode |
|---|---|
| Write inline, inside the bracket | Rollback leaves the other system wrong |
| `@TransactionalEventListener(AFTER_COMMIT)` | Crash between commit and send loses it |
| **Outbox** — intent as a row in the same transaction, published later | None. Costs a table and a poller. |

Pick by which direction fails safe. `revoke()` writes Redis inside the bracket:
Redis-revoked with a live DB row fails *closed*; the reverse fails *open*.

**Anything computed in Java doesn't roll back either** — timestamps, UUIDs, counters.

---

## 9. Testing

Two opposite failures, both in this repo's history.

- **A unit test cannot see a proxy.** `new OAuthService(...)` makes every annotation
  inert. 23 green tests coexisted with a `@Transactional` that never applied.
  Transactional behaviour needs a Spring context. No exceptions.
- **`@Transactional` on a test supplies a bracket production may not have.** A
  `MANDATORY` method passes in `OutboxTest` because *the test* opened one.
  `AuthFlowTest` is deliberately not `@Transactional` — rolling back would
  hide the committed revocation it exists to check.

Testing the transaction itself → the test must not be `@Transactional`. Testing
something else and just want a clean DB → it should be. **To prove atomicity, assert
the negative:** force a failure mid-unit, assert nothing survived.

---

## 10. Traps — all silent

1. `this.method()` — skips the proxy
2. `private` / `final` — never proxied
3. Catching the exception — no rollback
4. Checked exception — commits
5. Inner `setRollbackOnly` — outer gets `UnexpectedRollbackException`
6. Lazy read after the bracket — hidden in dev by `open-in-view: true`
7. `save()` on a managed entity is a no-op; forgetting it on a detached one does nothing
8. Network call inside — pool exhaustion
9. Redis/Kafka write inside — not covered by the rollback
10. `REQUIRES_NEW` — second connection, deadlock risk
11. Lock outside a transaction — protects nothing
12. `@Transactional` on the test — false pass

---

## 11. Rules of thumb

- The bracket goes on **the service method that is one unit of work**. Not the
  controller (spans serialisation), not the repository (each call its own transaction
  is the opposite of what you wanted).
- If you can't name the unit of work in a sentence, the boundary is wrong.
- `readOnly = true` on every read.
- `REQUIRED` unless you can say why not. `MANDATORY` only for fragments that must never
  commit alone.
- Never touch isolation; reach for a lock.
- Load everything the caller reads before it closes.
- Any second system is a dual write — choose the failure mode, or use the outbox.
- If a transaction matters, an integration test forces a failure and asserts nothing
  survived. Green unit tests are not evidence an annotation applied.

---

## Cheat sheet

```java
@Transactional                                          // REQUIRED, rollback on RuntimeException
@Transactional(readOnly = true)                         // every query method
@Transactional(propagation = Propagation.MANDATORY)     // fragment; caller owns the bracket
@Transactional(propagation = Propagation.REQUIRES_NEW)  // survives caller's rollback (2nd connection!)
@Transactional(rollbackFor = Exception.class)           // include checked exceptions
@Transactional(noRollbackFor = X.class)                 // throw but keep the writes — repeat on outer
@Transactional(timeout = 5)                             // seconds

transactionTemplate.execute(status -> { ... });         // when the caller must not be transactional
status.setRollbackOnly();                               // programmatic doom

@Lock(LockModeType.PESSIMISTIC_WRITE)                   // SELECT … FOR UPDATE (needs a transaction)
@Version                                                // optimistic; conflict throws at flush
@EntityGraph(attributePaths = "roles")                  // fetch before the bracket closes
```