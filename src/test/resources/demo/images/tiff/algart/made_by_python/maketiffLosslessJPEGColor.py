import tifffile
import numpy as np

# 1. Create 8-bit RGB gradient
# R: horizontal gradient, G: vertical gradient, B: constant
grid = np.arange(256, dtype=np.uint8)
r_channel = np.tile(grid, (256, 1))
g_channel = np.tile(grid, (256, 1)).T
b_channel = np.full((256, 256), 128, dtype=np.uint8)

rgb_8bit = np.stack([r_channel, g_channel, b_channel], axis=-1)

tifffile.imwrite(
    'color_8bit_lossless_jpeg.tif',
    rgb_8bit,
    photometric='rgb',
    compression='jpeg',
    compressionargs={'lossless': True}
)

# 2. Create 12-bit RGB gradient (0-4095)
grid_12 = np.linspace(0, 4095, 256).astype(np.uint16)
r_12 = np.tile(grid_12, (256, 1))
g_12 = np.tile(grid_12, (256, 1)).T
b_12 = np.full((256, 256), 2048, dtype=np.uint16)

rgb_12bit = np.stack([r_12, g_12, b_12], axis=-1)

tifffile.imwrite(
    'color_12bit_lossless_jpeg.tif',
    rgb_12bit,
    photometric='rgb',
    compression='jpeg',
    compressionargs={'lossless': True}
)

print("Done. Color files created.")