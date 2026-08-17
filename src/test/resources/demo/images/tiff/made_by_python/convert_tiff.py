import sys
import imagecodecs
import tifffile


def list_supported_formats():
    compressions = sorted(tifffile.COMPRESSION, key=lambda m: m.value)
    print(f"=== {len(compressions)} TIFF Compressions ===")
    for member in compressions:
        print(f"  - {member.name} (Tag: {member.value})")

    photometrics = sorted(tifffile.PHOTOMETRIC, key=lambda m: m.value)
    print(f"\n=== {len(photometrics)} Photometric Interpretations ===")
    for member in photometrics:
        print(f"  - {member.name} (Tag: {member.value})")

    print("\n=== imagecodecs ===")
    print(f"  - Version: {imagecodecs.__version__}")


def convert_tiff(
        input_path: str,
        output_path: str,
        compression: str = "deflate",
        bitspersample: int | None = None,
) -> None:
    """Reads a TIFF file and writes it with the specified compression and bit depth."""
    data = tifffile.imread(input_path)

    comp_param = (
        None if str(compression).lower() in ("none", "0") else compression
    )

    kwargs = {}
    if comp_param is not None:
        kwargs["compression"] = comp_param
    if bitspersample is not None:
        kwargs["bitspersample"] = bitspersample

    tifffile.imwrite(output_path, data, **kwargs)

    bps_info = f", BitsPerSample: {bitspersample}" if bitspersample else ""
    print(f"Saved: {output_path} [Compression: {compression}{bps_info}]")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] in ("--list", "-l", "--list-formats"):
        list_supported_formats()
        sys.exit(0)

    if len(sys.argv) < 3:
        print(
            "Usage:\n"
            "  python convert_tiff.py <input.tif> <output.tif> [compression] [bits_per_sample]\n"
            "  python convert_tiff.py --list\n\n"
            "Examples:\n"
            "  python convert_tiff.py lenna.tif lenna-12bit.tif zstd\n"
            "  python convert_tiff.py lenna.tif lenna-4bit.tif none 4\n"
            "Note the bits_per_sample should be used with compression \"none\""
        )
        sys.exit(1)

    in_file = sys.argv[1]
    out_file = sys.argv[2]
    comp = sys.argv[3] if len(sys.argv) > 3 else "deflate"
    bps = int(sys.argv[4]) if len(sys.argv) > 4 else None

    convert_tiff(in_file, out_file, comp, bps)