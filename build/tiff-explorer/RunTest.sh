#!/bin/sh
if [ $# -eq 0 ]; then
  echo "Usage:"
  echo "  $0 FullClassName [args...]"
  echo "Example:"
  echo "  $0 net.algart.matrices.tiff.demo.io.TiffWriteSimpleDemo ../../../src/test/resources/demo/images/lenna.png lenna.tiff"
  exit 1
fi

java -cp "$(dirname "$0")/TiffExplorer.jar" "$@"