# Draws the mod icon (32x32 pixel art, scaled up) and a two-frame GIF of it for the README.
import sys
from PIL import Image

BG, FRAME = (26, 26, 26), (79, 214, 79)
SKIN, HAIR, SHIRT, PANTS, SHOE, LINE = (224, 172, 125), (74, 48, 28), (38, 166, 154), (58, 76, 170), (60, 60, 60), (255, 255, 255)

def frame(up):
    im = Image.new("RGB", (32, 32), BG)
    def box(x0, y0, x1, y1, c):
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                im.putpixel((x, y), c)
    for i in range(32):  # frame like the site's pack icons
        for j in (0, 1, 30, 31):
            im.putpixel((i, j), FRAME); im.putpixel((j, i), FRAME)
    d = -2 if up else 0                      # the hips move, the rest stays
    box(13, 19, 16, 27, PANTS)               # back leg
    box(16, 20, 19, 27, PANTS)               # front leg
    box(12, 28, 17, 29, SHOE); box(16, 28, 21, 29, SHOE)
    box(7, 13 + d, 16, 20 + d, PANTS)        # hips, sticking out to the left
    box(8, 14 + d, 9, 15 + d, (86, 106, 204))  # highlight
    box(14, 11, 23, 16, SHIRT)               # torso, leaning forward
    box(20, 17, 22, 23, SKIN)                # arm, hand on the knee
    box(22, 5, 28, 12, SKIN)                 # head
    box(22, 4, 28, 6, HAIR); box(22, 4, 23, 9, HAIR)
    box(26, 8, 27, 9, (40, 40, 40))          # eye
    for y in ((8, 12, 16) if up else (13, 17, 21)):  # motion lines behind
        box(3, y, 5, y, LINE)
    box(2, 30 - 3, 29, 30 - 3 + 0, BG)
    return im.resize((256, 256), Image.NEAREST)

png, png2, gif = sys.argv[1], sys.argv[2], sys.argv[3]
a, b = frame(False), frame(True)
a.save(png)
b.save(png2)  # second frame, for the animation in the picker
a.save(gif, save_all=True, append_images=[b], duration=160, loop=0)
