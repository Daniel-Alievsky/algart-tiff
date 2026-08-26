#!/bin/sh
if [ $# -eq 0 ]; then
  echo "Usage:"
  echo "    $0 FullClassName [args...]"
  echo "Example:"
  echo "    $0 net.algart.matrices.tiff.demo.io.TiffWriteSimpleDemo demo-images/lenna.jpeg lenna.tiff"
  exit 1
fi

java -cp "$(dirname "$0")/TiffExplorer.jar" "$@"