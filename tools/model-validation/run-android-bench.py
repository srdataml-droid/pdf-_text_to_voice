#!/usr/bin/env python3
"""Run a pinned CPU llama-bench on an explicitly selected Android device.

Default is a local dry run. --run copies a binary/model into a new device temp
directory, executes it, samples memory, and saves the untouched output locally.
It never changes airplane mode, performance modes, app data, or the adb server.
"""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import shlex
import socket
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parent


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(4 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", required=True)
    parser.add_argument("--model", type=Path, required=True, help="Verified local GGUF")
    parser.add_argument("--binary", type=Path, default=ROOT / "bin/llama-bench-android-arm64")
    parser.add_argument("--serial", help="Explicit adb device serial; required for --run")
    parser.add_argument("--adb", default="/usr/bin/adb")
    parser.add_argument("--output", type=Path, default=ROOT / "runs")
    parser.add_argument("--timeout", type=int, default=1800)
    parser.add_argument("--run", action="store_true", help="Actually stage and run on the device")
    args = parser.parse_args()
    manifest = json.loads((ROOT / "candidates.json").read_text())
    matches = [c for c in manifest["candidates"] if c["id"] == args.candidate]
    if len(matches) != 1:
        parser.error("Unknown candidate id")
    candidate = matches[0]
    # A dry run is useful even while weights are unavailable and performs no adb calls.
    timestamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    remote = "/data/local/tmp/anchor-model-bench-" + timestamp
    command = [remote + "/llama-bench", "-m", remote + "/model.gguf",
               "-p", "256,1024", "-n", "128", "-t", "2,4", "-r", "3",
               "-b", "128", "-ub", "64", "-ngl", "0", "-lm", "mmap", "-o", "json"]
    plan = {"schema_version": 1, "kind": "synthetic_android_cpu_benchmark", "candidate": candidate,
            "runtime_commit": manifest["runtime_commit"], "device_directory": remote,
            "command": command, "run_requested": args.run,
            "scope": "Synthetic kernel throughput; excludes tokenization and sampling. Not app TTFT or task accuracy.",
            "cache_note": "OS file cache is uncontrolled; benchmark warmup remains enabled.",
            "context_note": "Upstream llama-bench allocates context from its test sizes; this does not reproduce the app's fixed 1024 context."}
    if not args.run:
        print(json.dumps(plan, indent=2))
        return
    if not args.serial:
        parser.error("--run requires --serial")
    if args.model.stat().st_size != candidate["bytes"] or sha256(args.model) != candidate["sha256"]:
        parser.error("Model size or SHA-256 differs from the pinned official candidate")
    if not args.binary.is_file():
        parser.error("Build the Android benchmark binary first")
    # Guard against adb's normal automatic daemon launch. Respect a nonstandard
    # configured server port if the operator deliberately supplied one.
    import os
    port = int(os.environ.get("ANDROID_ADB_SERVER_PORT", "5037"))
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=1):
            pass
    except OSError:
        parser.error("No existing local adb server; ask the device operator to connect it first")
    adb = [args.adb, "-s", args.serial]

    def invoke(parts, timeout=30):
        return subprocess.run(adb + parts, text=True, capture_output=True, timeout=timeout, check=True).stdout

    def shell(parts, timeout=30):
        return invoke(["shell", shlex.join(parts)], timeout=timeout)

    if invoke(["get-state"]).strip() != "device":
        parser.error("Selected device is not authorized and ready")
    out = args.output / (timestamp + "-" + args.candidate)
    out.mkdir(parents=True, exist_ok=False)
    plan["binary_sha256"] = sha256(args.binary)
    (out / "plan.json").write_text(json.dumps(plan, indent=2) + "\n")

    def snapshot(suffix):
        readings = {
            "device": ["getprop", "ro.product.model"],
            "manufacturer": ["getprop", "ro.product.manufacturer"],
            "android_release": ["getprop", "ro.build.version.release"],
            "android_api": ["getprop", "ro.build.version.sdk"],
            "abi": ["getprop", "ro.product.cpu.abi"],
            "cpuinfo": ["cat", "/proc/cpuinfo"],
            "meminfo": ["cat", "/proc/meminfo"],
            "thermal": ["dumpsys", "thermalservice"],
            "battery": ["dumpsys", "battery"],
            "airplane_mode": ["settings", "get", "global", "airplane_mode_on"],
            "storage": ["df", "-k", "/data/local/tmp"],
        }
        for name, parts in readings.items():
            try:
                content = shell(parts)
            except Exception as exc:
                content = "UNAVAILABLE: " + str(exc)
            (out / (name + "-" + suffix + ".txt")).write_text(content)

    snapshot("before")
    shell(["mkdir", remote])
    invoke(["push", str(args.binary), remote + "/llama-bench"], timeout=120)
    invoke(["push", str(args.model), remote + "/model.gguf"], timeout=600)
    shell(["chmod", "700", remote + "/llama-bench"])
    remote_hash = shell(["sha256sum", remote + "/model.gguf"]).split()[0]
    if remote_hash != candidate["sha256"]:
        raise RuntimeError("Device model checksum differs after transfer")
    wrapper = "echo $$ > " + shlex.quote(remote + "/pid") + "; exec " + shlex.join(command)
    started = time.monotonic()
    completed = threading.Event()
    pid = None
    samples = []
    with (out / "llama-bench.json").open("w") as stdout, (out / "llama-bench.stderr.txt").open("w") as stderr:
        process = subprocess.Popen(adb + ["shell", "sh -c " + shlex.quote(wrapper)], stdout=stdout, stderr=stderr)
        try:
            while process.poll() is None and time.monotonic() - started < 15:
                try:
                    possible = shell(["cat", remote + "/pid"]).strip()
                    if possible.isdigit():
                        pid = possible
                        break
                except Exception:
                    pass
                time.sleep(0.2)

            def sample():
                with (out / "memory-samples.jsonl").open("w") as stream:
                    while not completed.is_set():
                        row = {"elapsed_seconds": time.monotonic() - started}
                        try:
                            mem = shell(["cat", "/proc/meminfo"], timeout=10)
                            available = re.search(r"^MemAvailable:\s+(\d+)\s+kB", mem, re.M)
                            row["available_ram_kb"] = int(available.group(1)) if available else None
                            raw = shell(["dumpsys", "meminfo", pid], timeout=10) if pid else "No PID"
                            row["process_meminfo_raw"] = raw
                            pss = re.search(r"TOTAL PSS:\s+(\d+)", raw)
                            if not pss:
                                pss = re.search(r"^\s*TOTAL\s+(\d+)\s", raw, re.M)
                            row["pss_kb"] = int(pss.group(1)) if pss else None
                        except Exception as exc:
                            row["error"] = str(exc)
                        samples.append(row)
                        stream.write(json.dumps(row) + "\n")
                        stream.flush()
                        completed.wait(1)

            sampler = threading.Thread(target=sample, daemon=True)
            sampler.start()
            try:
                code = process.wait(timeout=args.timeout)
                failure = None
            except subprocess.TimeoutExpired:
                failure = "timeout"
                if pid:
                    shell(["kill", "-TERM", pid])
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                code = process.returncode
            finally:
                completed.set()
                sampler.join(timeout=25)
        except BaseException:
            if pid:
                try:
                    shell(["kill", "-TERM", pid])
                except Exception:
                    pass
            process.terminate()
            raise
    snapshot("after")
    measured = None
    parse_error = None
    try:
        measured = json.loads((out / "llama-bench.json").read_text())
    except (ValueError, OSError) as exc:
        parse_error = str(exc)
    runtime_match = bool(isinstance(measured, list) and measured and all(
        isinstance(row.get("build_commit"), str) and len(row["build_commit"]) >= 7
        and manifest["runtime_commit"].startswith(row["build_commit"]) for row in measured))
    pss_values = [row["pss_kb"] for row in samples if row.get("pss_kb") is not None]
    available_values = [row["available_ram_kb"] for row in samples if row.get("available_ram_kb") is not None]
    report = {"exit_code": code, "failure": failure, "parse_error": parse_error,
              "runtime_commit_matches_pin": runtime_match,
              "elapsed_seconds_including_sampling": time.monotonic() - started,
              "benchmark_records": measured, "peak_sampled_process_pss_kb": max(pss_values, default=None),
              "minimum_sampled_available_ram_kb": min(available_values, default=None),
              "sample_count": len(samples), "limitations": [
                  "Host-driven sampling has overhead and may miss short peaks.",
                  "A CLI process has different memory limits and lifecycle behavior from an Android app.",
                  "Synthetic throughput does not establish app first-token time, cancellation or action accuracy.",
                  "No sustained thermal qualification or airplane-mode proof is inferred from this run."]}
    (out / "summary.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Saved observations to " + str(out.resolve()))
    print("Device staging directory retained: " + remote)
    raise SystemExit(0 if code == 0 and measured is not None and runtime_match else 1)


if __name__ == "__main__":
    main()
