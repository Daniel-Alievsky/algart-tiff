Possible way to create test images:

magick.exe test.tiff -endian msb -compress none -type TrueColor -depth 4 test-rgb-4.tif 
magick.exe test.tiff -define tiff:endian=lsb -define tiff:fill-order=lsb -compress zip -type TrueColor -depth 32 test-rgb-32.tif 