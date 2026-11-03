#!/usr/bin/env python3
"""Full-screen flash helper for the camera timing trials (stdlib only).

Shows a black full-screen window. For each line "flash" on stdin it flashes white three times (120 ms on, 120 ms off) and
prints the PC time (ns since the Unix epoch) at which each white frame was handed to the display, one line, space separated.
Press Escape to quit early, or send "quit". The screen is black except during flashes; the time is taken right after the
window update returns, so true light-out is later by the display's own delay (typically 20 to 60 ms).
"""
import sys
import time
import tkinter as tk

root = tk.Tk()
root.configure(bg="black", cursor="none")
root.attributes("-fullscreen", True)
root.attributes("-topmost", True)
root.bind("<Escape>", lambda e: root.destroy())
root.update()
print("ready", flush=True)

for line in sys.stdin:
    cmd = line.strip()
    if cmd == "quit":
        break
    if cmd != "flash":
        continue
    stamps = []
    try:
        for _ in range(3):
            root.configure(bg="white")
            root.update()
            stamps.append(time.time_ns())
            time.sleep(0.12)
            root.configure(bg="black")
            root.update()
            time.sleep(0.12)
    except tk.TclError:           # window closed with Escape
        break
    print(" ".join(map(str, stamps)), flush=True)

try:
    root.destroy()
except tk.TclError:
    pass
