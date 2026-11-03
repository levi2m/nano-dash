#!/usr/bin/env python3
"""Check a .fit file written by nano-dash with independent decoders.

  python validate_fit.py ride.fit [--reference other.fit]

Uses `fitparse` (MIT) always, and Garmin's `garmin-fit-sdk` decoder if it is installed (it is optional and only used for
local checking; this project does not ship or depend on it). Exits non-zero if anything is wrong.
Install:  pip install fitparse            (optional: pip install garmin-fit-sdk)
"""
import argparse
import collections
import sys

import fitparse


def check_fitparse(path):
    ff = fitparse.FitFile(path)
    ff.parse()                                       # raises on a bad CRC or malformed structure
    msgs = collections.defaultdict(list)
    for m in ff.get_messages():
        msgs[m.name].append({f.name: f.value for f in m.fields})
    print("fitparse: parsed OK. messages:", {k: len(v) for k, v in msgs.items()})
    ok = True

    def need(cond, what):
        nonlocal ok
        print(("  ok   " if cond else "  FAIL ") + what)
        ok &= bool(cond)

    need(len(msgs["file_id"]) == 1 and msgs["file_id"][0]["type"] == "activity", "one file_id of type activity")
    fid = msgs["file_id"][0]
    need(fid["time_created"].year >= 2020, f"file_id time is real ({fid['time_created']}; Mimo's export wrote year 2046)")
    sess = msgs["session"]
    need(len(sess) == 1, "one session")
    need(sess[0].get("sport") == "motorcycling", f"sport is motorcycling ({sess[0].get('sport')})")
    need(abs((sess[0]["start_time"] - fid["time_created"]).total_seconds()) < 5, "session start time matches the file time")
    recs = msgs["record"]
    need(len(recs) > 0, f"{len(recs)} records")
    fields = collections.Counter(k for r in recs for k, v in r.items() if v is not None)
    for f in ("timestamp", "position_lat", "position_long", "speed", "enhanced_speed", "distance", "altitude", "enhanced_altitude", "grade"):
        need(fields[f] == len(recs), f"every record has {f}")
    dev = {k for r in recs for k in r if isinstance(k, str) and k in ("lean_angle", "lateral_accel", "longitudinal_accel", "camera_recording")}
    need(dev == {"lean_angle", "lateral_accel", "longitudinal_accel", "camera_recording"}, f"developer fields present by name: {sorted(dev)}")
    ts = [r["timestamp"] for r in recs]
    need(all(b > a for a, b in zip(ts, ts[1:])), "record timestamps strictly increase")
    return ok, fields


def check_garmin(path):
    try:
        from garmin_fit_sdk import Decoder, Stream
    except ImportError:
        print("garmin-fit-sdk not installed: skipped (optional)")
        return True
    d = Decoder(Stream.from_file(path))
    messages, errors = d.read(enable_crc_check=True)
    good = not errors and Decoder(Stream.from_file(path)).is_fit() and Decoder(Stream.from_file(path)).check_integrity()
    print("garmin-fit-sdk:", "OK" if good else f"PROBLEMS {errors}", {k: len(v) for k, v in messages.items()})
    return good


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("fit")
    ap.add_argument("--reference", help="another .fit (e.g. a DJI Mimo export) to compare record fields against")
    a = ap.parse_args()
    ok, fields = check_fitparse(a.fit)
    ok &= check_garmin(a.fit)
    if a.reference:
        ref = fitparse.FitFile(a.reference)
        rf = collections.Counter(f.name for m in ref.get_messages("record") for f in m.fields if f.value is not None)
        missing = sorted(set(rf) - set(fields))
        print("record fields in the reference but not in ours:", missing or "none")
        ok &= not missing
    print("RESULT:", "all checks passed" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
