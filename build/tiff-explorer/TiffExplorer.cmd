@echo off
if %1.==-h. (
    java -jar "%~dp0TiffExplorer.jar" %*
) else (
    start /MIN cmd /C java -jar "%~dp0TiffExplorer.jar" %*
)
