# #65 repeat run (controller, 2026-09-25, master 6a7a161, read-only; build outputs only)

- `AttachmentsSectionViewModelTest` alone, per-task `--rerun`, 20 runs: 0 failures.
- Whole `:app:testDebugUnitTest`, `--rerun`, 3 runs: 968/0/0/0 each.
- Test JVM config in `app/build.gradle.kts`: only `unitTests.all { jvmArgs("--enable-native-access=ALL-UNNAMED") }`; no `maxParallelForks`, no `forkEvery` (Gradle default: one fork, tests serial in one JVM).
- The single 1.4 sighting (B08 fix round, `savingNothingIsSilent`) happened while another worktree's Gradle and the emulator were busy on the same host; it passed on three reruns and the next full run. Not reproduced in 23 runs here.
