import tifffile
import numpy as np

def create_gradient(bits, size=256):
    """
    Creates an RGB gradient where Red is horizontal,
    Green is vertical, and Blue is constant.
    Values are scaled to the specified bit depth.
    """
    max_val = (2**bits) - 1
    # Create a linear ramp from 0 to max_val
    grid = np.linspace(0, max_val, size).astype(np.uint16)

    r = np.tile(grid, (size, 1))
    g = np.tile(grid, (size, 1)).T
    b = np.full((size, size), max_val // 2, dtype=np.uint16)

    return np.stack([r, g, b], axis=-1)

# 1. 3-bit Lossless JPEG (Values 0-7)
# We must use uint8 for < 8 bits, but tell tifffile the target bitdepth
rgb_3bit = create_gradient(3).astype(np.uint8)
tifffile.imwrite(
    'color_3bit_lossless_jpeg.tif',
    rgb_3bit,
    photometric='rgb',
    compression='jpeg',
    # 'bitsperpample' (typo fixed to 'bitspersample') is handled via extrasamples
    # or by passing the sampleformat/bits configuration explicitly
    extrasamples=[],
    compressionargs={'lossless': True, 'bitspersample': 3}
)

# 2. 6-bit Lossless JPEG (Values 0-63)
rgb_6bit = create_gradient(6).astype(np.uint8)
tifffile.imwrite(
    'color_6bit_lossless_jpeg.tif',
    rgb_6bit,
    photometric='rgb',
    compression='jpeg',
    compressionargs={'lossless': True, 'bitspersample': 6}
)

# 3. 12-bit Lossless JPEG (Values 0-4095)
# This was your working case
rgb_12bit = create_gradient(12).astype(np.uint16)
tifffile.imwrite(
    'color_12bit_lossless_jpeg.tif',
    rgb_12bit,
    photometric='rgb',
    compression='jpeg',
    compressionargs={'lossless': True, 'bitspersample': 12}
)

# 4. 16-bit Lossless JPEG (Values 0-65535)
rgb_16bit = create_gradient(16).astype(np.uint16)
tifffile.imwrite(
    'color_16bit_lossless_jpeg.tif',
    rgb_16bit,
    photometric='rgb',
    compression='jpeg',
    compressionargs={'lossless': True, 'bitspersample': 16}
)

print("Done. All files created.")