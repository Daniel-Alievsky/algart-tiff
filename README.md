# AlgART-TIFF

[AlgART](https://algart.net) library for TIFF support

## About

AlgART-TIFF is a Java library providing complete read/write support of TIFF files. 

It is based on [SCIFIO](https://github.com/scifio/scifio) library, 
but replaces its part, connected with TIFF support, with new powerful classes 
`TiffReader`/`TiffWriter`and a set of tool classes for TIFF processing.

Main AlgART-TIFF functions are available in [SciChains](http://scichains.com) visual constructor
via [scichains-map](https://github.com/scichains/scichains-maps) library.

## Key features

* You have full control over TIFF-file structure: ability to analyse, read or write separate IFDs
* You can correctly read/write most variants of TIFF formats, including BigTIFF
* You can read images with unusual bit depths like 1 bit/pixel (binary) or "strange" precision 24-bit/samples
* You can read or write any rectangular area of image, either as `byte[]` arrays or as suitable primitive-type array (`short[]` for 16 bits/sample, `float[]` for floating-point etc.)
* You also can read or write separate TIFF tiles (or strips) with/without decoding/encoding data
* Concept of `TiffMap`, describing the grid of rectangular tiles, simplifies work with large images
* You can create large TIFF step-by-step: you _start_ writing new image, then add data tile-per-tile or by a sequence of rectangular fragments, then _complete_ writing this image 
* If you add data by  rectangular areas, you may control, which tiles are already ready (filled by data), and to flush them to file; so you can create very large TIFF file without usage of large amoun of RAM

## Usage

This library is a Maven project, that can be installed by standard Maven tools or edited in IntelliJ IDEA.

You can use this library with the following Maven dependency in your POM:

```xml
 <dependencies>
    ...
    <dependency>
        <groupId>net.algart</groupId>
        <artifactId>algart-tiff</artifactId>
        <version>1.1.5</version>
    </dependency>
    ...
</dependencies>
```

Note that our library depends on SCIFIO, version 0.46.0. For this moment, SCIFIO modules are not deployed in Maven Central. Instead, SCIFIO uses its own repository [maven.scijava.org](https://maven.scijava.org/). However, for using AlgART-TIFF, it should not make any difference: in any case, all necessary JARs are downloaded automatically.   