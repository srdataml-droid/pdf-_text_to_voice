package com.novaxis.localreader;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic text only. Run on a plain JVM with a 32 MB heap; no Android/device dependencies. */
public class ReaderReliabilityCheck {
    static int passed;
    interface Check { void run() throws Exception; }
    static void check(String label, Check work) throws Exception { work.run(); passed++; System.out.println("PASS " + label); }
    static void require(boolean condition) { if (!condition) throw new AssertionError(); }
    static void rejects(Check work) throws Exception { try { work.run(); } catch (IOException expected) { return; } throw new AssertionError("Expected IOException"); }
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("synthetic-reader-check-").toFile();
        try {
            check("1,000 chapters / 201,000 passages exceed old text/count limits with a 32 MB heap", () -> {
                File folder = new File(root, "large");
                String body = "synthetic passage ".repeat(14);
                try (NarrationPlan.Writer writer = new NarrationPlan.Writer(folder, 201000)) {
                    for (int start = 0; start < 201000; start += 128) {
                        List<String> batch = new ArrayList<>();
                        for (int i = start; i < Math.min(201000, start + 128); i++) batch.add("Chapter " + (i / 201 + 1) + " passage " + i + " — " + body);
                        writer.append(batch);
                    }
                    writer.finish();
                }
                require(new File(folder, "text.bin").length() > 32L * 1024 * 1024);
                try (NarrationPlan plan = new NarrationPlan(folder)) {
                    require(plan.size() == 201000);
                    for (int chapter = 0; chapter < 1000; chapter++) {
                        require(plan.text(chapter * 201).startsWith("Chapter " + (chapter + 1) + " passage " + chapter * 201 + " — "));
                        require(plan.text(chapter * 201 + 200).contains("passage " + (chapter * 201 + 200)));
                    }
                    require(plan.text(200999).startsWith("Chapter 1000 passage 200999"));
                }
            });
            check("incomplete transfer cannot be committed", () -> {
                try (NarrationPlan.Writer writer = new NarrationPlan.Writer(new File(root, "incomplete"), 2)) {
                    writer.append(List.of("first")); rejects(writer::finish);
                }
            });
            check("oversized passages and batches fail explicitly", () -> {
                try (NarrationPlan.Writer writer = new NarrationPlan.Writer(new File(root, "bounded"), 200)) {
                    rejects(() -> writer.append(Collections.nCopies(129, "text")));
                    rejects(() -> writer.append(List.of("x".repeat(4097))));
                }
            });
            check("corrupt text and partial index are rejected", () -> {
                File folder = new File(root, "corrupt");
                try (NarrationPlan.Writer writer = new NarrationPlan.Writer(folder, 1)) { writer.append(List.of("hello")); writer.finish(); }
                try (RandomAccessFile text = new RandomAccessFile(new File(folder, "text.bin"), "rw")) { text.setLength(4); }
                try (NarrationPlan plan = new NarrationPlan(folder)) { rejects(() -> plan.text(0)); }
                try (RandomAccessFile index = new RandomAccessFile(new File(folder, "index.bin"), "rw")) { index.setLength(7); }
                rejects(() -> { try (NarrationPlan ignored = new NarrationPlan(folder)) {} });
            });
            check("temporary generation failure retries once with a reset", () -> {
                AtomicInteger attempts = new AtomicInteger(), resets = new AtomicInteger();
                String result = SpeechRecovery.run(() -> { if (attempts.incrementAndGet() == 1) throw new IOException("synthetic"); return "audio"; }, resets::incrementAndGet, () -> true);
                require(result.equals("audio") && attempts.get() == 2 && resets.get() == 1);
            });
            check("persistent failure stops after exactly two attempts", () -> {
                AtomicInteger attempts = new AtomicInteger();
                rejects(() -> SpeechRecovery.run(() -> { attempts.incrementAndGet(); throw new IOException("synthetic"); }, () -> {}, () -> true));
                require(attempts.get() == 2);
            });
            check("cancelled generation is never retried", () -> {
                AtomicInteger attempts = new AtomicInteger(), resets = new AtomicInteger();
                try { SpeechRecovery.run(() -> { attempts.incrementAndGet(); throw new InterruptedException(); }, resets::incrementAndGet, () -> true); throw new AssertionError(); }
                catch (InterruptedException expected) {}
                require(attempts.get() == 1 && resets.get() == 0);
                attempts.set(0);
                try { SpeechRecovery.run(() -> { attempts.incrementAndGet(); return "unused"; }, () -> {}, () -> false); throw new AssertionError(); }
                catch (InterruptedException expected) {}
                require(attempts.get() == 0);
            });
            check("watchdog distinguishes stalled output from pause and real progress", () -> {
                PlaybackWatchdog watchdog = new PlaybackWatchdog(0, 120000);
                require(!watchdog.expired(119999, false)); require(watchdog.expired(120000, false));
                watchdog.progress(120001); require(!watchdog.expired(200000, false));
                require(!watchdog.expired(500000, true)); require(!watchdog.expired(500001, false));
                require(watchdog.expired(620000, false));
            });
            System.out.println("Passed " + passed + " native reliability checks.");
        } finally {
            try (var paths = Files.walk(root.toPath())) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
}
