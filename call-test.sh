#!/bin/sh
if [ $# -eq 0 ]; then
  echo "Usage:"
  echo "  $0 FullClassName [args...]"
  echo "Example:"
  echo "  $0 net.algart.matrices.tiff.demo.io.TiffWriteSimpleDemo src/test/resources/demo/images/lenna.png lenna.tiff"
  echo "Note: you MUST build the project first with help of Apache Maven: 'mvn package' or 'mvn install' command."
  exit 1
fi

java -cp "$(dirname "$0")/target/algart-tiff-with-dependencies.jar" "$@"