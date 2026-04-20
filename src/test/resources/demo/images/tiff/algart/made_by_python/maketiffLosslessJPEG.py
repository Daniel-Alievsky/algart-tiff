import tifffile
import numpy as np

# 1. Create 12-bit gradient (Values 0-4095)
# Generate a row from 0 to 4095 with 512 steps
row_12bit = np.linspace(0, 4095, 512).astype(np.uint16)
data_12bit = np.tile(row_12bit, (512, 1))

tifffile.imwrite(
    'gradient_12bit_lossless.tif', 
    data_12bit, 
    photometric='minisblack',
    compression='jpeg',
    compressionargs={'lossless': True}
)

# 2. Create 8-bit gradient (Values 0-255)
# Generate a row by repeating 0-255 twice to fill 512 pixels
row_8bit = np.tile(np.arange(256, dtype=np.uint8), 2)
data_8bit = np.tile(row_8bit, (512, 1))

tifffile.imwrite(
    'gradient_8bit_lossless.tif', 
    data_8bit, 
    photometric='minisblack',
    compression='jpeg',
    compressionargs={'lossless': True}
)

print("Done. Files created: gradient_12bit_lossless.tif, gradient_8bit_lossless.tif")