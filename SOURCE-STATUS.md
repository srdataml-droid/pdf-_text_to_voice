# Source status — 13 November 2026

Anchor 0.5.0 source, including the goals timeline, is present. The developer test APK was installed and launched on an Android 13 HONOR device in a separate local verification step.

The personal daily routine preset and its loader were removed from this source snapshot. Creating a fresh app database produces an empty calendar. No phone database, personal tasks, saved routines, alarms state, memories, conversations, purchase goals, books, or reading notes are included. Generic optional suggestions and the routine builder remain available.

Validation for this update: 18 existing JVM tests for routine planning, period planning and goals pass; the changed planner and period UI compile against the Android API and existing compiled app dependencies. A new sanitized APK has not been built or installed. The older 0.5.0 APK still contains the earlier preset. Alarm delivery was not exercised in this update.

Gradle wrapper and font assets are included. Rebuild the native JNI runtime with scripts/build-native.sh. Native runtime binaries, model weights and signing keys are excluded. Android code is a reference implementation for a future web port, not a Vercel web app.
