#!/usr/bin/env python3
"""Check the Android app's camera rides against the footage the camera actually recorded (run in the WSL venv: needs numpy).

  python validate_app_clips.py PHONE_RIDES_DIR CLIPS_DIR CAM_IMU_DIR

PHONE_RIDES_DIR holds the pulled ride-* folders (events.jsonl, meta.json, imu.csv); CLIPS_DIR the copied DJI_*.MP4 files in
time order; CAM_IMU_DIR the `<clip>.imu.json` files with the camera's per-frame acceleration (djmd). Rides and clips are
paired by order. For each pair it compares the clip's length and header time with the app's estimate, and, where both devices
moved, slides the camera's acceleration against the phone's to measure the true first-frame time.
"""
import csv
import datetime as dt
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from analyze_trials import mp4_info  # noqa: E402


def moving_average(x, n):
    k = np.ones(n) / n
    return np.convolve(x, k, mode="same")


def load_phone(ride: Path):
    t, v = [], []
    with open(ride / "imu.csv") as f:
        r = csv.reader(f)
        next(r)
        for row in r:
            if int(row[1]) == 10:                     # linear acceleration (gravity removed)
                t.append(int(row[0]) / 1e9)
                v.append([float(x) for x in row[2:5]])
    t = np.array(t)
    return t, np.linalg.norm(np.array(v), axis=1)


def load_camera(path: Path, fps=59.94):
    d = json.loads(path.read_text())
    a = np.array(d["a"], dtype=float)                 # per-frame acceleration vector from djmd
    n = int(0.5 * fps)
    hp = a - np.column_stack([moving_average(a[:, i], n) for i in range(3)])
    return np.arange(len(a)) / fps, np.linalg.norm(hp, axis=1)


def best_offset(t_cam, c, t_phone, p, first_frame_est):
    """Offset delta (s) to add to the app's first-frame estimate so the camera and phone motion signals agree."""
    best = []
    deltas = np.arange(-2.5, 2.5, 0.005)
    for d in deltas:
        tt = first_frame_est + d + t_cam
        ok = (tt >= t_phone[0]) & (tt <= t_phone[-1])
        if ok.sum() < 60:
            best.append(-1)
            continue
        pi = np.interp(tt[ok], t_phone, p)
        ci = c[ok]
        if pi.std() < 1e-9 or ci.std() < 1e-9:
            best.append(-1)
            continue
        best.append(float(np.corrcoef(pi, ci)[0, 1]))
    best = np.array(best)
    i = int(np.argmax(best))
    far = np.abs(deltas - deltas[i]) > 0.3
    second = float(best[far].max()) if far.any() else float("nan")
    zero = float(best[int(np.argmin(np.abs(deltas)))])
    return float(deltas[i]), float(best[i]), second, zero


def main():
    rides_dir, clips_dir, imu_dir = map(Path, sys.argv[1:4])
    rides = [d for d in sorted(rides_dir.glob("ride-*")) if (d / "events.jsonl").exists()]
    clips = sorted(clips_dir.glob("*.MP4"))
    print(f"{len(rides)} camera rides, {len(clips)} clips\n")
    rows = []
    for ride, clip in zip(rides, clips):
        ev = {j["event"]: j for j in (json.loads(l) for l in (ride / "events.jsonl").read_text().splitlines() if l.strip())}
        meta = json.loads((ride / "meta.json").read_text())
        info = mp4_info(clip)
        vid = next(t for t in info["tracks"] if t["type"] == "vide")
        dj = next(t for t in info["tracks"] if t.get("fourcc") == "djmd")
        start, stop = ev["camera_start"], ev["camera_stop"]
        span = stop["clip_span_estimate_utc_ms"]
        est_len = (span[1] - span[0]) / 1000
        flag_utc = start["recording_flag"]["utc_ms"]
        header = info["created"]
        header_minus_flag = header.timestamp() - flag_utc / 1000
        row = {
            "ride": ride.name[5:], "clip": clip.name,
            "video_s": vid["dur"], "frames": vid["samples"], "djmd": dj["samples"],
            "estimated_s": est_len, "length_error_s": vid["dur"] - est_len,
            "header_minus_flag_s": header_minus_flag,
        }
        # motion alignment
        flag_elapsed = start["recording_flag"]["elapsed_ns"] / 1e9
        first_frame_est = flag_elapsed + 0.255
        tp, p = load_phone(ride)
        tc, c = load_camera(imu_dir / (clip.stem + ".imu.json"))
        row["phone_motion_sd"] = float(p.std())
        row["camera_motion_sd_g"] = float(c.std())
        d, r, second, zero = best_offset(tc, c, tp, p, first_frame_est)
        row.update({"offset_s": d, "corr": r, "corr_second_peak": second, "corr_at_zero": zero})
        rows.append(row)

    for r in rows:
        print(r["clip"], "<-", r["ride"])
        print(f"  video {r['video_s']:.3f}s, {r['frames']} frames, djmd samples {r['djmd']} ({'match' if r['frames'] == r['djmd'] else 'MISMATCH'})")
        print(f"  app estimate {r['estimated_s']:.3f}s -> actual minus estimate {r['length_error_s']:+.3f}s")
        print(f"  clip header time minus the phone's recording-flag time: {r['header_minus_flag_s']:+.2f}s (header has 1 s resolution)")
        print(f"  motion: phone sd {r['phone_motion_sd']:.3f} m/s2, camera sd {r['camera_motion_sd_g']:.3f} g; "
              f"best offset {r['offset_s']*1000:+.0f} ms (r={r['corr']:.2f}, next peak r={r['corr_second_peak']:.2f}, r at 0 ms={r['corr_at_zero']:.2f})")
    errs = [r["length_error_s"] for r in rows]
    print(f"\nlength error (actual - estimate): mean {np.mean(errs):+.3f}s, min {min(errs):+.3f}, max {max(errs):+.3f}, sd {np.std(errs):.3f}")
    good = [r for r in rows if r["corr"] > 0.5 and r["corr"] - r["corr_second_peak"] > 0.15]
    print(f"clips with an unambiguous motion alignment (r>0.5 and clearly above the next peak): {len(good)} of {len(rows)}")
    for r in good:
        print(f"  {r['clip']}: first frame is {r['offset_s']*1000:+.0f} ms from the app's estimate (r={r['corr']:.2f})")


if __name__ == "__main__":
    main()
