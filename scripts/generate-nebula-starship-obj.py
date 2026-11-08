#!/usr/bin/env python3
"""Generate the standalone Constitution-inspired Nebula starship OBJ/MTL pair.

Canonical axes match NebulaFlight and NebulaTextures:
  +Y up, +Z forward, twin nacelle axes at x=+/-0.255, y=0.115,
  nozzle plane z=-0.56, and conservative local half extents (0.42, 0.23, 0.66).
"""

from __future__ import annotations

from dataclasses import dataclass
from math import cos, pi, sin
from pathlib import Path
import struct
import zlib


ROOT = Path(__file__).resolve().parents[1]
ASSET_DIR = ROOT / "modules/modelling/src/contentTesting/resources/assets/kasuga_demo/models"
OBJ_PATH = ASSET_DIR / "constitution_starship.obj"
MTL_PATH = ASSET_DIR / "constitution_starship.mtl"


@dataclass(frozen=True)
class Material:
    diffuse: tuple[float, float, float]
    emission: tuple[float, float, float]
    roughness: float


MATERIALS = {
    "hull_dark": Material((0.036, 0.044, 0.057), (0.0, 0.0, 0.0), 0.82),
    "hull_mid": Material((0.076, 0.089, 0.109), (0.0, 0.0, 0.0), 0.73),
    "hull_light": Material((0.118, 0.133, 0.158), (0.0, 0.0, 0.0), 0.68),
    "trim_dark": Material((0.015, 0.021, 0.029), (0.0, 0.0, 0.0), 0.88),
    "coil_blue": Material((0.032, 0.190, 0.590), (0.025, 0.135, 0.390), 0.46),
    "collector_red": Material((0.430, 0.055, 0.010), (0.085, 0.010, 0.001), 0.54),
    "impulse_red": Material((0.340, 0.035, 0.007), (0.060, 0.004, 0.001), 0.57),
    "window_warm": Material((0.380, 0.300, 0.155), (0.120, 0.085, 0.032), 0.62),
    "window_cool": Material((0.080, 0.185, 0.265), (0.018, 0.060, 0.105), 0.58),
    "hangar_dark": Material((0.006, 0.009, 0.014), (0.004, 0.012, 0.025), 0.91),
    "deflector_copper": Material((0.285, 0.112, 0.027), (0.035, 0.012, 0.002), 0.53),
    "deflector_blue": Material((0.033, 0.168, 0.455), (0.014, 0.080, 0.220), 0.43),
    "nozzle_dark": Material((0.014, 0.020, 0.028), (0.005, 0.018, 0.040), 0.75),
}


def add(a: tuple[float, float, float], b: tuple[float, float, float]):
    return a[0] + b[0], a[1] + b[1], a[2] + b[2]


def sub(a: tuple[float, float, float], b: tuple[float, float, float]):
    return a[0] - b[0], a[1] - b[1], a[2] - b[2]


def mul(a: tuple[float, float, float], s: float):
    return a[0] * s, a[1] * s, a[2] * s


def dot(a: tuple[float, float, float], b: tuple[float, float, float]):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def cross(a: tuple[float, float, float], b: tuple[float, float, float]):
    return a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]


def unit(a: tuple[float, float, float]):
    length = sum(v * v for v in a) ** 0.5
    if length < 1e-12:
        raise ValueError("Cannot normalize zero vector")
    return tuple(v / length for v in a)


class Mesh:
    def __init__(self):
        self.vertices: list[tuple[float, float, float]] = []
        self.normals: list[tuple[float, float, float]] = []
        self.uvs: list[tuple[float, float]] = []
        self.faces: list[tuple[int, int, str, str]] = []

    def triangle(self, p0, p1, p2, n0, n1, n2, material: str, group: str):
        n0, n1, n2 = unit(n0), unit(n1), unit(n2)
        face = cross(sub(p1, p0), sub(p2, p0))
        if dot(face, add(add(n0, n1), n2)) < 0:
            p1, p2, n1, n2 = p2, p1, n2, n1
        if dot(cross(sub(p1, p0), sub(p2, p0)), cross(sub(p1, p0), sub(p2, p0))) <= 1e-16:
            raise ValueError(f"Degenerate triangle in {group}")
        faceNormal = unit(cross(sub(p1, p0), sub(p2, p0)))
        axis = max(range(3), key=lambda i: abs(faceNormal[i]))
        for p, n in ((p0, n0), (p1, n1), (p2, n2)):
            self.vertices.append(p)
            self.normals.append(n)
            if axis == 0:
                uv = (p[2] * 2.0 + .31, p[1] * 2.0 + .17)
            elif axis == 1:
                uv = (p[0] * 2.0 + .13, p[2] * 2.0 + .27)
            else:
                uv = (p[0] * 2.0 + .19, p[1] * 2.0 + .23)
            self.uvs.append(uv)
        base = len(self.vertices) - 3
        self.faces.append((base, base + 1, base + 2, material, group))

    def quad(self, p0, p1, p2, p3, n0, n1, n2, n3, material: str, group: str):
        self.triangle(p0, p1, p2, n0, n1, n2, material, group)
        self.triangle(p0, p2, p3, n0, n2, n3, material, group)

    def write(self):
        ASSET_DIR.mkdir(parents=True, exist_ok=True)
        with MTL_PATH.open("w", encoding="utf-8", newline="\n") as out:
            out.write("# Nebula standalone Constitution-inspired starship materials\n")
            for name, material in MATERIALS.items():
                out.write(f"\nnewmtl {name}\n")
                out.write("Kd %.6f %.6f %.6f\n" % material.diffuse)
                out.write("Ke %.6f %.6f %.6f\n" % material.emission)
                out.write("Ks %.6f %.6f %.6f\n" % ((0.10, 0.12, 0.15) if material.roughness > 0.6 else (0.25, 0.33, 0.42)))
                out.write(f"Ns {max(2.0, (1.0 - material.roughness) * 96.0):.3f}\n")
                out.write(f"Pr {material.roughness:.3f}\n")
                out.write("d 1.0\n")
                out.write("map_Kd constitution_starship_imagegen_basecolor.png\n")
                out.write("map_Bump constitution_starship_normal.png\n")
                out.write("map_Pr constitution_starship_metallic_roughness.png\n")
                out.write("map_Ke constitution_starship_emissive.png\n")
        with OBJ_PATH.open("w", encoding="utf-8", newline="\n") as out:
            out.write("# Nebula standalone Constitution-inspired starship\n")
            out.write("# Canonical axes: +Y up, +Z forward; local envelope x=+/-0.42 y=+/-0.23 z=+/-0.66\n")
            out.write("# Twin nacelles: x=+/-0.255, y=0.115; nozzle plane z=-0.56\n")
            out.write("mtllib constitution_starship.mtl\n")
            for p in self.vertices:
                out.write("v %.6f %.6f %.6f\n" % p)
            for n in self.normals:
                out.write("vn %.6f %.6f %.6f\n" % n)
            for uv in self.uvs:
                out.write("vt %.6f %.6f\n" % uv)
            current_group = None
            current_material = None
            for a, b, c, material, group in self.faces:
                if group != current_group:
                    out.write(f"g {group}\n")
                    current_group = group
                if material != current_material:
                    out.write(f"usemtl {material}\n")
                    current_material = material
                out.write(f"f {a + 1}/{a + 1}/{a + 1} {b + 1}/{b + 1}/{b + 1} {c + 1}/{c + 1}/{c + 1}\n")
        return len(self.vertices) // 3


def periodic_noise(x: int, y: int, period: int, seed: int) -> float:
    x %= period
    y %= period
    value = (x * 374761393 + y * 668265263 + seed * 69069) & 0xffffffff
    value = ((value ^ (value >> 13)) * 1274126177) & 0xffffffff
    return ((value ^ (value >> 16)) & 0xffff) / 65535.0


def png(path: Path, width: int, height: int, rows):
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for r, g, b, a in rows(y):
            raw.extend((r, g, b, a))

    def chunk(kind: bytes, data: bytes):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)

    data = b"\x89PNG\r\n\x1a\n"
    data += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    data += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    data += chunk(b"IEND", b"")
    path.write_bytes(data)


def height_sample(u: float, v: float) -> float:
    x, y = int(u * 512), int(v * 512)
    plate_x, plate_y = x // 32, y // 32
    plate = periodic_noise(plate_x, plate_y, 16, 17)
    seam_x = min((x % 32) / 32.0, 1.0 - (x % 32) / 32.0)
    seam_y = min((y % 32) / 32.0, 1.0 - (y % 32) / 32.0)
    seam = min(seam_x, seam_y)
    seam_depth = 1.0 - smooth(0.0, .035, seam)
    micro = periodic_noise(x // 2, y // 2, 256, 29)
    brushed = .5 + .5 * sin((x + y * .35) * .34 + micro * 2.2)
    return .62 + plate * .10 + brushed * .04 - seam_depth * .22


def smooth(edge0: float, edge1: float, x: float) -> float:
    t = max(0.0, min(1.0, (x - edge0) / (edge1 - edge0)))
    return t * t * (3.0 - 2.0 * t)


def write_pbr_textures():
    size = 512
    albedo_rows = []
    normal_rows = []
    orm_rows = []
    emissive_rows = []
    for y in range(size):
        albedo_rows.append([])
        normal_rows.append([])
        orm_rows.append([])
        emissive_rows.append([])
        for x in range(size):
            plate_x, plate_y = x // 32, y // 32
            plate = periodic_noise(plate_x, plate_y, 16, 5)
            grain = periodic_noise(x // 2, y // 2, 256, 11)
            seam_x = min((x % 32) / 32.0, 1.0 - (x % 32) / 32.0)
            seam_y = min((y % 32) / 32.0, 1.0 - (y % 32) / 32.0)
            seam = min(seam_x, seam_y)
            seam_depth = 1.0 - smooth(0.0, .035, seam)
            base = int(132 + plate * 70 + grain * 20 - seam_depth * 80)
            albedo_rows[-1].append((base, base, min(255, base + 3), 255))

            dx = (height_sample((x + 1) / size, y / size) - height_sample((x - 1) / size, y / size)) * 2.8
            dy = (height_sample(x / size, (y + 1) / size) - height_sample(x / size, (y - 1) / size)) * 2.8
            normal = unit((-dx, -dy, 1.0))
            normal_rows[-1].append(tuple(max(0, min(255, round((c * .5 + .5) * 255))) for c in normal) + (255,))

            metal = int(96 + plate * 44 - seam_depth * 18)
            roughness = int(112 + grain * 64 + seam_depth * 45)
            ao = int(222 - seam_depth * 105 - grain * 15)
            orm_rows[-1].append((metal, roughness, ao, 255))

            glow = int(205 + grain * 35 - seam_depth * 28)
            emissive_rows[-1].append((glow, glow, glow, 255))

    png(ASSET_DIR / "constitution_starship_basecolor.png", size, size, lambda y: albedo_rows[y])
    png(ASSET_DIR / "constitution_starship_normal.png", size, size, lambda y: normal_rows[y])
    png(ASSET_DIR / "constitution_starship_metallic_roughness.png", size, size, lambda y: orm_rows[y])
    png(ASSET_DIR / "constitution_starship_emissive.png", size, size, lambda y: emissive_rows[y])


def add_lathe_y(mesh: Mesh, center_z: float, profile, segment_materials, segment_groups,
                radial_steps=48):
    """Revolve a top-to-bottom (radius, y) profile around Y."""
    segment_normals = []
    for (r0, y0), (r1, y1) in zip(profile, profile[1:]):
        segment_normals.append(unit((- (y1 - y0), r1 - r0)))

    point_normals = []
    for i in range(len(profile)):
        adjacent = []
        if i > 0:
            adjacent.append(segment_normals[i - 1])
        if i < len(profile) - 1:
            adjacent.append(segment_normals[i])
        nr = sum(n[0] for n in adjacent)
        ny = sum(n[1] for n in adjacent)
        point_normals.append(unit((nr, ny)))

    for seg, ((r0, y0), (r1, y1)) in enumerate(zip(profile, profile[1:])):
        material = segment_materials[seg]
        group = segment_groups[seg]
        for j in range(radial_steps):
            a0, a1 = 2 * pi * j / radial_steps, 2 * pi * (j + 1) / radial_steps
            c0, s0, c1, s1 = cos(a0), sin(a0), cos(a1), sin(a1)
            p00 = (r0 * c0, y0, center_z + r0 * s0)
            p01 = (r0 * c1, y0, center_z + r0 * s1)
            p10 = (r1 * c0, y1, center_z + r1 * s0)
            p11 = (r1 * c1, y1, center_z + r1 * s1)
            n00 = (point_normals[seg][0] * c0, point_normals[seg][1], point_normals[seg][0] * s0)
            n01 = (point_normals[seg][0] * c1, point_normals[seg][1], point_normals[seg][0] * s1)
            n10 = (point_normals[seg + 1][0] * c0, point_normals[seg + 1][1], point_normals[seg + 1][0] * s0)
            n11 = (point_normals[seg + 1][0] * c1, point_normals[seg + 1][1], point_normals[seg + 1][0] * s1)
            if r0 < 1e-8:
                mesh.triangle(p00, p10, p11, n00, n10, n11, material, group)
            elif r1 < 1e-8:
                mesh.triangle(p00, p10, p01, n00, n10, n01, material, group)
            else:
                mesh.quad(p00, p10, p11, p01, n00, n10, n11, n01, material, group)


def add_lathe_z(mesh: Mesh, center_x: float, center_y: float, profile, segment_materials,
                segment_groups, radial_steps=32):
    """Revolve a rear-to-front (z, radius) profile around Z and close both ends."""
    for seg, ((z0, r0), (z1, r1)) in enumerate(zip(profile, profile[1:])):
        material = segment_materials[seg]
        group = segment_groups[seg]
        slope = -(r1 - r0) / max(1e-8, z1 - z0)
        for j in range(radial_steps):
            a0, a1 = 2 * pi * j / radial_steps, 2 * pi * (j + 1) / radial_steps
            c0, s0, c1, s1 = cos(a0), sin(a0), cos(a1), sin(a1)
            n0 = unit((c0, s0, slope))
            n1 = unit((c1, s1, slope))
            p00 = (center_x + r0 * c0, center_y + r0 * s0, z0)
            p01 = (center_x + r0 * c1, center_y + r0 * s1, z0)
            p10 = (center_x + r1 * c0, center_y + r1 * s0, z1)
            p11 = (center_x + r1 * c1, center_y + r1 * s1, z1)
            if r0 < 1e-8:
                mesh.triangle(p00, p10, p11, n0, n1, n1, material, group)
            elif r1 < 1e-8:
                mesh.triangle(p00, p10, p01, n0, n0, n1, material, group)
            else:
                mesh.quad(p00, p10, p11, p01, n0, n0, n1, n1, material, group)

    z0, r0 = profile[0]
    z1, r1 = profile[-1]
    if r0 > 1e-8:
        group = segment_groups[0]
        material = segment_materials[0]
        center = (center_x, center_y, z0)
        for j in range(radial_steps):
            a0, a1 = 2 * pi * j / radial_steps, 2 * pi * (j + 1) / radial_steps
            p0 = (center_x + r0 * cos(a0), center_y + r0 * sin(a0), z0)
            p1 = (center_x + r0 * cos(a1), center_y + r0 * sin(a1), z0)
            mesh.triangle(center, p0, p1, (0, 0, -1), (0, 0, -1), (0, 0, -1), material, group)
    if r1 > 1e-8:
        group = segment_groups[-1]
        material = segment_materials[-1]
        center = (center_x, center_y, z1)
        for j in range(radial_steps):
            a0, a1 = 2 * pi * j / radial_steps, 2 * pi * (j + 1) / radial_steps
            p0 = (center_x + r1 * cos(a0), center_y + r1 * sin(a0), z1)
            p1 = (center_x + r1 * cos(a1), center_y + r1 * sin(a1), z1)
            mesh.triangle(center, p0, p1, (0, 0, 1), (0, 0, 1), (0, 0, 1), material, group)


def add_cylinder_patch_z(mesh: Mesh, center_x: float, z0: float, z1: float, radius: float,
                         center_y: float, theta0: float, theta1: float, material: str, group: str,
                         angular_steps=5):
    for i in range(angular_steps):
        a0 = theta0 + (theta1 - theta0) * i / angular_steps
        a1 = theta0 + (theta1 - theta0) * (i + 1) / angular_steps
        n0 = (cos(a0), sin(a0), 0.0)
        n1 = (cos(a1), sin(a1), 0.0)
        p00 = (center_x + radius * cos(a0), center_y + radius * sin(a0), z0)
        p01 = (center_x + radius * cos(a1), center_y + radius * sin(a1), z0)
        p10 = (center_x + radius * cos(a0), center_y + radius * sin(a0), z1)
        p11 = (center_x + radius * cos(a1), center_y + radius * sin(a1), z1)
        mesh.quad(p00, p10, p11, p01, n0, n0, n1, n1, material, group)


def add_cylinder_patch_y(mesh: Mesh, center_z: float, y0: float, y1: float, radius: float,
                         theta0: float, theta1: float, material: str, group: str,
                         angular_steps=2):
    for i in range(angular_steps):
        a0 = theta0 + (theta1 - theta0) * i / angular_steps
        a1 = theta0 + (theta1 - theta0) * (i + 1) / angular_steps
        n0 = (cos(a0), 0.0, sin(a0))
        n1 = (cos(a1), 0.0, sin(a1))
        p00 = (radius * cos(a0), y0, center_z + radius * sin(a0))
        p01 = (radius * cos(a1), y0, center_z + radius * sin(a1))
        p10 = (radius * cos(a0), y1, center_z + radius * sin(a0))
        p11 = (radius * cos(a1), y1, center_z + radius * sin(a1))
        mesh.quad(p00, p10, p11, p01, n0, n0, n1, n1, material, group)


def add_box(mesh: Mesh, center, half, material: str, group: str):
    x, y, z = center
    hx, hy, hz = half
    p = [
        (x - hx, y - hy, z - hz), (x + hx, y - hy, z - hz),
        (x + hx, y + hy, z - hz), (x - hx, y + hy, z - hz),
        (x - hx, y - hy, z + hz), (x + hx, y - hy, z + hz),
        (x + hx, y + hy, z + hz), (x - hx, y + hy, z + hz),
    ]
    faces = [
        (0, 1, 2, 3, (0, 0, -1)), (7, 6, 5, 4, (0, 0, 1)),
        (0, 4, 5, 1, (0, -1, 0)), (3, 2, 6, 7, (0, 1, 0)),
        (0, 3, 7, 4, (-1, 0, 0)), (1, 5, 6, 2, (1, 0, 0)),
    ]
    for a, b, c, d, n in faces:
        mesh.quad(p[a], p[b], p[c], p[d], n, n, n, n, material, group)


def add_extruded_xy(mesh: Mesh, points, z0: float, z1: float, material: str, group: str,
                    mirror_x: bool = False):
    pts = [((-x if mirror_x else x), y) for x, y in points]
    front = [(x, y, z0) for x, y in pts]
    back = [(x, y, z1) for x, y in pts]
    for i in range(1, len(pts) - 1):
        mesh.triangle(front[0], front[i], front[i + 1],
                      (0, 0, -1), (0, 0, -1), (0, 0, -1), material, group)
        mesh.triangle(back[0], back[i + 1], back[i],
                      (0, 0, 1), (0, 0, 1), (0, 0, 1), material, group)
    for i in range(len(pts)):
        j = (i + 1) % len(pts)
        edge = sub(back[i], front[i])
        normal = unit(cross(edge, sub(front[j], front[i])))
        mesh.quad(front[i], front[j], back[j], back[i], normal, normal, normal, normal, material, group)


def add_extruded_yz(mesh: Mesh, points, x0: float, x1: float, material: str, group: str):
    left = [(x0, y, z) for z, y in points]
    right = [(x1, y, z) for z, y in points]
    for i in range(1, len(points) - 1):
        mesh.triangle(left[0], left[i + 1], left[i],
                      (-1, 0, 0), (-1, 0, 0), (-1, 0, 0), material, group)
        mesh.triangle(right[0], right[i], right[i + 1],
                      (1, 0, 0), (1, 0, 0), (1, 0, 0), material, group)
    for i in range(len(points)):
        j = (i + 1) % len(points)
        edge = sub(right[i], left[i])
        normal = unit(cross(edge, sub(left[j], left[i])))
        mesh.quad(left[i], left[j], right[j], right[i], normal, normal, normal, normal, material, group)


def build():
    mesh = Mesh()

    # Primary hull: a broad, layered circular saucer with the same plan silhouette as the references.
    add_lathe_y(
        mesh, 0.228,
        [(0, .112), (.045, .112), (.15, .107), (.235, .100), (.300, .090),
         (.333, .078), (.345, .064), (.336, .051), (.300, .041), (.230, .034),
         (.120, .029), (0, .026)],
        ["hull_mid", "hull_mid", "hull_mid", "hull_light", "hull_mid",
         "hull_dark", "hull_light", "hull_dark", "hull_mid", "hull_dark", "hull_mid"],
        ["saucer_top", "saucer_top", "saucer_top", "saucer_top", "saucer_top",
         "saucer_rim", "saucer_rim", "saucer_lower", "saucer_lower", "saucer_lower", "saucer_lower"],
        64,
    )

    # Raised command decks use hard annuli so the near silhouette keeps the reference's stepped bridge.
    add_lathe_y(
        mesh, 0.214,
        [(0, .132), (.032, .129), (.055, .120), (.105, .111), (.105, .100), (0, .100)],
        ["hull_mid", "hull_light", "hull_mid", "hull_light", "hull_dark"],
        ["bridge", "bridge", "bridge", "bridge_deck", "bridge_deck"], 32,
    )
    add_lathe_y(
        mesh, 0.218,
        [(0, .146), (.020, .145), (.036, .139), (.046, .126), (.050, .115), (0, .115)],
        ["hull_light", "hull_light", "hull_mid", "hull_mid", "hull_dark"],
        ["bridge", "bridge", "bridge", "bridge", "bridge"], 32,
    )

    # A shallow aft dorsal fairing reinforces the reference profile.
    add_extruded_xy(mesh, [(-.026, .047), (.026, .047), (.045, .070), (-.045, .070)],
                    .045, .392, "hull_mid", "saucer_dorsal")

    # Paired impulse housings sit in the trailing saucer edge.
    for sign in (-1, 1):
        add_box(mesh, (sign * .073, .072, -.091), (.031, .012, .027), "hull_dark", "impulse_housing")
        add_box(mesh, (sign * .073, .074, -.118), (.026, .008, .002), "impulse_red", "impulse_glow")

    # Aft saucer rim windows: repeated sparse blocks remain visible at the target framing.
    rim_radius = .346
    for i in range(26):
        a0 = -0.05 + i * (2 * pi / 34)
        a1 = a0 + .035
        material = "window_warm" if i % 5 else "window_cool"
        add_cylinder_patch_y(mesh, .228, .061, .068, rim_radius, a0, a1,
                             material, "saucer_windows", 2)

    # Swept trapezoidal dorsal neck, strongly tapered toward the secondary hull.
    add_extruded_yz(
        mesh,
        [(.082, .043), (-.075, .019), (-.155, -.025), (-.145, -.064),
         (-.075, -.050), (.078, .010)],
        -.039, .039, "hull_mid", "neck",
    )
    add_extruded_yz(
        mesh,
        [(.055, .033), (-.060, .012), (-.130, -.023), (-.105, -.038),
         (-.040, -.018), (.052, .015)],
        -.050, .050, "hull_dark", "neck_spine",
    )

    # Long secondary hull: rounded D-profile, tapered rear, distinct stern cap and forward deflector.
    add_lathe_z(
        mesh, 0.0, -.071,
        [(-.423, .018), (-.405, .038), (-.355, .052), (-.280, .063),
         (-.140, .078), (.010, .084), (.110, .077), (.150, .058), (.168, .027)],
        ["trim_dark", "hull_mid", "hull_mid", "hull_mid", "hull_mid",
         "hull_light", "hull_mid", "hull_dark"],
        ["engineering_stern", "engineering_hull", "engineering_hull", "engineering_hull",
         "engineering_hull", "engineering_hull", "engineering_hull", "engineering_hull"],
        48,
    )
    add_box(mesh, (0, .012, -.12), (.038, .018, .245), "hull_dark", "engineering_dorsal")

    # Navigational deflector: copper rim around a restrained blue center.
    add_lathe_z(
        mesh, 0.0, -.071,
        [(.162, .020), (.176, .043), (.181, .069), (.171, .079), (.162, .069), (.162, .020)],
        ["deflector_blue", "deflector_copper", "deflector_copper", "deflector_blue", "deflector_blue"],
        ["deflector", "deflector", "deflector", "deflector", "deflector"], 48,
    )

    # Hangar: raised frame plus deep black inset at the stern, readable in oblique near shots.
    add_box(mesh, (0, -.074, -.424), (.035, .026, .004), "hull_light", "hangar")
    add_box(mesh, (0, -.074, -.429), (.028, .019, .002), "hangar_dark", "hangar")
    add_box(mesh, (0, -.074, -.432), (.014, .005, .001), "window_cool", "hangar")

    # Side windows along the secondary hull.
    for side in (-1, 1):
        for i in range(7):
            add_box(mesh, (side * .076, -.045 + (i % 2) * .015, -.26 + i * .050),
                    (.002, .004, .010), "window_warm", "secondary_windows")

    # Broad swept pylon blades, with inner reinforcement ribs for depth.
    pylon = [(.059, -.060), (.059, .004), (.232, .091), (.232, .061)]
    rib = [(.067, -.048), (.067, -.018), (.218, .068), (.218, .055)]
    for side in (-1, 1):
        group = "pylon_left" if side < 0 else "pylon_right"
        add_extruded_xy(mesh, pylon, -.281, -.244, "hull_mid", group, side < 0)
        add_extruded_xy(mesh, rib, -.239, -.235, "hull_light", group, side < 0)
        add_box(mesh, (side * .236, .080, -.263), (.027, .024, .045), "hull_dark", group)

    # Twin nacelles preserve the CPU particle axes and end on the exact nozzle plane.
    for sign in (-1, 1):
        side = "left" if sign < 0 else "right"
        cx = sign * .255
        group = f"nacelle_{side}"
        add_lathe_z(
            mesh, cx, .115,
            [(-.560, .018), (-.548, .031), (-.535, .039), (-.522, .036),
             (-.120, .036), (-.018, .037), (.004, .041), (.026, .043),
             (.047, .036), (.061, .021), (.069, 0.0)],
            ["nozzle_dark", "hull_mid", "hull_mid", "hull_dark", "hull_mid",
             "hull_light", "collector_red", "collector_red", "hull_dark", "trim_dark"],
            [f"nozzle_{side}", group, group, group, group, group,
             f"collector_{side}", f"collector_{side}", group, group],
            40,
        )
        # Refit-style grille bays: 19 raised blue panels per outer flank and top facet.
        for bay in range(19):
            z0 = -.510 + bay * .024
            z1 = z0 + .019
            coil_group = f"coil_{side}_{bay:02d}"
            add_cylinder_patch_z(mesh, cx, z0, z1, .0382, .115, -.34, .34,
                                 "coil_blue", coil_group, 5)
            add_cylinder_patch_z(mesh, cx, z0, z1, .0382, .115, pi / 2 - .26, pi / 2 + .26,
                                 "coil_blue", coil_group, 4)
            # Dark grille rails separate the blue panels without adding tiny disconnected parts.
            add_cylinder_patch_z(mesh, cx, z0 - .0015, z0 + .0015, .0385, .115, -.40, .40,
                                 "trim_dark", group, 5)
        # Rear nozzle rim, pylon-root cradle and dorsal intercooler rail.
        add_cylinder_patch_z(mesh, cx, -.552, -.532, .0405, .115, 0, 2 * pi,
                             "hull_mid", f"nozzle_{side}", 20)
        add_box(mesh, (cx, .115, -.5585), (.022, .022, .0015), "nozzle_dark", f"nozzle_{side}")
        add_box(mesh, (cx, .151, -.465), (.008, .009, .050), "hull_light", group)

    # Small phaser strips and impulse deck lamps provide material accents without extra silhouette clutter.
    for sign in (-1, 1):
        add_box(mesh, (sign * .258, .088, -.352), (.018, .004, .045), "window_cool", "nacelle_detail")
        add_box(mesh, (sign * .152, .061, -.010), (.012, .004, .020), "window_cool", "saucer_detail")

    triangles = mesh.write()
    write_pbr_textures()
    xs = [p[0] for p in mesh.vertices]
    ys = [p[1] for p in mesh.vertices]
    zs = [p[2] for p in mesh.vertices]
    print(f"Wrote {OBJ_PATH} and {MTL_PATH}: {triangles} triangles, "
          f"bounds x[{min(xs):.4f},{max(xs):.4f}] y[{min(ys):.4f},{max(ys):.4f}] "
          f"z[{min(zs):.4f},{max(zs):.4f}]")


if __name__ == "__main__":
    build()
