#!/bin/sh
java -cp "$(dirname "$0")/TiffExplorer.jar" net.algart.matrices.tiff.app.MakeSvs "$@"