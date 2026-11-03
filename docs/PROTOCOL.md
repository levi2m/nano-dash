# The Osmo Nano's Bluetooth control

What we confirmed on one real Osmo Nano. The framing and command ids come from the [Osmosis](https://github.com/KonradIT/osmosis) project's protocol notes (MIT); everything marked "observed" was checked on the camera. This is independent research on how the camera behaves, with no DJI SDK code or documents used. The code is in `android/core/.../duml/` and `.../camera/`.

## Link

- BLE GATT service `FFF0`. Notifications arrive on `FFF4` (and `FFF5`); frames are written to `FFF5` without response. Enable notifications on both, then write `01 00` to `FFF4` (with response) about 200 ms before any traffic.
- Request MTU 500. Observed: 517 makes the camera stop answering.
- Scan filter: manufacturer data, company id `0x08AA`, data starting `19 00` (the Nano's model id).
- The Nano drops an idle link after about 5 to 6 s, so a keepalive is sent every second.
- The link is not encrypted and the pairing identifier is sent in the clear.

## Frame (DUML)

All multi-byte fields are little-endian.

```
0x55 | length(10 bits) + version(6 bits, 1) | crc8 of bytes 0..2 | sender | receiver | seq(2)
     | cmdType | cmdSet | cmdId | payload | crc16 of everything before it
```

CRC-8: reflected polynomial 0x31, initial value 0x77. CRC-16: reflected polynomial 0x1021, initial value 0x3692. Notifications can split one frame or merge several: the reassembler checks the header CRC before trusting a length, so a stray `0x55` byte cannot stall it.

## Commands

| Step | Set / id | Notes |
| --- | --- | --- |
| Pair | `07/45` | Reply says already paired or approval required |
| Approval | `07/46` | The camera asks on its screen; acknowledge with the same sequence, response flag, code 0 |
| Keepalive | `00/2B` payload `01 01` | About once a second |
| Wake | `53/10` | Wakes a camera that is on standby |
| Set clock | `00/6A` to receiver `0x28` | `01 00`, unix seconds u64-LE, UTC offset minutes i16-LE, zone id with a length byte. Reply `00 01 00` (first byte 0 = ok). Sent to the camera itself (`0x01`) it is silently dropped (Osmosis). The camera takes whole seconds, so time the write just before a second boundary. It overwrites the camera's clock |
| Record | `02/02` | First payload byte `1` starts, otherwise stops. Reply code 0 accepted, `0xD6` USB attached, `0xDF` already recording |
| Status | `02/80` pushes | About 10 per second; bit 7 of byte 0 is the recording flag |

## Behaviour observed

- Start: reply after about 550 ms, recording flag after about 1.15 to 1.5 s.
- Stop: reply in about 50 ms, flag clears about 2.1 s later.
- With USB connected the camera refuses to start recording (`0xD6`).
- Close Mimo on the phone before connecting. We did not test what happens when both are open.
