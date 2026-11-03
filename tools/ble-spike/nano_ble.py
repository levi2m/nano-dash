#!/usr/bin/env python3
"""Talk to a DJI Osmo Nano over Bluetooth LE from a PC: pair, wake, record start and stop, and timing trials.

Research tool used to validate the protocol behaviour that the Android app relies on. The command set and frame layout
come from the protocol documentation of the Osmosis project (https://github.com/KonradIT/osmosis, MIT); the Nano-specific
behaviour (timings, replies, error codes) was measured on a real camera. Runs on Windows Python with `bleak`.

  python nano_ble.py selftest                      # offline: frame code against the frame published by Osmosis
  python nano_ble.py scan                          # find the Nano (advert company id 0x08AA, model id 0x0019)
  python nano_ble.py duml ADDRESS                  # pair (approve on the camera), keep alive, wake, show status
  python nano_ble.py duml ADDRESS --record-start --record-seconds 5
  python nano_ble.py trials ADDRESS --n 5          # start / flash the screen / stop, N times, logging every timestamp

Before scanning: close any phone app that holds the camera's Bluetooth link, turn the camera on with wireless enabled,
and unplug USB (the camera refuses to record while USB is connected).
"""
import argparse
import asyncio
import datetime as dt
import random
import struct
import sys
import time

DJI_COMPANY_ID = 0x08AA  # company id in the Nano's advert; its model id 0x0019 follows (bytes "19 00")
SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"   # control service seen on the Nano
NOTIFY_UUID = "0000fff4-0000-1000-8000-00805f9b34fb"
WRITE_UUID = "0000fff5-0000-1000-8000-00805f9b34fb"


def _make_table(poly: int, width: int):
    table = []
    for i in range(256):
        c = i
        for _ in range(8):
            c = (c >> 1) ^ poly if c & 1 else c >> 1
        table.append(c & ((1 << width) - 1))
    return table


_T16 = _make_table(0xA001, 16)
_T32 = _make_table(0xEDB88320, 32)



def now_utc() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%H:%M:%S.%f")[:-3] + "Z"



async def scan(seconds: float):
    from bleak import BleakScanner
    print(f"scanning {seconds:.0f} s ...")
    found = await BleakScanner.discover(timeout=seconds, return_adv=True)
    rows = []
    for dev, adv in found.values():
        dji = DJI_COMPANY_ID in adv.manufacturer_data
        rows.append((not dji, -(adv.rssi or -999), dev, adv, dji))
    rows.sort(key=lambda r: (r[0], r[1]))
    for _, _, dev, adv, dji in rows:
        mfr = {hex(k): v.hex() for k, v in adv.manufacturer_data.items()}
        tag = "  <== DJI company id" if dji else ""
        print(f"{dev.address}  rssi={adv.rssi}  name={adv.local_name or dev.name!r}  mfr={mfr}{tag}")
    if not any(r[4] for r in rows):
        print("\nNo advert with DJI company id 0x08AA. Is the camera on, wireless enabled, and Mimo closed?")




# ---------- DUML (0x55 frames): what the Osmo Nano actually appears to speak ----------
# Layout, CRC parameters and the command ids below come from KonradIT/osmosis MEDIA_PROTOCOL.md (MIT) and are
# checked against its documented GetWifiSsid example frame in selftest. Whether the ACK shape for 0x07/0x46 and the
# receiver for record control are right is NOT confirmed; those are marked ASSUMPTION.
def _crc_reflected(data: bytes, table, init: int, mask: int) -> int:
    c = init
    for b in data:
        c = table[(c ^ b) & 0xFF] ^ (c >> 8)
    return c & mask


_T8 = _make_table(0x8C, 8)       # CRC-8: poly 0x31 reflected, init 0x77
_T16D = _make_table(0x8408, 16)  # CRC-16: poly 0x1021 reflected, init 0x3692


def duml_build(sender: int, receiver: int, seq: int, cmd_type: int, cmd_set: int, cmd_id: int, payload: bytes = b"") -> bytes:
    length = 13 + len(payload)
    head = bytes([0x55, length & 0xFF, ((length >> 8) & 0x03) | (1 << 2)])
    head += bytes([_crc_reflected(head, _T8, 0x77, 0xFF)])
    body = head + bytes([sender, receiver]) + struct.pack("<H", seq) + bytes([cmd_type, cmd_set, cmd_id]) + payload
    return body + struct.pack("<H", _crc_reflected(body, _T16D, 0x3692, 0xFFFF))


def duml_parse(buf: bytes):
    if len(buf) < 13 or buf[0] != 0x55:
        raise ValueError("not a DUML frame")
    length = buf[1] | ((buf[2] & 0x03) << 8)
    if length != len(buf):
        raise ValueError(f"length {length} != {len(buf)} bytes")
    if buf[3] != _crc_reflected(buf[:3], _T8, 0x77, 0xFF):
        raise ValueError("crc8 mismatch")
    if struct.unpack_from("<H", buf, length - 2)[0] != _crc_reflected(buf[:length - 2], _T16D, 0x3692, 0xFFFF):
        raise ValueError("crc16 mismatch")
    return {"sender": buf[4], "receiver": buf[5], "seq": struct.unpack_from("<H", buf, 6)[0], "cmd_type": buf[8],
            "cmd_set": buf[9], "cmd_id": buf[10], "payload": bytes(buf[11:length - 2])}


class DumlReassembler:
    def __init__(self):
        self.buf = bytearray()

    def feed(self, data: bytes):
        self.buf += data
        out = []
        while True:
            while self.buf and self.buf[0] != 0x55:
                del self.buf[0]
            if len(self.buf) < 3:
                break
            length = self.buf[1] | ((self.buf[2] & 0x03) << 8)
            if length < 13 or length > 1024:
                del self.buf[0]
                continue
            if len(self.buf) < length:
                break
            out.append(bytes(self.buf[:length]))
            del self.buf[:length]
        return out


def pack_string(s: str) -> bytes:
    b = s.encode()
    return bytes([len(b)]) + b


def describe_duml(raw: bytes) -> str:
    try:
        f = duml_parse(raw)
        return (f"DUML {f['sender']:02X}->{f['receiver']:02X} type={f['cmd_type']:02X} "
                f"{f['cmd_set']:02X}/{f['cmd_id']:02X} seq={f['seq']} payload={f['payload'].hex(' ')}")
    except ValueError as e:
        return f"unparsed ({e}): {raw.hex(' ')}"


def duml_selftest() -> bool:
    ex = bytes.fromhex("550d0433020700a04007077472")  # Osmosis' documented GetWifiSsid frame
    ok = duml_build(0x02, 0x07, 0xA000, 0x40, 0x07, 0x07) == ex
    print("DUML build matches Osmosis' documented example:", ok)
    return ok and duml_parse(ex)["cmd_id"] == 0x07


APP, CAMERA, WIFI, SESSION_F0, SESSION_1C = 0x02, 0x01, 0x07, 0xF0, 0x1C


async def duml_session(address: str, record: bool, record_seconds: float):
    """Pair with the Nano as a new controller (the camera may ask you to approve on its screen), keep the link alive,
    and optionally start then stop a recording. Everything received is printed."""
    import secrets
    from bleak import BleakClient

    rx = DumlReassembler()
    q: asyncio.Queue = asyncio.Queue()
    log = []
    seq = random.randint(0x1000, 0x7000)

    def note(t):
        log.append(t)
        print(f"{now_utc()} {t}")

    rec_bit = {"v": None}

    def on_notify(label):
        def h(_, data: bytearray):
            for raw in rx.feed(bytes(data)):
                try:
                    f = duml_parse(raw)
                except ValueError:
                    note("   -> " + describe_duml(raw))
                    continue
                if (f["cmd_set"], f["cmd_id"]) == (0x02, 0x80) and f["payload"]:
                    # status push is ~10 Hz: only print when the recording bit (bit 7 of byte 0, per Osmosis) changes
                    bit = bool(f["payload"][0] & 0x80)
                    if bit != rec_bit["v"]:
                        rec_bit["v"] = bit
                        note(f"STATUS 02/80 recording bit = {bit} (byte0=0x{f['payload'][0]:02X}, {len(f['payload'])}B)")
                else:
                    note(f"notify {label} " + describe_duml(raw))
                q.put_nowait(f)
        return h

    async def wait(cmd_set, cmd_id, timeout):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            try:
                f = await asyncio.wait_for(q.get(), max(0.01, end - time.monotonic()))
            except asyncio.TimeoutError:
                return None
            if f["cmd_set"] == cmd_set and f["cmd_id"] == cmd_id:
                return f
        return None

    async with BleakClient(address, timeout=20) as client:
        async def send(receiver, cmd_set, cmd_id, payload=b"", label=""):
            nonlocal seq
            seq += 1
            frame = duml_build(APP, receiver, seq, 0x40, cmd_set, cmd_id, payload)
            note(f"send {label or f'{cmd_set:02X}/{cmd_id:02X}'}: {frame.hex(' ')}")
            await client.write_gatt_char(WRITE_UUID, frame, response=False)

        await client.start_notify(NOTIFY_UUID, on_notify("FFF4"))
        await client.start_notify(WRITE_UUID, on_notify("FFF5"))
        await asyncio.sleep(0.2)
        await client.write_gatt_char(NOTIFY_UUID, bytes([0x01, 0x00]), response=True)   # per Osmosis: before any FFF5 traffic
        await asyncio.sleep(0.3)

        # 32-char controller identifier, created once and reused so the camera sees the same controller each run.
        # Stored outside the project (it is the credential that identifies this PC to the camera).
        import os
        from pathlib import Path
        idfile = Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "nano-dash" / "controller_id"
        if idfile.exists():
            ident = idfile.read_text().strip()
        else:
            ident = secrets.token_hex(16)
            idfile.parent.mkdir(parents=True, exist_ok=True)
            idfile.write_text(ident)
        note(f"controller identifier: {ident[:4]}... (stored in {idfile})")
        print("\n>>> WATCH THE CAMERA SCREEN: approve the new controller if asked. <<<\n")
        await send(SESSION_F0, 0x00, 0x2B, bytes([0x04, 0x00]), "pre-pair 00/2B")
        await asyncio.sleep(0.3)
        await send(WIFI, 0x07, 0x45, pack_string(ident) + pack_string("osmo"), "SetPairingPIN 07/45")
        r = await wait(0x07, 0x45, 3)
        note(f"RESULT: pairing reply = {r['payload'].hex(' ') if r else None} (00 01 already paired, 00 02 approval required)")
        if r and r["payload"][:2] == b"\x00\x02":
            req = await wait(0x07, 0x46, 40)
            if req is None:
                note("RESULT: no approval request (07/46) arrived within 40 s")
                return
            ack = duml_build(APP, req["sender"], req["seq"], 0x80, 0x07, 0x46, b"\x00")   # ASSUMPTION: ack shape
            note(f"send approval ack (assumed shape): {ack.hex(' ')}")
            await client.write_gatt_char(WRITE_UUID, ack, response=False)

        stop = asyncio.Event()

        async def keepalive():
            while not stop.is_set():
                await send(SESSION_F0, 0x00, 0x2B, bytes([0x01, 0x01]), "keepalive 00/2B")
                try:
                    await asyncio.wait_for(stop.wait(), 1.0)
                except asyncio.TimeoutError:
                    pass

        ka = asyncio.create_task(keepalive())
        try:
            await send(SESSION_1C, 0x53, 0x10, bytes(4), "wake 53/10")
            w = await wait(0x53, 0x10, 3)
            note(f"RESULT: wake reply = {w['payload'].hex(' ') if w else None} (01 00 00 00 expected)")
            st = await wait(0x02, 0x80, 4)
            note("RESULT: status push 02/80 received" if st else "RESULT: no status push 02/80 in 4 s")
            if record:
                started = False
                try:
                    t0 = now_utc()
                    await send(CAMERA, 0x02, 0x02, b"\x01", "RECORD START 02/02")   # ASSUMPTION: receiver 0x01 (camera)
                    r = await wait(0x02, 0x02, 5)
                    note(f"RESULT: record start reply = {r['payload'].hex(' ') if r else None} (sent at {t0}; 00 expected)")
                    started = True
                    await asyncio.sleep(record_seconds)
                finally:
                    if started:
                        await send(CAMERA, 0x02, 0x02, b"\x00", "RECORD STOP 02/02")
                        r = await wait(0x02, 0x02, 5)
                        note(f"RESULT: record stop reply = {r['payload'].hex(' ') if r else None}")
            else:
                await asyncio.sleep(3)
        finally:
            stop.set()
            await ka
        print("\n--- RESULT lines ---")
        for t in log:
            if t.startswith("RESULT"):
                print(t)


async def duml_trials(address: str, n: int, hold: float, label: str):
    """Repeat start -> beep -> hold -> stop N times in one Bluetooth session and log every timestamp.

    The flash (three white flashes of the PC's screen) is the ground truth: the camera films it, so the frame where the
    screen first goes bright tells us which PC time video t=0 corresponds to. Point the camera at the screen.
    Results go to tools/ble-spike/trials/trials-<label>-<utc>.json.
    """
    import json
    import os
    import subprocess
    from pathlib import Path
    from bleak import BleakClient

    idfile = Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "nano-dash" / "controller_id"
    if not idfile.exists():
        print("No controller id yet: run the `duml` command once and approve the pairing on the camera.")
        return
    ident = idfile.read_text().strip()
    rx = DumlReassembler()
    q: asyncio.Queue = asyncio.Queue()
    bit = {"v": None, "t_ns": None}
    events = []                       # every recording-bit change with a PC timestamp
    seq = random.randint(0x1000, 0x7000)
    out_dir = Path(__file__).parent / "trials"
    out_dir.mkdir(exist_ok=True)

    def now_ns() -> int:
        return time.time_ns()

    def iso(ns: int | None) -> str | None:
        return None if ns is None else dt.datetime.fromtimestamp(ns / 1e9, dt.timezone.utc).strftime("%H:%M:%S.%f")[:-3] + "Z"

    def on_notify(_, data: bytearray):
        t = now_ns()
        for raw in rx.feed(bytes(data)):
            try:
                f = duml_parse(raw)
            except ValueError:
                continue
            f["t_ns"] = t
            if (f["cmd_set"], f["cmd_id"]) == (0x02, 0x80) and f["payload"]:
                b = bool(f["payload"][0] & 0x80)
                if b != bit["v"]:
                    bit["v"], bit["t_ns"] = b, t
                    events.append({"bit": b, "utc": iso(t), "t_ns": t})
                    print(f"{iso(t)} recording bit = {b}")
            else:
                q.put_nowait(f)

    async def wait_reply(cmd_set, cmd_id, timeout):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            try:
                f = await asyncio.wait_for(q.get(), max(0.01, end - time.monotonic()))
            except asyncio.TimeoutError:
                return None
            if (f["cmd_set"], f["cmd_id"]) == (cmd_set, cmd_id):
                return f
        return None

    async def wait_bit(target, timeout):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if bit["v"] is target:
                return bit["t_ns"]
            await asyncio.sleep(0.01)
        return None

    results = []
    async with BleakClient(address, timeout=20) as client:
        async def send(receiver, cmd_set, cmd_id, payload=b"") -> int:
            nonlocal seq
            seq += 1
            frame = duml_build(APP, receiver, seq, 0x40, cmd_set, cmd_id, payload)
            t = now_ns()
            await client.write_gatt_char(WRITE_UUID, frame, response=False)
            return t

        await client.start_notify(NOTIFY_UUID, on_notify)
        await client.start_notify(WRITE_UUID, on_notify)
        await asyncio.sleep(0.2)
        await client.write_gatt_char(NOTIFY_UUID, bytes([0x01, 0x00]), response=True)
        await asyncio.sleep(0.3)
        await send(SESSION_F0, 0x00, 0x2B, bytes([0x04, 0x00]))
        await asyncio.sleep(0.3)
        await send(WIFI, 0x07, 0x45, pack_string(ident) + pack_string("osmo"))
        r = await wait_reply(0x07, 0x45, 3)
        if not r or r["payload"][:2] != b"\x00\x01":
            print("Camera did not report 'already paired' (reply:", r and r["payload"].hex(" "), "). Run `duml` and approve first.")
            return
        # Only now cover the screen, so any earlier failure never leaves a full-screen window behind.
        helper = subprocess.Popen([sys.executable, str(Path(__file__).parent / "flash_window.py")], stdin=subprocess.PIPE,
                                  stdout=subprocess.PIPE, text=True, bufsize=1)
        if helper.stdout.readline().strip() != "ready":
            print("flash window helper did not start (is tkinter installed in this Python?)")
            helper.terminate()
            return
        print("A full-screen black window now covers the primary monitor. Point the camera at it. Escape aborts.")
        stop = asyncio.Event()

        async def keepalive():
            while not stop.is_set():
                await send(SESSION_F0, 0x00, 0x2B, bytes([0x01, 0x01]))
                try:
                    await asyncio.wait_for(stop.wait(), 1.0)
                except asyncio.TimeoutError:
                    pass

        ka = asyncio.create_task(keepalive())
        try:
            await send(SESSION_1C, 0x53, 0x10, bytes(4))
            await wait_reply(0x53, 0x10, 3)
            await asyncio.sleep(1.5)
            print(f"\nstarting {n} trials, hold {hold}s each. Camera should face the screen.\n")
            loop = asyncio.get_running_loop()
            for i in range(1, n + 1):
                rec = {"trial": i}
                if bit["v"]:
                    print("camera already recording; stopping it first")
                    await send(CAMERA, 0x02, 0x02, b"\x00")
                    await wait_bit(False, 8)
                    await asyncio.sleep(3)
                rec["t_start_sent_ns"] = await send(CAMERA, 0x02, 0x02, b"\x01")
                rep = await wait_reply(0x02, 0x02, 5)
                rec["t_start_reply_ns"] = rep["t_ns"] if rep else None
                rec["start_reply"] = rep["payload"].hex() if rep else None
                rec["t_bit_on_ns"] = await wait_bit(True, 8)
                if rec["t_bit_on_ns"] is None:
                    print(f"trial {i}: recording bit never turned on")
                    results.append(rec)
                    continue
                await asyncio.sleep(2.0)                       # let the first frames start before the beep
                helper.stdin.write("flash\n")                  # three white flashes; the helper reports when each was drawn
                helper.stdin.flush()
                line = await loop.run_in_executor(None, helper.stdout.readline)
                stamps = [int(v) for v in line.split()]
                if len(stamps) != 3:
                    print("flash helper stopped (Escape pressed?); aborting")
                    results.append(rec)
                    break
                rec["t_flash_calls_ns"] = stamps
                rec["t_flash_ns"] = stamps[0]
                await asyncio.sleep(hold)
                rec["t_stop_sent_ns"] = await send(CAMERA, 0x02, 0x02, b"\x00")
                rep = await wait_reply(0x02, 0x02, 5)
                rec["t_stop_reply_ns"] = rep["t_ns"] if rep else None
                rec["stop_reply"] = rep["payload"].hex() if rep else None
                rec["t_bit_off_ns"] = await wait_bit(False, 10)
                for k in [k for k in rec if k.endswith("_ns") and isinstance(rec[k], int)]:
                    rec[k.replace("_ns", "_utc")] = iso(rec[k])
                s = rec["t_start_sent_ns"]
                ms = lambda k: None if rec.get(k) is None else round((rec[k] - s) / 1e6)
                print(f"trial {i}: start reply +{ms('t_start_reply_ns')} ms, bit on +{ms('t_bit_on_ns')} ms, "
                      f"flash +{ms('t_flash_ns')} ms, stop sent +{ms('t_stop_sent_ns')} ms, bit off +{ms('t_bit_off_ns')} ms")
                results.append(rec)
                await asyncio.sleep(4)                         # let the camera close the file
        finally:
            stop.set()
            await ka
            try:
                helper.stdin.write("quit\n")
                helper.stdin.flush()
            except (OSError, ValueError):
                pass
            helper.terminate()
    path = out_dir / f"trials-{label}-{dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')}.json"
    path.write_text(json.dumps({"label": label, "hold": hold, "events": events, "trials": results}, indent=1))
    print("\nsaved", path)




def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("selftest")
    p = sub.add_parser("scan"); p.add_argument("--seconds", type=float, default=10)
    p = sub.add_parser("duml", help="pairing / keepalive / status session")
    p.add_argument("address")
    p.add_argument("--record-start", action="store_true", help="also start, then stop, a recording")
    p.add_argument("--record-seconds", type=float, default=5)
    p = sub.add_parser("trials", help="repeat start/flash/stop N times and log timestamps (camera facing the screen)")
    p.add_argument("address")
    p.add_argument("--n", type=int, default=5)
    p.add_argument("--hold", type=float, default=6)
    p.add_argument("--label", default="usb-unplugged", help="free text, e.g. usb-unplugged or usb-attached")
    a = ap.parse_args()
    if a.cmd == "selftest":
        ok = duml_selftest()
        return 0 if ok else 1
    if a.cmd == "scan":
        asyncio.run(scan(a.seconds))
    elif a.cmd == "duml":
        asyncio.run(duml_session(a.address, a.record_start, a.record_seconds))
    elif a.cmd == "trials":
        asyncio.run(duml_trials(a.address, a.n, a.hold, a.label))
    return 0


if __name__ == "__main__":
    sys.exit(main())
