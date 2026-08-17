import numpy as np
import tifffile

gradient = np.tile(np.arange(256, dtype=np.uint8), (256, 1))

tifffile.imwrite("gradient_jpeg2000.tiff", gradient, compression='jpeg2000')
