"""Passive probe: connect to the paired Nano, keep alive, and record every status push (02/80) and other frames for 14 s.
Prints which payload bytes change and whether anything looks like a clock. Sends only the normal handshake and keepalives."""
import asyncio, os, sys, time, datetime as dt, collections
import nano_ble as nb
from bleak import BleakClient, BleakScanner

async def main():
    print("scanning ...")
    found = None
    for dev, adv in (await BleakScanner.discover(8, return_adv=True)).values():
        mfr = adv.manufacturer_data.get(nb.DJI_COMPANY_ID)
        if mfr and mfr[:2] == b"\x19\x00":
            found = dev.address; print("found", dev.address, adv.rssi); break
    if not found:
        print("NO CAMERA FOUND"); return
    rx = nb.DumlReassembler()
    frames = []
    seq = 0x2000

    def h(_, data):
        for raw in rx.feed(bytes(data)):
            try: f = nb.duml_parse(raw)
            except ValueError: continue
            frames.append((time.time(), f))

    ident = open(os.path.join(os.environ["LOCALAPPDATA"], "nano-dash", "controller_id")).read().strip()
    async with BleakClient(found, timeout=20) as c:
        async def send(rcv, s, i, p=b""):
            nonlocal seq
            seq += 1
            await c.write_gatt_char(nb.WRITE_UUID, nb.duml_build(nb.APP, rcv, seq, 0x40, s, i, p), response=False)
        await c.start_notify(nb.NOTIFY_UUID, h); await c.start_notify(nb.WRITE_UUID, h)
        await asyncio.sleep(0.2); await c.write_gatt_char(nb.NOTIFY_UUID, bytes([1, 0]), response=True); await asyncio.sleep(0.3)
        await send(nb.SESSION_F0, 0x00, 0x2B, bytes([4, 0])); await asyncio.sleep(0.3)
        await send(nb.WIFI, 0x07, 0x45, nb.pack_string(ident) + nb.pack_string("osmo"))
        await asyncio.sleep(1.5)
        stop = asyncio.Event()
        async def ka():
            while not stop.is_set():
                await send(nb.SESSION_F0, 0x00, 0x2B, bytes([1, 1])); await asyncio.sleep(1)
        t = asyncio.create_task(ka())
        await send(nb.SESSION_1C, 0x53, 0x10, bytes(4))
        t0 = time.time(); print("collecting 14 s at", dt.datetime.now(dt.timezone.utc).isoformat())
        await asyncio.sleep(14)
        stop.set(); await t

    kinds = collections.Counter((f["cmd_set"], f["cmd_id"]) for _, f in frames)
    print("frame kinds (set,id): count", {f"{a:02X}/{b:02X}": n for (a, b), n in kinds.items()})
    for key, label in (((0x02, 0x80), "STATUS 02/80"),):
        pl = [f["payload"] for _, f in frames if (f["cmd_set"], f["cmd_id"]) == key]
        if not pl: print("no", label); continue
        n = min(len(p) for p in pl)
        print(label, "pushes", len(pl), "payload length", {len(p) for p in pl})
        print(" first:", pl[0].hex(" "))
        print(" last: ", pl[-1].hex(" "))
        changing = [i for i in range(n) if len({p[i] for p in pl}) > 1]
        print(" bytes that change over the run:", changing)
        for i in changing:
            vals = [p[i] for p in pl]
            print(f"   byte {i}: distinct {len(set(vals))}, first {vals[0]}, last {vals[-1]}")
        # look for a clock: u32 little/big endian near now (unix), or year 2026 as u16, anywhere in any payload of any frame
        now = int(time.time())
        hits = []
        for _, f in frames:
            p = f["payload"]
            for i in range(len(p) - 3):
                for order in ("little", "big"):
                    v = int.from_bytes(p[i:i + 4], order)
                    if abs(v - now) < 86400 * 400: hits.append((f["cmd_set"], f["cmd_id"], i, order, v))
            for i in range(len(p) - 1):
                if int.from_bytes(p[i:i + 2], "little") == 2026 or int.from_bytes(p[i:i + 2], "big") == 2026:
                    hits.append((f["cmd_set"], f["cmd_id"], i, "year2026", 2026))
        print(" clock-like values:", sorted(set(hits))[:10] or "none")
    others = [(f["cmd_set"], f["cmd_id"], f["payload"].hex(" ")) for _, f in frames if (f["cmd_set"], f["cmd_id"]) != (0x02, 0x80)]
    seen = set()
    for s, i, p in others:
        if (s, i) in seen: continue
        seen.add((s, i)); print(f" other frame {s:02X}/{i:02X}: {p[:120]}")

asyncio.run(main())
