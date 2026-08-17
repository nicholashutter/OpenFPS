#!/usr/bin/env python3
"""Compare JSON and hand-written .ofm files."""
import os
import struct
import sys

MAPS = [
    "cornerstone", "overpass", "tripoint", "extraction",
    "refinery", "foundry", "pipeline", "storage",
    "crossroads", "mesa", "sandbar", "stronghold",
    "arctic-station", "arctic-hp", "arctic-dom", "coldfront",
]

def read_ofm(path):
    with open(path, 'rb') as f:
        data = f.read()
    return {
        'vertices': struct.unpack_from('<I', data, 16)[0],
        'triangles': struct.unpack_from('<I', data, 24)[0] // 3,
        'submeshes': struct.unpack_from('<I', data, 32)[0],
        'textures': struct.unpack_from('<I', data, 40)[0],
        'size': len(data),
    }

def main():
    print(f"{'Map':<18} {'JSON verts':>10} {'HW verts':>9} {'% diff':>7}  {'JSON tris':>10} {'HW tris':>9} {'% diff':>7}  {'Subs':>5}  {'Status'}")
    print("-" * 110)
    all_ok = True
    for map_id in MAPS:
        json_path = f'engine/src/main/resources/maps/{map_id}/level.ofm'
        hw_path = f'engine/src/main/resources/maps/_hw/{map_id}/level.ofm'
        if not os.path.exists(json_path):
            print(f"{map_id:<18} MISSING JSON")
            all_ok = False
            continue
        if not os.path.exists(hw_path):
            print(f"{map_id:<18} MISSING HW")
            all_ok = False
            continue
        j = read_ofm(json_path)
        h = read_ofm(hw_path)
        vd = (h['vertices'] - j['vertices']) / h['vertices'] * 100 if h['vertices'] else 0
        td = (h['triangles'] - j['triangles']) / h['triangles'] * 100 if h['triangles'] else 0
        sub_ok = j['submeshes'] == h['submeshes']
        status = "OK" if abs(vd) <= 5 and abs(td) <= 5 and sub_ok else "FAIL"
        if status == "FAIL":
            all_ok = False
        print(f"{map_id:<18} {j['vertices']:>10} {h['vertices']:>9} {vd:>+6.1f}%  {j['triangles']:>10} {h['triangles']:>9} {td:>+6.1f}%  {j['submeshes']:>5}  {status}")
    print()
    print("ALL OK" if all_ok else "SOME FAILED")

if __name__ == "__main__":
    main()
