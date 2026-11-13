# Dependency review, 2026-10-28

WorkManager `androidx.work:work-runtime:2.11.2`: risk posture UNKNOWN. Exact version and Android requirements were checked against official Android release notes (https://developer.android.com/jetpack/androidx/releases/work). The package supports this app minSdk26/compileSdk36. This is the existing stable maintenance line, with periodic work failure and SecurityException fixes.

Endor dependency-reviewer package-risk profile: live risk/vulnerability/license/policy lookup unavailable: no exposed Endor risk tool and no endorctl executable. No vulnerability-free claim is made; no tenant namespace or approval is inferred. Follow-up: run an authenticated exact-coordinate dependency risk review before distribution beyond personal testing. The current implementation uses official Google Maven coordinates.

Existing build dependencies: AGP8.13.0; JUnit4.13.2 (tests only). Native runtime pinned llama.cpp d05706b4f32a545814856e98ca518292585443b9. This review does not establish transitive dependency security.
