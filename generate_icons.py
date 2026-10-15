import os
from PIL import Image, ImageDraw, ImageFont

os.makedirs('static/icons', exist_ok=True)

def create_app_icon(size):
    # Create OLED pure black background with rounded corners (squircle)
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    
    corner_radius = int(size * 0.22)
    
    # Outer background: Rich dark gradient / solid OLED black with subtle emerald rim
    draw.rounded_rectangle(
        [(0, 0), (size - 1, size - 1)],
        radius=corner_radius,
        fill=(8, 11, 16, 255),
        outline=(0, 245, 160, 100),
        width=max(2, int(size * 0.015))
    )
    
    # Inner glowing circle
    center = size / 2
    r_outer = size * 0.38
    r_inner = size * 0.34
    
    # Glow layers
    for i in range(12, 0, -2):
        alpha = int(18 - i * 1.2)
        r = r_outer + i * (size * 0.01)
        draw.ellipse(
            [(center - r, center - r), (center + r, center + r)],
            outline=(0, 245, 160, max(0, alpha)),
            width=int(size * 0.02)
        )
    
    # Draw omega symbol Ω or stylized geometric mark
    # Let's draw high-quality stylized Ω geometry
    scale = size / 100.0
    stroke_w = max(3, int(7 * scale))
    
    # Omega circle part (open at bottom)
    arc_box = [
        center - 26 * scale,
        center - 32 * scale,
        center + 26 * scale,
        center + 20 * scale
    ]
    draw.arc(arc_box, start=40, end=140, fill=(0, 245, 160, 255), width=stroke_w)
    
    # Feet of Omega
    # Left foot
    draw.line([
        (center - 19 * scale, center + 14 * scale),
        (center - 30 * scale, center + 14 * scale)
    ], fill=(0, 245, 160, 255), width=stroke_w)
    
    # Right foot
    draw.line([
        (center + 19 * scale, center + 14 * scale),
        (center + 30 * scale, center + 14 * scale)
    ], fill=(0, 245, 160, 255), width=stroke_w)

    # Core high-tech dot in center
    dot_r = 3.5 * scale
    draw.ellipse([
        (center - dot_r, center - 6 * scale - dot_r),
        (center + dot_r, center - 6 * scale + dot_r)
    ], fill=(255, 255, 255, 240))
    
    return img

for sz in [64, 180, 192, 512]:
    icon = create_app_icon(sz)
    if sz == 180:
        icon.save('static/icons/apple-touch-icon.png', 'PNG')
    elif sz == 64:
        icon.save('static/icons/favicon.png', 'PNG')
    else:
        icon.save(f'static/icons/icon-{sz}.png', 'PNG')

print("All icons successfully generated in static/icons/")
