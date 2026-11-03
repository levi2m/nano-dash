"""Experiment: set the Nano's clock over Bluetooth (DUML 0x00/0x6A to receiver 0x28, as documented by Osmosis), exactly on a second
boundary of the PC's clock, then record three short clips at different phases of the second. Afterwards the clips' header times
tell how far the camera's clock sits from the PC's. Saves a JSON of the PC-side timestamps."""
import asyncio, json, os, random, struct, sys, time, datetime as dt
import nano_ble as nb
from bleak import BleakClient, BleakScanner

OUT = sys.argv[1]
TZ_ID, TZ_MIN = b"America/Sao_Paulo", -180      # change to your own zone id and its UTC offset in minutes

async def main():
    found = None
    for dev, adv in (await BleakScanner.discover(8, return_adv=True)).values():
        m = adv.manufacturer_data.get(nb.DJI_COMPANY_ID)
        if m and m[:2] == b"\x19\x00": found = dev.address; break
    if not found: print("NO CAMERA FOUND"); return
    rx = nb.DumlReassembler(); q = asyncio.Queue(); flag = {"v": None, "t": 0}
    seq = 0x3000

    def h(_, data):
        for raw in rx.feed(bytes(data)):
            try: f = nb.duml_parse(raw)
            except ValueError: continue
            if (f["cmd_set"], f["cmd_id"]) == (0x02, 0x80) and f["payload"]:
                b = bool(f["payload"][0] & 0x80)
                if b != flag["v"]: flag["v"] = b; flag["t"] = time.time_ns()
            else:
                q.put_nowait((time.time_ns(), f))

    ident = open(os.path.join(os.environ["LOCALAPPDATA"], "nano-dash", "controller_id")).read().strip()

    async def wait(cs, ci, timeout):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            try: t, f = await asyncio.wait_for(q.get(), max(0.01, end - time.monotonic()))
            except asyncio.TimeoutError: return None
            if (f["cmd_set"], f["cmd_id"]) == (cs, ci): return t, f
        return None

    result = {"set_time": None, "trials": []}
    async with BleakClient(found, timeout=20) as c:
        async def send(rcv, s, i, p=b""):
            nonlocal seq
            seq += 1
            await c.write_gatt_char(nb.WRITE_UUID, nb.duml_build(nb.APP, rcv, seq, 0x40, s, i, p), response=False)
            return time.time_ns()
        await c.start_notify(nb.NOTIFY_UUID, h); await c.start_notify(nb.WRITE_UUID, h)
        await asyncio.sleep(0.2); await c.write_gatt_char(nb.NOTIFY_UUID, bytes([1, 0]), response=True); await asyncio.sleep(0.3)
        await send(nb.SESSION_F0, 0x00, 0x2B, bytes([4, 0])); await asyncio.sleep(0.3)
        await send(nb.WIFI, 0x07, 0x45, nb.pack_string(ident) + nb.pack_string("osmo"))
        r = await wait(0x07, 0x45, 3); print("pairing reply", r and r[1]["payload"].hex(" "))
        stop = asyncio.Event()
        async def ka():
            while not stop.is_set():
                await send(nb.SESSION_F0, 0x00, 0x2B, bytes([1, 1])); await asyncio.sleep(1)
        kt = asyncio.create_task(ka())
        await send(nb.SESSION_1C, 0x53, 0x10, bytes(4)); await wait(0x53, 0x10, 3)
        for _ in range(30):
            if flag["v"] is not None: break
            await asyncio.sleep(0.1)

        # --- set the clock: aim the write to arrive just after a whole second of the PC clock
        now = time.time_ns(); target = (now // 1_000_000_000 + 2) * 1_000_000_000          # a whole second, 1 to 2 s ahead
        await asyncio.sleep((target - time.time_ns()) / 1e9 - 0.03)
        payload = bytes([1, 0]) + struct.pack("<Q", target // 1_000_000_000) + struct.pack("<h", TZ_MIN) + bytes([len(TZ_ID)]) + TZ_ID
        sent = await send(0x28, 0x00, 0x6A, payload)
        rep = await wait(0x00, 0x6A, 3)
        result["set_time"] = {"unix": target // 1_000_000_000, "second_boundary_ns": target, "sent_ns": sent,
                              "reply_ns": rep[0] if rep else None, "reply": rep[1]["payload"].hex(" ") if rep else None}
        print("set time: sent %.0f ms after the boundary; reply %s" % ((sent - target) / 1e6, result["set_time"]["reply"]))

        for i in range(3):
            phase = [0.10, 0.45, 0.80][i]                        # spread the first frames evenly over the second
            await asyncio.sleep((phase - (time.time_ns() / 1e9 % 1.0)) % 1.0 + 1.0)
            t_start = await send(nb.CAMERA, 0x02, 0x02, b"\x01")
            rep = await wait(0x02, 0x02, 5)
            for _ in range(100):
                if flag["v"]: break
                await asyncio.sleep(0.05)
            if not flag["v"]: print("trial", i + 1, "no recording flag"); break
            t_flag = flag["t"]
            await asyncio.sleep(4)
            t_stop = await send(nb.CAMERA, 0x02, 0x02, b"\x00")
            await wait(0x02, 0x02, 5)
            for _ in range(100):
                if flag["v"] is False: break
                await asyncio.sleep(0.05)
            ff = t_flag + 255_000_000
            result["trials"].append({"first_frame_ns": ff, "expected_len_ms": ((t_stop - 651_000_000) - ff) / 1e6,
                                     "start_sent_ns": t_start, "flag_ns": t_flag, "stop_sent_ns": t_stop})
            print("trial", i + 1, "start reply", rep and rep[1]["payload"].hex(" "), "first frame at fraction %.3f s" % ((ff % 1_000_000_000) / 1e9))
            await asyncio.sleep(2.5)
        stop.set(); await kt
    json.dump(result, open(OUT, "w"), indent=1)
    print("saved", OUT)

asyncio.run(main())
