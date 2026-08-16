import sys
import tifffile


def convert_to_tiff(
    input_path: str, output_path: str, compression: str = "lzw"
):
    """Reads image via tifffile (imagecodecs) and saves it with specified compression."""
    # tifffile.imread uses imagecodecs directly, supporting Zstd, WebP, etc.
    data = tifffile.imread(input_path)

    comp_param = (
        None if str(compression).lower() in ("none", "0") else compression
    )

    tifffile.imwrite(output_path, data, compression=comp_param)
    print(f"Saved: {output_path} [Compression: {compression}]")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(
            "Usage: python convert_tiff.py <input> <output.tif> [compression]"
        )
        sys.exit(1)

    in_file = sys.argv[1]
    out_file = sys.argv[2]
    comp = sys.argv[3] if len(sys.argv) > 3 else "lzw"

    convert_to_tiff(in_file, out_file, comp)