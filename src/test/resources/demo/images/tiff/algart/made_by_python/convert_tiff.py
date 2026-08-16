import sys
import imagecodecs
import tifffile


def list_supported_formats() -> None:
    """Prints all supported compressions, photometrics, and active imagecodecs."""
    print("=== Supported TIFF Compressions (tifffile) ===")
    for name in sorted(tifffile.COMPRESSION.__members__.keys()):
        tag_code = tifffile.COMPRESSION[name].value
        print(f"  - {name} (Tag: {tag_code})")

    print("\n=== Supported Photometric Interpretations (tifffile) ===")
    for name in sorted(tifffile.PHOTOMETRIC.__members__.keys()):
        tag_code = tifffile.PHOTOMETRIC[name].value
        print(f"  - {name} (Tag: {tag_code})")

    print("\n=== Active C Codecs (imagecodecs) ===")
    for codec in sorted(imagecodecs.codecs()):
        print(f"  - {codec}")


def convert_tiff(input_path: str, output_path: str, compression: str = "lzw") -> None:
    """Reads a TIFF file and writes it with the specified compression using tifffile."""
    data = tifffile.imread(input_path)

    comp_param = (
        None if str(compression).lower() in ("none", "0") else compression
    )
    tifffile.imwrite(output_path, data, compression=comp_param)
    print(f"Saved: {output_path} [Compression: {compression}]")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] in ("--list", "-l", "--list-formats"):
        list_supported_formats()
        sys.exit(0)

    if len(sys.argv) < 3:
        print(
            "Usage:\n"
            "  python convert_tiff.py <input.tif> <output.tif> [compression]\n"
            "  python convert_tiff.py --list"
        )
        sys.exit(1)

    in_file = sys.argv[1]
    out_file = sys.argv[2]
    comp = sys.argv[3] if len(sys.argv) > 3 else "lzw"

    convert_tiff(in_file, out_file, comp)