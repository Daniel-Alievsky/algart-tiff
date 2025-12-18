@echo off
if %1.==. goto usage
if not exist "%~dp0target/algart-tiff-with-dependencies.jar" (
    echo Error: algart-tiff-with-dependencies.jar not found.
    echo Please run "mvn package" or "mvn install" command first.
    goto end
)
java -cp "%~dp0target/algart-tiff-with-dependencies.jar" %*
goto end

:usage
echo Usage:
echo   call-test.cmd FullClassName [args...]
echo Example:
echo   call-test.cmd net.algart.matrices.tiff.demo.io.TiffWriteSimpleDemo src/test/resources/demo/images/lenna.png lenna.tiff
echo Note: you MUST build the project first with help of Apache Maven: "mvn package" or "mvn install" command.

:end