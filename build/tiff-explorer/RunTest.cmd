@echo off
if "%~1"=="" goto usage
java -cp "%~dp0TiffExplorer.jar" %*
goto end

:usage
echo Usage:
echo     RunTest.cmd FullClassName [args...]
echo Example:
echo     RunTest.cmd net.algart.matrices.tiff.demo.io.TiffWriteSimpleDemo demo-images/lenna.jpeg lenna.tiff

:end