
"""
Create SGI LogLuv / LogL TIFF files using OpenCV.

Usage:
  python make_logluv.py <input> <output.tif> [--gray] [--luv24]

Examples:
  python make_logluv.py photo.jpg          test_luv32.tif
  python make_logluv.py hdr.exr            test_luv32.tif
  python make_logluv.py gray.png           test_l16.tif --gray
  python make_logluv.py photo.jpg          test_luv24.tif --luv24 (does not work well in current version)
"""

import sys
import argparse
import numpy as np
import cv2


def load_image(path: str, as_gray: bool = False) -> np.ndarray:
    """Load image and convert to float32 linear-ish data."""
    flags = cv2.IMREAD_GRAYSCALE if as_gray else cv2.IMREAD_COLOR
    img = cv2.imread(path, flags | cv2.IMREAD_ANYDEPTH)

    if img is None:
        raise FileNotFoundError(f"Cannot read image: {path}")

    # Convert to float32
    if img.dtype == np.uint8:
        img = img.astype(np.float32) / 255.0
    elif img.dtype == np.uint16:
        img = img.astype(np.float32) / 65535.0
    else:
        img = img.astype(np.float32)

    # OpenCV loads BGR → convert to RGB for LogLuv
    if not as_gray and img.ndim == 3 and img.shape[2] == 3:
        img = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)

    # Make sure we have some dynamic range (optional stretch)
    # img = np.clip(img, 0.0, None)  # negative values not useful

    return img


def write_logluv(path: str, img: np.ndarray, use_24bit: bool = False) -> None:
    """Write float image as SGI LogLuv (32-bit) or LogLuv24."""
    if img.ndim == 2:
        # Grayscale → duplicate to 3 channels (OpenCV LogL path is weak)
        img = np.stack([img, img, img], axis=-1)

    if img.shape[2] != 3:
        raise ValueError("Image must have 1 or 3 channels")

    # OpenCV expects BGR order when writing
    img_bgr = cv2.cvtColor(img, cv2.COLOR_RGB2BGR)

    compression = (
        cv2.IMWRITE_TIFF_COMPRESSION_SGILOG24
        if use_24bit
        else cv2.IMWRITE_TIFF_COMPRESSION_SGILOG
    )

    success = cv2.imwrite(
        path,
        img_bgr,
        [cv2.IMWRITE_TIFF_COMPRESSION, compression]
    )

    if not success:
        raise RuntimeError(f"Failed to write {path}")

    print(f"Saved: {path}  [{'SGILOG24' if use_24bit else 'SGILOG'}]")


def main():
    parser = argparse.ArgumentParser(description="Create SGI LogLuv TIFF")
    parser.add_argument("input", help="Input image (jpg/png/tif/exr/...)")
    parser.add_argument("output", help="Output .tif file")
    parser.add_argument("--gray", action="store_true",
                        help="Treat as grayscale (LogL)")
    parser.add_argument("--luv24", action="store_true",
                        help="Use 24-bit LogLuv instead of 32-bit")
    args = parser.parse_args()

    img = load_image(args.input, as_gray=args.gray)
    write_logluv(args.output, img, use_24bit=args.luv24)


if __name__ == "__main__":
    if len(sys.argv) == 1:
        print(__doc__)
        sys.exit(0)
    main()