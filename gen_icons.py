from PIL import Image
import os

src_path = r"C:\Users\21883\Desktop\7610cce8696d3541-cat-scale-calibration.png"
res_root = r"C:\Users\21883\Desktop\Pawchive-1.8.4\app\src\main\res"

# mipmap densities and launcher icon sizes (px)
sizes = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# adaptive icon foreground sizes (108dp x density)
fg_sizes = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}

src = Image.open(src_path).convert("RGBA")

for density, size in sizes.items():
    mipmap_dir = os.path.join(res_root, f"mipmap-{density}")
    os.makedirs(mipmap_dir, exist_ok=True)

    # ic_launcher.png - square
    launcher = src.resize((size, size), Image.LANCZOS)
    launcher.save(os.path.join(mipmap_dir, "ic_launcher.png"))

    # ic_launcher_round.png - circle mask
    mask = Image.new("L", (size, size), 0)
    from PIL import ImageDraw
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0, size, size), fill=255)
    round_icon = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    round_icon.paste(launcher, (0, 0), mask)
    round_icon.save(os.path.join(mipmap_dir, "ic_launcher_round.png"))

    # ic_launcher_foreground.png - adaptive icon foreground (centered, with safe zone padding)
    fg_size = fg_sizes[density]
    # The foreground image should have the icon in the center ~66% (safe zone)
    # Scale the source to fit within the safe zone (72/108 = 2/3 of the total)
    icon_size = int(fg_size * 0.66)
    fg_icon = src.resize((icon_size, icon_size), Image.LANCZOS)
    fg = Image.new("RGBA", (fg_size, fg_size), (0, 0, 0, 0))
    offset = (fg_size - icon_size) // 2
    fg.paste(fg_icon, (offset, offset), fg_icon)
    fg.save(os.path.join(mipmap_dir, "ic_launcher_foreground.png"))

    print(f"Generated {density}: launcher={size}px, fg={fg_size}px")

print("Done!")
