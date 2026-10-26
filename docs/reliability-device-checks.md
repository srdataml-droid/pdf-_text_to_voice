# Physical-device listening checks — v1.4.2

The automated checks use synthetic text and simulated audio timing. Complete these with bundled Piper Lessac on the actual phone before describing screen-off reliability as verified. Read/choose the updated agreement yourself. No one needs to collect or open your personal books for the checks.

| Scenario | Expected outcome |
| --- | --- |
| Lock screen for five minutes, then 30–60 minutes | Narration keeps moving; brief buffering recovers without tapping Play. Extend to a longer session afterward. |
| Chapter 26 → 27 → 28 | No stop at a chapter boundary. A failure must show a message and retain passage progress. |
| Notification Pause, wait, then Play | Silence while paused; resume the same in-session audio position. |
| Pause while initial preparation is running | Preparation is cancelled; no delayed automatic start. Resume can prepare a fresh plan. |
| Headphones disconnect | Reading pauses; it does not suddenly speak through the phone speaker. |
| Temporary audio interruption / phone call | Speech pauses; regain focus can resume unless you manually paused. |
| Speech generation falls behind | Bounded look-ahead; automatic continuation when audio arrives. Two minutes without progress reports an error instead of waiting forever. |
| A malformed passage keeps failing | One reset/retry, prepared speech finishes, then an explicit error. Unread content is not silently skipped. |
| Insufficient local storage during preparation | Clear error; partial plan removed; existing library is not deleted. |
| Force-stop or restart phone | Playback can stop. Reopen the book and press Play; saved recovery is passage based and can repeat part of a passage. |
| Change book, voice or passage rapidly | No old-book audio taking over a newer request. |
| Very large book | Opening can take time and still depends on source size and phone memory. Native plan limits are one million passages / 256 MiB text frames plus index; no universal chapter-count guarantee. |

For a problem, note app version, bundled voice, whether the screen was locked, approximate elapsed time, whether it always happens at the same passage and the displayed error. Share error details, not book contents, unless you choose otherwise. Native diagnostic logs contain passage indices/error classes; bridge argument logging is off.

Background playback currently covers bundled Piper Lessac. Kokoro, Kitten and imported Piper voices retain foreground playback. The watchdog cancels a stalled session, but cannot forcibly restart a native runtime call that never yields; Android process/battery restrictions also remain outside a guarantee of continuous playback.
