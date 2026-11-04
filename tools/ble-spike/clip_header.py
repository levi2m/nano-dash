"""Print the creation time (UTC, whole seconds) and length from a clip's mvhd box. Usage: python clip_header.py "E:\\DCIM\\DJI_001\\*.MP4"
Reads only the box headers, so it is fast on files of any size. A clip's lag window is: (first frame in your clock - header) - 1 s, up to that."""
import struct, sys, datetime as d, glob
EPOCH = 2082844800
def mvhd(path):
    with open(path, 'rb') as f:
        f.seek(0, 2); size = f.tell(); pos = 0; boxes = []
        while pos < size:
            f.seek(pos); h = f.read(16)
            if len(h) < 8: break
            sz, typ = struct.unpack('>I4s', h[:8]); hdr = 8
            if sz == 1: sz = struct.unpack('>Q', h[8:16])[0]; hdr = 16
            elif sz == 0: sz = size - pos
            if sz < hdr: break   # a zero 64-bit size would never advance
            boxes.append((typ.decode('latin1'), pos, sz))
            if typ == b'moov':
                f.seek(pos + hdr); body = f.read(120)
                # first child should be mvhd
                csz, ctyp = struct.unpack('>I4s', body[:8])
                ver = body[8]
                if ver == 0:
                    cr, mo, ts, du = struct.unpack('>IIII', body[12:28])
                else:
                    cr, mo, ts, du = struct.unpack('>QQIQ', body[12:36])
                return ctyp, cr, mo, ts, du, boxes
            pos += sz
    return None
for p in sorted(glob.glob(sys.argv[1])):
    r = mvhd(p)
    if not r: print(p, 'no moov'); continue
    ctyp, cr, mo, ts, du, boxes = r
    c = d.datetime(1970, 1, 1) + d.timedelta(seconds=cr - EPOCH)
    m = d.datetime(1970, 1, 1) + d.timedelta(seconds=mo - EPOCH)
    print(p.split('\\')[-1], ctyp, 'created', c, 'modified', m, 'dur %.3f s' % (du / ts), 'boxes', [(b[0], b[1]) for b in boxes])
