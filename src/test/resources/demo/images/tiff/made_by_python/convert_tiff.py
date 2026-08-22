import io
import sys
import numpy as np
import imagecodecs
import tifffile


def check_compression_support(compression_val) -> tuple[bool, str | None]:
    dummy_data = np.zeros((8, 8, 3), dtype=np.uint8)
    buf = io.BytesIO()
    try:
        tifffile.imwrite(buf, dummy_data, compression=compression_val)
        buf.seek(0)
        _ = tifffile.imread(buf)
        return True, None
    except Exception as e:
        msg = str(e).strip().split("\n")[0]
        return False, msg if msg else e.__class__.__name__


def list_supported_formats(test: bool = False):
    compressions = sorted(tifffile.COMPRESSION, key=lambda m: m.value)
    print(f"=== {len(compressions)} TIFF Compressions ===")

    for member in compressions:
        status_str = ""
        if test:
            is_ok, err_msg = check_compression_support(member.value)
            if is_ok:
                status_str = " | Supported: YES"
            else:
                status_str = f" | Supported: NO ({err_msg})"

        print(f"  - {member.name:<25} (Tag: {member.value:<5}){status_str}")

    photometrics = sorted(tifffile.PHOTOMETRIC, key=lambda m: m.value)
    print(f"\n=== {len(photometrics)} Photometric Interpretations ===")
    for member in photometrics:
        print(f"  - {member.name:<25} (Tag: {member.value})")

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

    comp_upper = str(compression).upper()
    is_3channel = data.ndim == 3 and data.shape[2] == 3
    kwargs = {}
    if comp_param is not None:
        kwargs["compression"] = comp_param
    if bitspersample is not None:
        kwargs["bitspersample"] = bitspersample
    if is_3channel and comp_upper == "JPEG_LOSSY":
        kwargs["photometric"] = "ycbcr"

    tifffile.imwrite(output_path, data, **kwargs)

    bps_info = f", BitsPerSample: {bitspersample}" if bitspersample else ""
    print(f"Saved: {output_path} [Compression: {compression}{bps_info}]")


if __name__ == "__main__":
    args = [arg.lower() for arg in sys.argv[1:]]

    # Режим вывода списка (с тестом или без)
    if any(flag in args for flag in ("--list", "-l", "--test", "-t")):
        run_test = any(flag in args for flag in ("--test", "-t"))
        list_supported_formats(test=run_test)
        sys.exit(0)

    if len(sys.argv) < 3:
        print(
            "Usage:\n"
            "  python convert_tiff.py <input.tif> <output.tif> [compression] [bits_per_sample]\n"
            "  python convert_tiff.py --list [--test]\n\n"
            "Examples:\n"
            "  python convert_tiff.py --list\n"
            "  python convert_tiff.py --list --test\n"
            "  python convert_tiff.py lenna.tif lenna-zstd.tif zstd\n"
        )
        sys.exit(1)

    in_file = sys.argv[1]
    out_file = sys.argv[2]
    comp = sys.argv[3] if len(sys.argv) > 3 else "deflate"
    bps = int(sys.argv[4]) if len(sys.argv) > 4 else None

    convert_tiff(in_file, out_file, comp, bps)