# ML layout baseline

Geometry/layout основан на [`haitaks/mahjong`](https://github.com/haitaks/mahjong)
commit `4955fb61e3d9dab7e6a3640ce2a63759ca0da27f` (`mahjong-layout`, author
`rosalvak`). Upstream `pyproject.toml` declares MIT; отдельного `LICENSE` файла
на этом commit нет.

В Dorahub перенесены идеи `TileBox → scale-aware DBSCAN → hand/discard/wall`
и параметры калибровки. Реализация адаптирована на Python stdlib и добавляет
валидацию normalized boxes и лимит detections. OCR, classifier, CLI и
visualization не копируются. CC BY 4.0 dataset импортирован отдельно в
`vision/datasets/haitaks-mahjong-layout-v2` как single-class detector baseline.
