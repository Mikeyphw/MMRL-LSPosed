# R1A — profile-aware, read-only saved scope index

Source pin: `6c89740f86fb750ad9dc108f02065719314a7595`.

## Problem

Apps reverse lookup previously threw an error on **any** saved Vector scope with
Android user ID other than 0. One work-profile scope could hide the whole saved
scope index. Additionally, an installed package name for the manager's own user
cannot prove that package is installed in every other Android user.

## Change

- Reverse index keys by `packageName/userId`; no dropping or merging other-user
  memberships. Maintainer must explicitly build/rebuild the index.
- `Process.myUid()/100_000` identifies the Android user for the manager's
  visible package inventory. Only that user's packages are marked installed.
- Other-user entries are labeled **other profile / installation not checked**;
  local-user missing entries say **not installed or not visible**.
- Search supports package names, labels, module names and `user:<number>`.
- Protect existing S08 mutation path unchanged: scope writes are still guarded,
  journaled, limited to primary user, and must never be used as a test against
  unrelated modules.
- Include JVM tests for profile separation and malformed identities.

## Qualification boundary

This overlay has no local Android build. Hosted GitHub Actions must compile,
run the JVM suite, lint and API36 emulator tests. After CI, test the index
manually on physical hardware only when multiuser scopes actually exist; do not
create/alter a work profile simply for this check. The current S11 manual
acceptance remains complete for its original source commit.
