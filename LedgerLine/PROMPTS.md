# AI development log

**Date note:** The source conversation available for entries 1–4 was summarized and did not retain original timestamps or full assistant transcripts. `2026-11-09` is the date these entries were recorded, not a claim about when each original prompt was sent. Outputs/corrections below distinguish conversation-summary evidence from this working tree. No GitHub commit links are listed where no corresponding committed change exists.

## 1. Role-based admin and auditor dashboards

**Date recorded:** 2026-11-09

**Prompt given to AI:** “Implement the Admin and Auditor dashboards for LedgerLine.” The request specified user/account totals, transactions, deposits/withdrawals, audit records, suspicious flags, account management, auditor statements/ledger review, admin authorization, auditor read-only access, and testing all three roles.

**AI-generated output:** Earlier work added role-aware React dashboard pages and Java/Spring governance APIs. During the later backend migration those APIs were not present in the initially incomplete Node scaffold.

**What was incorrect:** Migrating only a package manifest and Mongoose models did not preserve the dashboard API surface; leaving the old Java implementation also made the requested MERN backend incomplete.

**How I corrected it:** Implemented role-gated Express admin/auditor endpoints and read-only ledger verification, preserved the frontend paths, added role-focused integration coverage, and removed the Java implementation from the backend folder.

**Actual Git commit link:** None available. The related working-tree changes are not committed; no commit URL is claimed.

## 2. Financial integrity, idempotency, and concurrency tests

**Date recorded:** 2026-11-09

**Prompt given to AI:** “Perform complete automated testing of LedgerLine” using JUnit 5, Mockito, and Spring Boot integration testing for money conservation, balanced postings, nonnegative balances, duplicate requests, changed idempotency payloads, concurrent withdrawals/transfers, unauthorized access, rollback, and ledger immutability.

**AI-generated output:** The earlier Java suite reported 41 tests passing, but six MySQL concurrency tests were skipped when test-database credentials were unavailable to the test process.

**What was incorrect:** A green general test run with skipped real-database concurrency tests did not establish that the locking scenarios had run. After changing backend databases, the old Java test approach also no longer matched the shipped backend.

**How I corrected it:** Added Node HTTP integration tests against an ephemeral, real MongoDB replica set. Tests execute parallel requests and inspect persisted balances, transaction records, ledger entries, idempotency outcomes, authorization, rollback, and demo seeding. On 2026-11-09, `npm test` completed with 16 passed, 0 failed, 0 skipped.

**Actual Git commit link:** None available. The related tests and implementation are not committed; no commit URL is claimed.

## 3. Java-to-MERN backend migration

**Date recorded:** 2026-11-09

**Prompt given to AI:** “change java to mern stack in backend folder”.

**AI-generated output:** The first migration work added a Node project manifest, environment template, and Mongoose model definitions.

**What was incorrect:** That scaffold had no Express server, authentication middleware, route implementations, or database-backed verification, while the Java/Maven sources remained in place.

**How I corrected it:** Completed the Express app/server, authentication and authorization, account/statement/admin/auditor APIs, transactional money services, seed/testing support, and removed the Java sources and Maven configuration. API paths remain aligned with the React client.

**Actual Git commit link:** None available. The migration is still an uncommitted worktree change; no commit URL is claimed.

## 4. Starting the backend

**Date recorded:** 2026-11-09

**Prompt given to AI:** “run backend”.

**AI-generated output:** The API startup was attempted against the available local MongoDB service.

**What was incorrect:** The local service was standalone MongoDB, but the application correctly requires replica-set transactions. Claiming the backend was running or silently disabling transactions would have been inaccurate and unsafe.

**How I corrected it:** Confirmed the local service had no replica-set configuration, reported the startup failure, and added a local Compose evaluation setup that initializes a single-node replica set. Docker was unavailable in this environment, so that Compose startup remains unverified here.

**Actual Git commit link:** None available. No committed backend-startup or Compose change exists yet.

## 5. Final-evaluation and submission preparation

**Date recorded:** 2026-11-09

**Prompt given to AI:** “Prepare LedgerLine for final evaluation and GitHub submission” with requirements for README, threats, dated prompts, API documentation, deployment, secret handling, demo credentials, and a five-minute demo.

**AI-generated output:** Prepared evaluation documentation, a complete API reference, threat mitigations/tests/accepted risks, Dockerfiles and a local replica-set Compose stack, generated local-only demo credentials, and duplicate/concurrent-withdrawal demo automation. On 2026-11-09, the frontend production build passed and both package audits reported zero vulnerabilities.

**What was incorrect:** The request named MySQL for deployment, but the implemented backend uses MongoDB. Treating MySQL as the active database would have produced misleading documentation. Public or committed demo passwords would also violate the secret-handling requirement.

**How I corrected it:** Asked which database the evaluation should use; the user chose MongoDB. Documented that actual stack, generated random demo passwords only at seed time into ignored local storage, and explicitly marked clean GitHub-clone and Docker deployment verification as incomplete rather than claiming success.

**Actual Git commit link:** None available. This evaluation-preparation work is uncommitted; no commit URL is claimed.
