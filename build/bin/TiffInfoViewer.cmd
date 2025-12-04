@echo off
if "%1"=="-h" (
    java -jar "%~dp0tiff-info.jar" %*
) else (
    start /MIN cmd /C java -jar "%~dp0tiff-info.jar" %*
)
