# Dataset source

Imported from [haitaks/mahjong](https://github.com/haitaks/mahjong) commit `4955fb61e3d9dab7e6a3640ce2a63759ca0da27f`.
The source is [Roboflow Mahjong_YOLO v2](https://universe.roboflow.com/test-wmo8i/mahjong_yolo/dataset/2446) and declares [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), workspace `test-wmo8i`, project `mahjong_yolo`, version `2`.

The upstream 86-class map is corrupted, so all valid boxes are remapped to class `0: tile`; eight polygon rows are converted to bounding boxes. Nine exact image+label duplicates are removed, leaving 209 images. This dataset is a detector/layout baseline, not a tile-classification or product validation set.
