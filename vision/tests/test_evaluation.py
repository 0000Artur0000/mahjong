import unittest

from dorahub_vision.evaluation import SceneEvaluation, evaluate


class EvaluationTest(unittest.TestCase):
    def test_reports_product_and_model_metrics_with_denominators(self) -> None:
        report = evaluate(
            [
                SceneEvaluation(
                    truth=("1m", "2m"),
                    predictions=("1m", "2m"),
                    false_detections=0,
                    group_results=(True,),
                    confidence=0.95,
                    corrected_tiles=0,
                    confirmation_ms=1000,
                    reshoot=False,
                ),
                SceneEvaluation(
                    truth=("2p", "3p"),
                    predictions=("3p", None),
                    false_detections=1,
                    group_results=(False,),
                    confidence=0.90,
                    corrected_tiles=2,
                    confirmation_ms=3000,
                    reshoot=True,
                ),
            ],
            acceptance_threshold=0.8,
        )

        self.assertEqual(report["counts"], {
            "scenes": 2,
            "acceptedScenes": 2,
            "rejectedScenes": 0,
        })
        rates = report["rates"]
        self.assertEqual(rates["falseAccept"], {
            "numerator": 1,
            "denominator": 2,
            "value": 0.5,
        })
        self.assertEqual(rates["detectionPrecision"]["value"], 0.75)
        self.assertEqual(rates["detectionRecall"]["value"], 0.75)
        self.assertAlmostEqual(rates["classificationAccuracy"]["value"], 2 / 3)
        self.assertEqual(rates["groupingAccuracy"]["value"], 0.5)
        self.assertEqual(report["ux"], {
            "meanCorrectedTiles": 1.0,
            "confirmationMsP50": 1000,
            "confirmationMsP90": 3000,
        })
        with self.assertRaises(ValueError):
            evaluate([], acceptance_threshold=1.1)


if __name__ == "__main__":
    unittest.main()
