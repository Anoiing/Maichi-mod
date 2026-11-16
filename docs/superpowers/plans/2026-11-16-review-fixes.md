# Review Findings Fix Plan

> **For agentic workers:** Use superpowers:executing-plans to implement these two fixes and verify each regression before proceeding.

**Goal:** Fix the two P2 findings in the review of `74770b7..5db95f2`.

**Architecture:** Keep reception state in the existing service and connection. The call page must not request screen wake while its key/deadlines are awaiting validation. Treat a future Cainiao refresh timestamp as invalid, publish one recovery refresh, and persist the corrected time through the existing controller.

**Tech Stack:** Kotlin, Android Activity/Binder, AndroidJUnit4, JUnit 4.

**Spec:** [dedicated intercom design](../specs/2026-11-13-dedicated-intercom-design.md) C01/C03 and [home information plan](2026-11-16-home-weather-cainiao-design-and-plan.md); the current review supplies the two reproductions.

## Constraints and Review Focus

- Retain valid locked-screen incoming-call wake, including delayed service validation.
- An expired initial intent or an expired intent delivered to a stopped call page must not wake the display.
- Old call intents must not answer, replace or dismiss the current call.
- Keep Cainiao's verified-version/visible-home gates and 30-second return / 30-minute regular cooldowns.
- Use local samples and isolated emulators only; do not install on the field tablet or issue physical door/elevator commands.

## Task 1: Defer call wake until validation

**Files:** `IncomingCallActivity.kt`, `IncomingCallInteractionTest.kt`; `AppConnectionTest.kt` only for the cold-start-independent subscription test fixture.

**Interface:** Preserve the current call key, service binding and action APIs.

- [x] Add regressions for expired cold/reused presentation while validation is queued, and a valid queued presentation that must wake after validation.
- [x] Run the regressions against the original implementation and confirm the premature-wake failure.
- [x] Move wake permission behind the current-key, deadline and active-call validation; preserve pending validation across screen-off lifecycle transitions if needed.
- [x] Verify new regressions and the existing locked incoming, stale intent, permission, recreation and return-to-app cases.

## Task 2: Recover Cainiao refresh after clock correction

**Files:** `home/HomeInformation.kt`, `home/HomeInformationTest.kt`.

**Interface:** Preserve `shouldRefreshCainiao(version, code, visible, now, last, returning): Boolean` and the controller's save-before-broadcast path.

- [x] Add failing cases for a seven-day and a one-second clock rollback, plus the cooldown following the recovery refresh.
- [x] Run `:app:testDebugUnitTest --tests '*HomeInformationTest'` and confirm the future-timestamp failure.
- [x] Allow one refresh when `last > now`, only after the existing visibility/version gates. The controller already saves `now` before broadcasting.
- [x] Verify all JVM and Python tests, Debug/Release builds and Lint; run the device regressions on isolated API 31/36 emulators where available.

## Delivery

- [x] Review the final diff and record verification evidence below. Leave the fixes reviewable on the current branch.

## Verification

- Baseline: three pending-presentation tests failed with `Unvalidated call presentation woke the display`; both new clock-correction tests failed against the original implementation.
- Clock correction: `HomeInformationTest` 9/9 and full JVM suite 109/109 passed; Python 9/9 passed.
- Initial implementation: expired cold/reused page and normal locked incoming wake passed on API 36. Delayed valid-page wake passed after isolating the independent SystemUI full-screen notification in that test.
- Independent final review found that synchronous initial `observe` could detach before its returned cancellation was assigned. `leavingDuringValidationDoesNotRetainTheHiddenPagesSubscription` reproduced it (expected 1 observer, actual 2). The fix checks the remaining binding after `observe` returns and cancels any subscription no longer needed. That regression passed on the final API 36 build.
- Final Debug/Release builds and Lint passed; JVM 109/109 passed. Lint reports 0 errors and 36 existing warnings in each variant; reported source lines are unchanged from the baseline.
- The initial API 31 run completed 105 tests with two failures because its environment had `hw.camera.front=none`: `PresenceDetectorTest.bundledModelAndActualCameraCanStopAndRestartOnTheEmulator` and `StandbyInteractionTest.externalAppPausesPresenceButKeepsReception`. The emulator was relaunched with `-camera-front emulated`; the final full run passed 106/106 tests with no failures or skips.
- The pre-existing API 36 emulator likewise exposes only a back camera (`dumpsys media.camera`); its full 106-test run failed the same two camera cases and passed the remaining 104. A separate AVD with an emulated front camera was created without restarting the pre-existing emulator. Its 106-test full run passed the two camera cases and all other functional cases; only the old cold-replay timeout described below failed (105/106).
- On the fresh API 36 AVD, `AppConnectionTest.observerReceivesCurrentStateAndCanDetach` hit its existing 5-second timeout while loading the large replay assets. Its transport boundary is now an isolated wired fake, preserving the initial callback, main-thread delivery, option propagation and cancellation checks without testing replay startup speed. The updated `AppConnectionTest` class passed 4/4 tests in 0.985 seconds on the existing API 36 emulator and 4/4 in 0.758 seconds on the same isolated AVD used by the full run. The full run was not repeated after this test-only fixture correction; the other 105 cases had already passed on the identical production APK.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `b3443ceca584756ebd1ce57d4cfb702bb5258f4a879b827f3493fa1c6f71771c`. No field tablet installation or physical door/elevator operations form part of this verification.

Final state: both reviewed P2 findings and the final-review subscription cleanup finding are fixed. Six new regression tests are included. Delivery branch: `codex/home-weather-cainiao`. No push, merge or field-tablet installation forms part of this change. The temporary emulators created for this verification were shut down.
