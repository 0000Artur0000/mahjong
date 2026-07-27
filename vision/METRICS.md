# Vision evaluation metrics — schema 1

Единица отчёта — одна scene. Ground truth содержит tile instances и groups;
`predictions` уже сопоставлены с ground truth, а лишние detections считаются
отдельно. Scene считается автоматически принятой, если её scene confidence не
ниже зафиксированного в отчёте `acceptanceThreshold`.

`dorahub_vision.evaluation.evaluate` возвращает:

- automation coverage = accepted scenes / all scenes;
- false accept = accepted scenes с ошибкой tile detection/class/group / accepted scenes;
- exact hand = scenes без missing/extra/wrong tiles и grouping errors / all scenes;
- detection precision = matched detections / (matched + extra detections);
- detection recall = matched detections / ground-truth tile instances;
- classification accuracy = correct classes / matched detections;
- grouping accuracy = correct groups / evaluated groups;
- reshoot rate = reshoot scenes / all scenes;
- mean corrected tiles и nearest-rank p50/p90 времени от prediction до confirm.

Каждая rate хранит `numerator`, `denominator` и `value`; при нулевом denominator
`value` равен `null`. Candidate и production сравниваются только на одном
dataset/split, schema version и threshold. До calibration/rejection gate ML-19
этот threshold не включает автоматическое принятие в продукте.
