import os
import unittest

import numpy as np
import pandas as pd

import train


class TrainerTests(unittest.TestCase):
    def test_builds_dataset_and_trains_versioned_artifact(self):
        dates = pd.bdate_range("2023-01-03", periods=650)
        frames = []
        for offset, symbol in enumerate(["SPY", "AAPL", "MSFT"]):
            index = np.arange(len(dates), dtype=float)
            close = 100.0 + offset * 20.0 + index * (0.04 + offset * 0.005) + np.sin(index / 7.0)
            frames.append(
                pd.DataFrame(
                    {
                        "instrument_id": symbol.lower(),
                        "symbol": symbol,
                        "price_date": dates,
                        "adjusted_close": close,
                        "volume": 1_000_000.0 + index * 100.0 + offset * 10_000.0,
                    }
                )
            )
        dataset = train.build_dataset(pd.concat(frames, ignore_index=True))
        previous_rows = os.environ.get("TRAINING_MIN_ROWS")
        previous_symbols = os.environ.get("TRAINING_MIN_SYMBOLS")
        os.environ["TRAINING_MIN_ROWS"] = "500"
        os.environ["TRAINING_MIN_SYMBOLS"] = "2"
        try:
            artifact = train.train(dataset)
        finally:
            if previous_rows is None:
                os.environ.pop("TRAINING_MIN_ROWS", None)
            else:
                os.environ["TRAINING_MIN_ROWS"] = previous_rows
            if previous_symbols is None:
                os.environ.pop("TRAINING_MIN_SYMBOLS", None)
            else:
                os.environ["TRAINING_MIN_SYMBOLS"] = previous_symbols

        self.assertEqual(1, artifact["schemaVersion"])
        self.assertEqual(train.FEATURE_NAMES, artifact["featureNames"])
        self.assertEqual("phase2-v1", artifact["featureSetVersion"])
        self.assertNotIn("SPY", dataset["symbol"].unique())
        self.assertGreater(artifact["metrics"]["outOfSampleRows"], 500)
        self.assertGreaterEqual(artifact["calibrationSlope"], 0.0)


if __name__ == "__main__":
    unittest.main()
