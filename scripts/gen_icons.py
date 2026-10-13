import zlib, struct, math, os

# 以脚本所在位置推导工程根目录（scripts/ 的上一级），避免硬编码绝对路径
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'res')

def write_png(path, w, h, rows):
    raw = bytearray()
    for row in rows:
        raw.append(0)
        for px in row:
            raw += bytes(px)
    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += chunk(b'IEND', b'')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as f:
        f.write(png)

C1 = (0x6C, 0x5C, 0xE7)
C2 = (0x2F, 0x25, 0xA8)
SS = 4  # supersample

def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))

def make_icon(size, round_shape):
    rows = []
    r_out = 0.30 * size
    r_mid = 0.150 * size
    r_dot = 0.048 * size
    corner = 0.225 * size
    cx = cy = size / 2.0
    for y in range(size):
        row = []
        for x in range(size):
            acc = [0.0, 0.0, 0.0]
            acc_a = 0.0
            for sy in range(SS):
                for sx in range(SS):
                    px = x + (sx + 0.5) / SS
                    py = y + (sy + 0.5) / SS
                    # 背景形状遮罩
                    inside = False
                    if round_shape:
                        inside = (px - cx) ** 2 + (py - cy) ** 2 <= (size / 2.0) ** 2
                    else:
                        dx = max(corner - px, px - (size - corner), 0.0)
                        dy = max(corner - py, py - (size - corner), 0.0)
                        inside = (dx * dx + dy * dy) <= corner * corner
                    if not inside:
                        continue
                    t = (px / size * 0.5 + py / size * 0.5)
                    bg = lerp(C1, C2, min(max(t, 0.0), 1.0))
                    d = math.hypot(px - cx, py - cy)
                    col = bg
                    if d <= r_out:
                        if d >= r_mid:
                            col = (0xFF, 0xFF, 0xFF)          # 唱片外环
                        elif d <= r_dot:
                            col = (0xFF, 0xFF, 0xFF)          # 中心孔
                        else:
                            col = bg
                    acc[0] += col[0]; acc[1] += col[1]; acc[2] += col[2]
                    acc_a += 1.0
            n = SS * SS
            if acc_a == 0:
                row.append((0, 0, 0, 0))
            else:
                a = int(round(255 * acc_a / n))
                row.append((int(round(acc[0] / acc_a)), int(round(acc[1] / acc_a)), int(round(acc[2] / acc_a)), a))
        rows.append(row)
    return rows

DENS = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
for dens, px in DENS.items():
    write_png(os.path.join(OUT, f'mipmap-{dens}', 'ic_launcher.png'), px, px, make_icon(px, False))
    write_png(os.path.join(OUT, f'mipmap-{dens}', 'ic_launcher_round.png'), px, px, make_icon(px, True))

# 播放通知/锁屏用的小图标
print("icons generated:", len(DENS) * 2)
