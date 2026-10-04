from __future__ import annotations

import hashlib
import json
import math
import os
import uuid
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd
import psycopg
from psycopg.types.json import Jsonb
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    accuracy_score,
    balanced_accuracy_score,
    brier_score_loss,
    f1_score,
    log_loss,
    precision_score,
    recall_score,
)
from sklearn.preprocessing import StandardScaler


FEATURE_SET_VERSION = "phase2-v1"
FEATURE_NAMES = [
    "return_1d",
    "return_5d",
    "return_20d",
    "sma_20_ratio",
    "sma_50_ratio",
    "rsi_14",
    "volatility_20",
    "relative_volume_20",
    "market_return_20d",
    "relative_strength_market_20d",
]
TARGET_DEFINITION = "next completed U.S. session adjusted close > cutoff adjusted close"


def database_url() -> str:
    return os.environ.get(
        "TRAINING_DATABASE_URL",
        "postgresql://analyzer:analyzer-local@postgres:5432/analyzer",
    )


def load_prices(connection: psycopg.Connection) -> pd.DataFrame:
    sql = """
        SELECT b.instrument_id::text, i.current_symbol AS symbol, b.price_date,
               COALESCE(b.close_fully_adjusted, b.close_split_adjusted)::double precision AS adjusted_close,
               b.volume_split_adjusted::double precision AS volume
        FROM analyzer.daily_price_bar b
        JOIN analyzer.instrument i ON i.id = b.instrument_id
        WHERE (i.asset_type = 'EQUITY' OR i.current_symbol = 'SPY')
          AND COALESCE(b.close_fully_adjusted, b.close_split_adjusted) IS NOT NULL
        ORDER BY i.current_symbol, b.price_date
    """
    with connection.cursor() as cursor:
        cursor.execute(sql)
        frame = pd.DataFrame(cursor.fetchall(), columns=[column.name for column in cursor.description])
    if frame.empty:
        raise RuntimeError("No local EOD price history is available for training")
    frame["price_date"] = pd.to_datetime(frame["price_date"])
    return frame


def add_stock_features(group: pd.DataFrame) -> pd.DataFrame:
    group = group.sort_values("price_date").copy()
    close = group["adjusted_close"]
    volume = group["volume"]
    group["return_1d"] = close.pct_change(1, fill_method=None)
    group["return_5d"] = close.pct_change(5, fill_method=None)
    group["return_20d"] = close.pct_change(20, fill_method=None)
    group["sma_20_ratio"] = close / close.rolling(20).mean() - 1.0
    group["sma_50_ratio"] = close / close.rolling(50).mean() - 1.0

    delta = close.diff()
    gains = delta.clip(lower=0).rolling(14).mean()
    losses = (-delta.clip(upper=0)).rolling(14).mean()
    rs = gains / losses.replace(0, np.nan)
    rsi = (100.0 - 100.0 / (1.0 + rs)) / 100.0
    group["rsi_14"] = rsi.where(losses != 0, 1.0)

    log_return = np.log(close / close.shift(1))
    group["volatility_20"] = log_return.rolling(20).std(ddof=1) * math.sqrt(252.0)
    group["relative_volume_20"] = volume / volume.shift(1).rolling(20).mean()
    group["next_close"] = close.shift(-1)
    group["target_up"] = (group["next_close"] > close).astype(float)
    group.loc[group["next_close"].isna(), "target_up"] = np.nan
    return group


def build_dataset(prices: pd.DataFrame) -> pd.DataFrame:
    spy = prices.loc[prices["symbol"] == "SPY", ["price_date", "adjusted_close"]].copy()
    if spy.empty:
        raise RuntimeError("SPY history is required for market-relative features")
    spy = spy.sort_values("price_date")
    spy["market_return_20d"] = spy["adjusted_close"].pct_change(20, fill_method=None)
    spy = spy[["price_date", "market_return_20d"]]

    equities = prices.loc[prices["symbol"] != "SPY"]
    if equities.empty:
        raise RuntimeError("No equity EOD price history is available for training")
    featured = pd.concat(
        [add_stock_features(group) for _, group in equities.groupby("instrument_id", sort=False)],
        ignore_index=True,
    )
    featured = featured.merge(spy, on="price_date", how="left")
    featured["relative_strength_market_20d"] = (
        featured["return_20d"] - featured["market_return_20d"]
    )
    featured = featured.replace([np.inf, -np.inf], np.nan)
    return featured.dropna(subset=FEATURE_NAMES + ["target_up"]).sort_values("price_date")


def sigmoid(values: np.ndarray) -> np.ndarray:
    values = np.clip(values, -35.0, 35.0)
    return 1.0 / (1.0 + np.exp(-values))


def expanding_splits(dates: pd.Series, folds: int = 5) -> list[tuple[np.ndarray, np.ndarray]]:
    unique_dates = np.array(sorted(pd.unique(dates)))
    initial = max(252, int(len(unique_dates) * 0.40))
    if len(unique_dates) - initial < folds * 20:
        raise RuntimeError("Not enough distinct sessions for five walk-forward folds")
    test_blocks = np.array_split(unique_dates[initial:], folds)
    splits = []
    date_values = dates.to_numpy()
    for block in test_blocks:
        if len(block) == 0:
            continue
        train = np.flatnonzero(date_values < block[0])
        test = np.flatnonzero(np.isin(date_values, block))
        if len(train) and len(test):
            splits.append((train, test))
    return splits


def fit_calibrator(logits: np.ndarray, labels: np.ndarray) -> LogisticRegression | None:
    if len(logits) < 200 or len(np.unique(labels)) < 2:
        return None
    calibrator = LogisticRegression(C=1_000_000.0, solver="lbfgs")
    calibrator.fit(logits.reshape(-1, 1), labels)
    return calibrator


def evaluate_walk_forward(dataset: pd.DataFrame) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    features = dataset[FEATURE_NAMES].to_numpy(dtype=float)
    labels = dataset["target_up"].to_numpy(dtype=int)
    calibrated_predictions = np.full(len(dataset), np.nan)
    raw_logits = np.full(len(dataset), np.nan)
    baseline_probabilities = np.full(len(dataset), np.nan)

    prior_logits: list[float] = []
    prior_labels: list[int] = []
    for train_index, test_index in expanding_splits(dataset["price_date"]):
        scaler = StandardScaler().fit(features[train_index])
        classifier = LogisticRegression(C=1.0, max_iter=2_000, solver="lbfgs")
        classifier.fit(scaler.transform(features[train_index]), labels[train_index])
        logits = classifier.decision_function(scaler.transform(features[test_index]))
        calibrator = fit_calibrator(np.asarray(prior_logits), np.asarray(prior_labels))
        probabilities = (
            calibrator.predict_proba(logits.reshape(-1, 1))[:, 1]
            if calibrator is not None
            else sigmoid(logits)
        )
        raw_logits[test_index] = logits
        calibrated_predictions[test_index] = probabilities
        baseline_probabilities[test_index] = labels[train_index].mean()
        prior_logits.extend(logits.tolist())
        prior_labels.extend(labels[test_index].tolist())

    mask = np.isfinite(calibrated_predictions)
    if mask.sum() < 500:
        raise RuntimeError("Walk-forward evaluation produced fewer than 500 out-of-sample rows")
    return mask, raw_logits, calibrated_predictions, baseline_probabilities


def calibration_bands(labels: np.ndarray, probabilities: np.ndarray) -> list[dict[str, float | int]]:
    bands = []
    for lower in np.arange(0.0, 1.0, 0.1):
        upper = lower + 0.1
        mask = (probabilities >= lower) & (probabilities < upper if upper < 1.0 else probabilities <= upper)
        if not mask.any():
            continue
        bands.append(
            {
                "lower": round(float(lower), 2),
                "upper": round(float(upper), 2),
                "count": int(mask.sum()),
                "meanProbability": float(probabilities[mask].mean()),
                "observedUpRate": float(labels[mask].mean()),
            }
        )
    return bands


def metrics(labels: np.ndarray, probabilities: np.ndarray, baseline: np.ndarray, dataset: pd.DataFrame) -> dict:
    decisions = probabilities >= 0.5
    bands = calibration_bands(labels, probabilities)
    ece = sum(
        band["count"] / len(labels) * abs(band["meanProbability"] - band["observedUpRate"])
        for band in bands
    )
    previous_day = (dataset["return_1d"].to_numpy() > 0).astype(int)
    momentum = (dataset["return_20d"].to_numpy() > 0).astype(int)
    return {
        "accuracy": float(accuracy_score(labels, decisions)),
        "balancedAccuracy": float(balanced_accuracy_score(labels, decisions)),
        "precision": float(precision_score(labels, decisions, zero_division=0)),
        "recall": float(recall_score(labels, decisions, zero_division=0)),
        "f1": float(f1_score(labels, decisions, zero_division=0)),
        "brierScore": float(brier_score_loss(labels, probabilities)),
        "logLoss": float(log_loss(labels, np.clip(probabilities, 1e-6, 1 - 1e-6))),
        "expectedCalibrationError": float(ece),
        "coverage": 1.0,
        "outOfSampleRows": int(len(labels)),
        "baselineUnconditionalBrier": float(brier_score_loss(labels, baseline)),
        "baselineAlwaysUpAccuracy": float(accuracy_score(labels, np.ones(len(labels)))),
        "baselinePreviousDayAccuracy": float(accuracy_score(labels, previous_day)),
        "baselineMomentum20Accuracy": float(accuracy_score(labels, momentum)),
        "calibrationBands": bands,
    }


def train(dataset: pd.DataFrame) -> dict:
    minimum_rows = int(os.environ.get("TRAINING_MIN_ROWS", "2000"))
    minimum_symbols = int(os.environ.get("TRAINING_MIN_SYMBOLS", "3"))
    if len(dataset) < minimum_rows:
        raise RuntimeError(f"Need at least {minimum_rows} complete training rows; found {len(dataset)}")
    if dataset["symbol"].nunique() < minimum_symbols:
        raise RuntimeError(
            f"Need at least {minimum_symbols} locally synchronized symbols; found {dataset['symbol'].nunique()}"
        )

    mask, raw_logits, calibrated, baseline = evaluate_walk_forward(dataset)
    evaluation = dataset.loc[mask].copy()
    labels = evaluation["target_up"].to_numpy(dtype=int)
    probabilities = calibrated[mask]
    evaluation_metrics = metrics(labels, probabilities, baseline[mask], evaluation)

    all_oof_logits = raw_logits[mask]
    final_calibrator = fit_calibrator(all_oof_logits, labels)
    calibration_slope = float(final_calibrator.coef_[0, 0]) if final_calibrator is not None else 1.0
    calibration_intercept = float(final_calibrator.intercept_[0]) if final_calibrator is not None else 0.0

    features = dataset[FEATURE_NAMES].to_numpy(dtype=float)
    targets = dataset["target_up"].to_numpy(dtype=int)
    scaler = StandardScaler().fit(features)
    classifier = LogisticRegression(C=1.0, max_iter=2_000, solver="lbfgs")
    classifier.fit(scaler.transform(features), targets)

    now = datetime.now(timezone.utc)
    model_version = "logistic-" + now.strftime("%Y%m%dT%H%M%SZ")
    return {
        "schemaVersion": 1,
        "modelVersion": model_version,
        "featureSetVersion": FEATURE_SET_VERSION,
        "calibrationVersion": model_version + "-platt-v1",
        "algorithm": "STANDARDIZED_LOGISTIC_REGRESSION_WITH_PLATT_CALIBRATION",
        "trainedAt": now.isoformat().replace("+00:00", "Z"),
        "trainingStartDate": dataset["price_date"].min().date().isoformat(),
        "trainingEndDate": dataset["price_date"].max().date().isoformat(),
        "trainingRows": int(len(dataset)),
        "featureNames": FEATURE_NAMES,
        "means": dict(zip(FEATURE_NAMES, scaler.mean_.astype(float).tolist(), strict=True)),
        "scales": dict(zip(FEATURE_NAMES, scaler.scale_.astype(float).tolist(), strict=True)),
        "coefficients": dict(zip(FEATURE_NAMES, classifier.coef_[0].astype(float).tolist(), strict=True)),
        "intercept": float(classifier.intercept_[0]),
        "calibrationSlope": calibration_slope,
        "calibrationIntercept": calibration_intercept,
        "decisionThreshold": 0.5,
        "metrics": evaluation_metrics,
    }


def persist_candidate(connection: psycopg.Connection, artifact: dict, path: Path, sha256: str) -> None:
    model_id = uuid.uuid4()
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO analyzer.model_definition (
                id, model_version, feature_set_version, calibration_version, algorithm,
                target_definition, status, trained_at, training_start_date, training_end_date,
                training_rows, metadata)
            VALUES (%s, %s, %s, %s, %s, %s, 'CANDIDATE', %s, %s, %s, %s, %s)
            """,
            (
                model_id,
                artifact["modelVersion"],
                artifact["featureSetVersion"],
                artifact["calibrationVersion"],
                artifact["algorithm"],
                TARGET_DEFINITION,
                artifact["trainedAt"],
                artifact["trainingStartDate"],
                artifact["trainingEndDate"],
                artifact["trainingRows"],
                Jsonb(artifact["metrics"]),
            ),
        )
        cursor.execute(
            """
            INSERT INTO analyzer.model_artifact (
                id, model_definition_id, artifact_uri, sha256, artifact_format, schema_version)
            VALUES (%s, %s, %s, %s, 'JSON_LOGISTIC_REGRESSION', 1)
            """,
            (uuid.uuid4(), model_id, str(path), sha256),
        )
        cursor.execute(
            """
            INSERT INTO analyzer.model_evaluation (
                id, model_definition_id, evaluation_type, period_start, period_end,
                sample_count, metrics, baselines, calibration_bands)
            VALUES (%s, %s, 'WALK_FORWARD', %s, %s, %s, %s, %s, %s)
            """,
            (
                uuid.uuid4(),
                model_id,
                artifact["trainingStartDate"],
                artifact["trainingEndDate"],
                artifact["metrics"]["outOfSampleRows"],
                Jsonb(artifact["metrics"]),
                Jsonb({key: value for key, value in artifact["metrics"].items() if key.startswith("baseline")}),
                Jsonb(artifact["metrics"]["calibrationBands"]),
            ),
        )
    connection.commit()


def main() -> None:
    output_root = Path(os.environ.get("MODEL_ARTIFACT_DIR", "/artifacts")).resolve()
    candidate_dir = output_root / "candidates"
    candidate_dir.mkdir(parents=True, exist_ok=True)
    with psycopg.connect(database_url()) as connection:
        dataset = build_dataset(load_prices(connection))
        artifact = train(dataset)
        payload = json.dumps(artifact, indent=2, sort_keys=True) + "\n"
        path = candidate_dir / f"{artifact['modelVersion']}.json"
        temporary = path.with_suffix(".tmp")
        temporary.write_text(payload, encoding="utf-8")
        temporary.replace(path)
        digest = hashlib.sha256(payload.encode("utf-8")).hexdigest()
        persist_candidate(connection, artifact, path, digest)
    print(json.dumps({"status": "CANDIDATE_CREATED", "artifact": str(path), "sha256": digest}))


if __name__ == "__main__":
    main()
