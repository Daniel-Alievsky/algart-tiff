/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2025 Daniel Alievsky, AlgART Laboratory (http://algart.net)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.algart.matrices.tiff.tags;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

class TagFriendlyNames {
    private TagFriendlyNames() {
    }

    static final Map<Integer, String> TAG_NAMES = buildTagNames();

    private static Map<Integer, String> buildTagNames() {
        final Map<Integer, String> result = new LinkedHashMap<>();
        for (String javaConstant : NamedConstantsHolder.SOURCE_CODE_WITH_IFD_TAG_NAMES) {
            addTagName(result, javaConstant);
        }
        return result;
    }

    private static void addTagName(Map<Integer, String> map, String line) {
        int p = line.indexOf("//");
        if (p != -1) {
            line = line.substring(0, p);
        }
        line = line.trim();
        if (line.isEmpty()) {
            return;
        }
        p = line.indexOf("=");
        assert p != -1;
        String name = line.substring(0, p);
        String id = line.substring(p + 1);
        name = NamedConstantsHolder.BEGIN_PATTERN.matcher(name).replaceAll("").trim();
        name = NamedConstantsHolder.NAME_UNDERLINE_PATTERN.matcher(name).replaceAll("_");
        if (!NamedConstantsHolder.NAME_REQUIREMENT_PATTERN.matcher(name).matches()) {
            throw new AssertionError("Not allowed name: " + name);
        }
        id = NamedConstantsHolder.END_PATTERN.matcher(id).replaceAll("").trim();
        map.put(Integer.parseInt(id), name);
//        System.out.println(name + ":" + id);
    }

    private static class NamedConstantsHolder {
        // The following matchers allow processing also lines like
        // "public static final int make = 271;"
        // and also replaces " " -> "_"
        private static final Pattern BEGIN_PATTERN = Pattern.compile("^[\\w\\s]*\\sint\\s");
        private static final Pattern END_PATTERN = Pattern.compile("\\s*;\\s*$");
        private static final Pattern NAME_UNDERLINE_PATTERN = Pattern.compile("[\\s./]+");
        private static final Pattern NAME_REQUIREMENT_PATTERN = Pattern.compile("\\w+");
        // Below is the string constant, containing the tag names and the corresponding decimal values
        private static final String[] SOURCE_CODE_WITH_IFD_TAG_NAMES = """
                NewSubfileType = 254;
                SubfileType = 255
                ImageWidth = 256
                ImageLength = 257
                BitsPerSample = 258
                Compression = 259
                PhotometricInterpretation = 262
                Thresholding = 263
                CellWidth = 264
                CellLength = 265
                FillOrder = 266
                DocumentName = 269
                ImageDescription = 270
                Make = 271
                Model = 272
                StripOffsets = 273
                Orientation = 274
                SamplesPerPixel = 277
                RowsPerStrip = 278
                StripByteCounts = 279
                MinSampleValue = 280
                MaxSampleValue = 281
                XResolution = 282
                YResolution = 283
                PlanarConfiguration = 284
                PageName = 285
                XPosition = 286
                YPosition = 287
                FreeOffsets = 288
                FreeByteCounts = 289
                GrayResponseUnit = 290
                GrayResponseCurve = 291
                T4Options = 292
                T6Options = 293
                ResolutionUnit = 296
                PageNumber = 297
                TransferFunction = 301
                Software = 305
                DateTime = 306
                Artist = 315
                HostComputer = 316
                Predictor = 317
                WhitePoint = 318
                PrimaryChromaticities = 319
                ColorMap = 320
                HalftoneHints = 321
                TileWidth = 322
                TileLength = 323
                TileOffsets = 324
                TileByteCounts = 325
                BadFaxLines = 326
                CleanFaxData = 327
                ConsecutiveBadFaxLines = 328
                SubIFDs = 330
                InkSet = 332
                InkNames = 333
                NumberOfInks = 334
                DotRange = 336
                TargetPrinter = 337
                ExtraSamples = 338
                SampleFormat = 339
                SMinSampleValue = 340
                SMaxSampleValue = 341
                TransferRange = 342
                ClipPath = 343
                XClipPathUnits = 344
                YClipPathUnits = 345
                Indexed = 346
                JPEGTables = 347
                OPIProxy = 351
                GlobalParametersIFD = 400
                ProfileType = 401
                FaxProfile = 402
                CodingMethods = 403
                VersionYear = 404
                ModeNumber = 405
                Decode = 433
                DefaultImageColor = 434
                JPEGProc = 512
                JPEGInterchangeFormat = 513
                JPEGInterchangeFormatLength = 514
                JPEGRestartInterval = 515
                JPEGLosslessPredictors = 517
                JPEGPointTransforms = 518
                JPEGQTables = 519
                JPEGDCTables = 520
                JPEGACTables = 521
                YCbCrCoefficients = 529
                YCbCrSubSampling = 530
                YCbCrPositioning = 531
                ReferenceBlackWhite = 532
                StripRowCounts = 559
                XMP = 700
                Image.Rating = 18246
                Image.RatingPercent = 18249
                ImageID = 32781
                Wang Annotation = 32932
                CFARepeatPatternDim = 33421
                CFAPattern = 33422
                BatteryLevel = 33423
                Copyright = 33432
                ExposureTime = 33434
                FNumber = 33437
                MD FileTag = 33445
                MD ScalePixel = 33446
                MD ColorTable = 33447
                MD LabName = 33448
                MD SampleInfo = 33449
                MD PrepDate = 33450
                MD PrepTime = 33451
                MD FileUnits = 33452
                ModelPixelScaleTag = 33550
                IPTC/NAA = 33723
                INGR Packet Data Tag = 33918
                INGR Flag Registers = 33919
                IrasB Transformation Matrix = 33920
                ModelTiepointTag = 33922
                Site = 34016
                ColorSequence = 34017
                IT8Header = 34018
                RasterPadding = 34019
                BitsPerRunLength = 34020
                BitsPerExtendedRunLength = 34021
                ColorTable = 34022
                ImageColorIndicator = 34023
                BackgroundColorIndicator = 34024
                ImageColorValue = 34025
                BackgroundColorValue = 34026
                PixelIntensityRange = 34027
                TransparencyIndicator = 34028
                ColorCharacterization = 34029
                HCUsage = 34030
                TrapIndicator = 34031
                CMYKEquivalent = 34032
                Reserved = 34033
                Reserved = 34034
                Reserved = 34035
                ModelTransformationTag = 34264
                Photoshop = 34377
                Exif IFD = 34665
                InterColorProfile = 34675
                ImageLayer = 34732
                GeoKeyDirectoryTag = 34735
                GeoDoubleParamsTag = 34736
                GeoAsciiParamsTag = 34737
                ExposureProgram = 34850
                SpectralSensitivity = 34852
                GPSTag = 34853
                ISOSpeedRatings = 34855
                OECF = 34856
                Interlace = 34857
                TimeZoneOffset = 34858
                SelfTimeMode = 34859
                SensitivityType = 34864
                StandardOutputSensitivity = 34865
                RecommendedExposureIndex = 34866
                ISOSpeed = 34867
                ISOSpeedLatitudeyyy = 34868
                ISOSpeedLatitudezzz = 34869
                HylaFAX FaxRecvParams = 34908
                HylaFAX FaxSubAddress = 34909
                HylaFAX FaxRecvTime = 34910
                ExifVersion = 36864
                DateTimeOriginal = 36867
                DateTimeDigitized = 36868
                ComponentsConfiguration = 37121
                CompressedBitsPerPixel = 37122
                ShutterSpeedValue = 37377
                ApertureValue = 37378
                BrightnessValue = 37379
                ExposureBiasValue = 37380
                MaxApertureValue = 37381
                SubjectDistance = 37382
                MeteringMode = 37383
                LightSource = 37384
                Flash = 37385
                FocalLength = 37386
                FlashEnergy = 37387
                SpatialFrequencyResponse = 37388
                Noise = 37389
                FocalPlaneXResolution = 37390
                FocalPlaneYResolution = 37391
                FocalPlaneResolutionUnit = 37392
                ImageNumber = 37393
                SecurityClassification = 37394
                ImageHistory = 37395
                SubjectLocation = 37396
                ExposureIndex = 37397
                TIFF/EPStandardID = 37398
                SensingMethod = 37399
                MakerNote = 37500
                UserComment = 37510
                SubsecTime = 37520
                SubsecTimeOriginal = 37521
                SubsecTimeDigitized = 37522
                ImageSourceData = 37724
                XPTitle = 40091
                XPComment = 40092
                XPAuthor = 40093
                XPKeywords = 40094
                XPSubject = 40095
                FlashpixVersion = 40960
                ColorSpace = 40961
                PixelXDimension = 40962
                PixelYDimension = 40963
                RelatedSoundFile = 40964
                Interoperability IFD = 40965
                FlashEnergy = 41483
                SpatialFrequencyResponse = 41484
                FocalPlaneXResolution = 41486
                FocalPlaneYResolution = 41487
                FocalPlaneResolutionUnit = 41488
                SubjectLocation = 41492
                ExposureIndex = 41493
                SensingMethod = 41495
                FileSource = 41728
                SceneType = 41729
                CFAPattern = 41730
                CustomRendered = 41985
                ExposureMode = 41986
                WhiteBalance = 41987
                DigitalZoomRatio = 41988
                FocalLengthIn35mmFilm = 41989
                SceneCaptureType = 41990
                GainControl = 41991
                Contrast = 41992
                Saturation = 41993
                Sharpness = 41994
                DeviceSettingDescription = 41995
                SubjectDistanceRange = 41996
                ImageUniqueID = 42016
                CameraOwnerName = 42032
                BodySerialNumber = 42033
                LensSpecification = 42034
                LensMake = 42035
                LensModel = 42036
                LensSerialNumber = 42037
                GDAL_METADATA = 42112
                GDAL_NODATA = 42113
                PixelFormat = 48129
                Transformation = 48130
                Uncompressed = 48131
                ImageType = 48132
                ImageWidth = 48256
                ImageHeight = 48257
                WidthResolution = 48258
                HeightResolution = 48259
                ImageOffset = 48320
                ImageByteCount = 48321
                AlphaOffset = 48322
                AlphaByteCount = 48323
                ImageDataDiscard = 48324
                AlphaDataDiscard = 48325
                ImageType = 48132
                Oce Scanjob Description = 50215
                Oce Application Selector = 50216
                Oce Identification Number = 50217
                Oce ImageLogic Characteristics = 50218
                PrintImageMatching = 50341
                DNGVersion = 50706
                DNGBackwardVersion = 50707
                UniqueCameraModel = 50708
                LocalizedCameraModel = 50709
                CFAPlaneColor = 50710
                CFALayout = 50711
                LinearizationTable = 50712
                BlackLevelRepeatDim = 50713
                BlackLevel = 50714
                BlackLevelDeltaH = 50715
                BlackLevelDeltaV = 50716
                WhiteLevel = 50717
                DefaultScale = 50718
                DefaultCropOrigin = 50719
                DefaultCropSize = 50720
                ColorMatrix1 = 50721
                ColorMatrix2 = 50722
                CameraCalibration1 = 50723
                CameraCalibration2 = 50724
                ReductionMatrix1 = 50725
                ReductionMatrix2 = 50726
                AnalogBalance = 50727
                AsShotNeutral = 50728
                AsShotWhiteXY = 50729
                BaselineExposure = 50730
                BaselineNoise = 50731
                BaselineSharpness = 50732
                BayerGreenSplit = 50733
                LinearResponseLimit = 50734
                CameraSerialNumber = 50735
                LensInfo = 50736
                ChromaBlurRadius = 50737
                AntiAliasStrength = 50738
                ShadowScale = 50739
                DNGPrivateData = 50740
                MakerNoteSafety = 50741
                CalibrationIlluminant1 = 50778
                CalibrationIlluminant2 = 50779
                BestQualityScale = 50780
                RawDataUniqueID = 50781
                Alias Layer Metadata = 50784
                OriginalRawFileName = 50827
                OriginalRawFileData = 50828
                ActiveArea = 50829
                MaskedAreas = 50830
                AsShotICCProfile = 50831
                AsShotPreProfileMatrix = 50832
                CurrentICCProfile = 50833
                CurrentPreProfileMatrix = 50834
                ColorimetricReference = 50879
                CameraCalibrationSignature = 50931
                ProfileCalibrationSignature = 50932
                ExtraCameraProfiles = 50933
                AsShotProfileName = 50934
                NoiseReductionApplied = 50935
                ProfileName = 50936
                ProfileHueSatMapDims = 50937
                ProfileHueSatMapData1 = 50938
                ProfileHueSatMapData2 = 50939
                ProfileToneCurve = 50940
                ProfileEmbedPolicy = 50941
                ProfileCopyright = 50942
                ForwardMatrix1 = 50964
                ForwardMatrix2 = 50965
                PreviewApplicationName = 50966
                PreviewApplicationVersion = 50967
                PreviewSettingsName = 50968
                PreviewSettingsDigest = 50969
                PreviewColorSpace = 50970
                PreviewDateTime = 50971
                RawImageDigest = 50972
                OriginalRawFileDigest = 50973
                SubTileBlockSize = 50974
                RowInterleaveFactor = 50975
                ProfileLookTableDims = 50981
                ProfileLookTableData = 50982
                OpcodeList1 = 51008
                OpcodeList2 = 51009
                OpcodeList3 = 51022
                NoiseProfile = 51041
                OriginalDefaultFinalSize = 51089
                OriginalBestQualityFinalSize = 51090
                OriginalDefaultCropSize = 51091
                ProfileHueSatMapEncoding = 51107
                ProfileLookTableEncoding = 51108
                BaselineExposureOffset = 51109
                DefaultBlackRender = 51110
                NewRawImageDigest = 51111
                RawToPreviewGain = 51112
                DefaultUserCrop = 51125
                Matteing = 32995
                DataType = 32996
                ImageDepth = 32997
                TileDepth = 32998
                StoNits = 37439
                """
                .split("\\n");
    }
}
