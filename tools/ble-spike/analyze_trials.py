#!/usr/bin/env python3
"""Match a `trials` log to the clips copied from the camera and measure what the camera actually did.

  python analyze_trials.py trials/trials-usb-unplugged-....json  C:\\path\\to\\clips        (needs ffmpeg on PATH)

For each clip (sorted by name, one per trial) it finds the first white flash of the PC screen in the video, which gives the
PC time of the clip's first frame (to within the display's own delay, assumed 0 here, so first-frame times are slightly
early), and compares clip length, header time and track layout with what the Bluetooth log says happened.
Run it where ffmpeg exists (the WSL venv python works: paths under /mnt/c).
"""
import datetime as dt
import json
import statistics as st
import struct
import subprocess
import sys
from pathlib import Path

EPOCH_1904 = dt.datetime(1904, 1, 1, tzinfo=dt.timezone.utc)


def mp4_info(path: Path):
    f = open(path, "rb")
    f.seek(0, 2)
    size = f.tell()

    def boxes(a, b):
        pos = a
        while pos + 8 <= b:
            f.seek(pos)
            sz, t = struct.unpack(">I4s", f.read(8))
            hdr = 8
            if sz == 1:
                sz = struct.unpack(">Q", f.read(8))[0]
                hdr = 16
            elif sz == 0:
                sz = b - pos
            if sz < hdr:
                return
            yield t, pos + hdr, pos + sz
            pos += sz

    info = {"tracks": []}
    for t, a, b in boxes(0, size):
        if t != b"moov":
            continue
        for t1, a1, b1 in boxes(a, b):
            if t1 == b"mvhd":
                f.seek(a1)
                v = f.read(1)[0]
                f.seek(a1 + 4)
                c, m, ts, dur = struct.unpack(">IIII", f.read(16)) if v == 0 else struct.unpack(">QQIQ", f.read(28))
                info["created"] = EPOCH_1904 + dt.timedelta(seconds=c)
                info["duration"] = dur / ts
            if t1 == b"trak":
                tr = {}
                for t2, a2, b2 in boxes(a1, b1):
                    if t2 != b"mdia":
                        continue
                    for t3, a3, b3 in boxes(a2, b2):
                        if t3 == b"mdhd":
                            f.seek(a3)
                            v = f.read(1)[0]
                            f.seek(a3 + (12 if v == 0 else 20))
                            ts, dur = struct.unpack(">II", f.read(8)) if v == 0 else struct.unpack(">IQ", f.read(12))
                            tr["dur"] = dur / ts
                        if t3 == b"hdlr":
                            f.seek(a3 + 8)
                            tr["type"] = f.read(4).decode("latin1")
                        if t3 == b"minf":
                            for t4, a4, b4 in boxes(a3, b3):
                                if t4 != b"stbl":
                                    continue
                                for t5, a5, b5 in boxes(a4, b4):
                                    if t5 == b"stsd":
                                        f.seek(a5 + 12)
                                        tr["fourcc"] = f.read(4).decode("latin1")
                                    if t5 == b"stsz":
                                        f.seek(a5 + 8)
                                        tr["samples"] = struct.unpack(">I", f.read(4))[0]
                info["tracks"].append(tr)
    return info


def frame_brightness(path: Path):
    """Mean grey level (0..255) of every video frame, from a 32x18 downscale."""
    cmd = ["ffmpeg", "-v", "error", "-nostdin", "-i", str(path), "-map", "0:v:0",
           "-vf", "scale=32:18,format=gray", "-f", "rawvideo", "-"]
    data = subprocess.run(cmd, capture_output=True, check=True).stdout
    fs = 32 * 18
    return [sum(data[i * fs:(i + 1) * fs]) / fs for i in range(len(data) // fs)]


def find_flash(b, fps, spacing_s):
    """Index of the first bright frame of the first three-flash pattern, or None.
    Returns (frame index, contrast = high - low grey level, edges found)."""
    s = sorted(b)
    lo, hi = s[int(len(s) * 0.10)], s[int(len(s) * 0.99)]
    if hi - lo < 8:
        return None, hi - lo, []
    thr = lo + 0.5 * (hi - lo)
    edges = [i for i in range(1, len(b)) if b[i] >= thr > b[i - 1]]
    tol = 0.08
    for k in range(len(edges) - 2):
        d1 = (edges[k + 1] - edges[k]) / fps
        d2 = (edges[k + 2] - edges[k + 1]) / fps
        if abs(d1 - spacing_s) < tol and abs(d2 - spacing_s) < tol:
            return edges[k], hi - lo, edges
    return None, hi - lo, edges


def utc(ns):
    return dt.datetime.fromtimestamp(ns / 1e9, dt.timezone.utc)


def main():
    log = json.loads(Path(sys.argv[1]).read_text())
    clips = sorted(Path(sys.argv[2]).glob("*.MP4"))
    trials = [t for t in log["trials"] if t.get("t_bit_on_ns")]
    print(f"{len(clips)} clips, {len(trials)} trials with a recording bit\n")
    rows = []
    for clip, tr in zip(clips, trials):
        info = mp4_info(clip)
        vid = next(t for t in info["tracks"] if t.get("type") == "vide")
        fps = vid["samples"] / vid["dur"]
        calls = tr.get("t_flash_calls_ns")
        spacing = (calls[2] - calls[0]) / 2e9 if calls and len(calls) == 3 else 0.24
        b = frame_brightness(clip)
        idx, contrast, edges = find_flash(b, fps, spacing)
        s = tr["t_start_sent_ns"]
        rel = lambda k: (tr[k] - s) / 1e6
        row = {
            "clip": clip.name,
            "tracks": [(t.get("type"), t.get("fourcc"), t.get("samples")) for t in info["tracks"]],
            "frames_decoded": len(b),
            "video_s": vid["dur"],
            "fps": fps,
            "flash_frame": idx,
            "flash_contrast": contrast,
            "flash_edges": edges,
            "header_utc": info["created"],
            "start_to_bit_on_ms": rel("t_bit_on_ns"),
            "start_to_stop_sent_ms": rel("t_stop_sent_ns"),
            "start_to_bit_off_ms": rel("t_bit_off_ns") if tr.get("t_bit_off_ns") else None,
        }
        if idx is not None and tr.get("t_flash_ns"):
            flash_vt = idx / fps
            row["flash_at_clip_s"] = flash_vt
            row["start_to_flash_ms"] = rel("t_flash_ns")
            first = row["start_to_flash_ms"] - flash_vt * 1000
            row["first_frame_after_start_ms"] = first
            row["first_frame_after_bit_on_ms"] = first - row["start_to_bit_on_ms"]
            row["last_frame_minus_stop_sent_ms"] = first + vid["dur"] * 1000 - row["start_to_stop_sent_ms"]
            pc_first = utc(s) + dt.timedelta(milliseconds=first)
            row["pc_first_frame_utc"] = pc_first
            row["camera_clock_behind_pc_s"] = (pc_first - row["header_utc"]).total_seconds()
        rows.append(row)

    for r in rows:
        print(r["clip"])
        print("  tracks:", r["tracks"], "| frames decoded:", r["frames_decoded"])
        print(f"  video {r['video_s']:.3f}s at {r['fps']:.2f} fps, header {r['header_utc']:%H:%M:%S}Z")
        if r["flash_frame"] is None:
            print(f"  no flash pattern found (grey-level contrast {r['flash_contrast']:.1f}, edges at frames {r['flash_edges'][:8]})")
            continue
        print(f"  first flash at clip t={r['flash_at_clip_s']:.3f}s (frame {r['flash_frame']}, contrast {r['flash_contrast']:.0f} grey levels)")
        print(f"  first frame: {r['first_frame_after_start_ms']:.0f} ms after the start command, "
              f"{r['first_frame_after_bit_on_ms']:+.0f} ms vs the recording-bit time")
        print(f"  last frame vs stop command sent: {r['last_frame_minus_stop_sent_ms']:+.0f} ms; "
              f"camera header clock behind the PC by {r['camera_clock_behind_pc_s']:.2f} s (header has 1 s resolution)")

    ok = [r for r in rows if r.get("first_frame_after_start_ms") is not None]
    if len(ok) >= 2:
        def stat(k):
            v = [r[k] for r in ok]
            return f"mean {st.mean(v):.0f}, min {min(v):.0f}, max {max(v):.0f}, sd {st.pstdev(v):.0f}"
        print("\nSUMMARY over", len(ok), "clips")
        print("  first frame after start command (ms):", stat("first_frame_after_start_ms"))
        print("  first frame after recording-bit (ms):", stat("first_frame_after_bit_on_ms"))
        print("  last frame minus stop-sent (ms):     ", stat("last_frame_minus_stop_sent_ms"))
        v = [r["camera_clock_behind_pc_s"] for r in ok]
        print(f"  camera clock behind PC (s): mean {st.mean(v):.2f}, min {min(v):.2f}, max {max(v):.2f}")
        print("  video length (s):", [round(r['video_s'], 2) for r in ok])


if __name__ == "__main__":
    main()
