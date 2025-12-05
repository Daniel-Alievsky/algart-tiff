#!/bin/sh
java -cp "$(dirname "$0")/TiffInfoViewer.jar" net.algart.matrices.tiff.executable.TiffInfo "$@"