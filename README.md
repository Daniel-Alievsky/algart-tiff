# AlgART-TIFF

[AlgART](https://algart.net) library for TIFF support

## About

AlgART-TIFF is a Java library providing complete read/write support for TIFF files. 

Main AlgART-TIFF functions are available in [SciChains](https://scichains.com) visual constructor
via [scichains-map](https://github.com/scichains/scichains-maps) library.

See also [AlgART-TIFF page](https://algart.net/java/AlgART-TIFF/) on AlgART site.

## Key features

* You have full control over TIFF-file structure: ability to analyze, read or write separate IFDs with help of `TiffReader` and `TiffWriter` classes.
* You can correctly read/write most variants of TIFF formats, including BigTIFF
* You can read images with unusual bit depths like 1 bit/pixel (binary) or "strange" precision 24-bit/samples
* You can read or write any rectangular area of image, either as `byte[]` arrays or as suitable primitive-type array (`short[]` for 16 bits/sample, `float[]` for floating-point etc.)
* You also can read or write separate TIFF tiles (or strips) with/without decoding/encoding data
* Concept of `TiffMap`, describing the grid of rectangular tiles, simplifies work with large images
* You can create a large TIFF step-by-step: you _start_ writing a new image, then add data tile-per-tile or by a sequence of rectangular fragments, then _complete_ writing this image 
* If you add data by rectangular areas, you may control which tiles are already ready (filled with data), and flush them to file; so you can create a huge TIFF file without using a large amount of RAM
  
This library is compatible with the [SCIFIO](https://github.com/scifio/scifio). This library is independent on SCIFIO, but recognizes the situation when SCIFIO (`io.scif` package) is available in the classpath. In this case, the `TiffReader` and `TiffWriter` classes also "understand" all TIFF compressions supported by `io.scif.formats.tiff.TiffCompression` class, in addition to the built-in compression formats. 
This is usually not important because this library supports almost all formats, but it may help to ensure compatibility when migrating from SCIFIO to AlgART-TIFF. 


## Usage

This library is a Maven project that can be installed by standard Maven tools or edited in IntelliJ IDEA.

You can use this library with the following Maven dependency in your POM:

```xml
 <dependencies>
    ...
    <dependency>
        <groupId>net.algart</groupId>
        <artifactId>algart-tiff</artifactId>
        <version>1.5.0</version>
    </dependency>
    ...
</dependencies>
```

Note that this library depends on the maven modules `org.scijava:scijava-common` and `io.scif:scifio-jai-imageio`, included in the SCIFIO framework. These modules are not currently deployed in Maven Central. Instead, SCIFIO stores them in its own repository [maven.scijava.org](https://maven.scijava.org/). However, this should not make any difference when using AlgART-TIFF: in any case, all the necessary JARs are downloaded automatically.

This library does not depend on SCIFIO itself (`io.scif:scifio`), but can be used together with it. 
