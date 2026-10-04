from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

import psycopg


FEATURE_NAMES = [
    "return_1d", "return_5d", "return_20d", "sma_20_ratio", "sma_50_ratio",
    "rsi_14", "volatility_20", "relative_volume_20", "market_return_20d",
    "relative_strength_market_20d",
]


def main() -> None:
    parser = argparse.ArgumentParser(description="Promote a reviewed candidate model to champion")
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--force", action="store_true", help="Permit promotion without beating the baseline Brier score")
    args = parser.parse_args()

    candidate = args.candidate.resolve()
    artifact = json.loads(candidate.read_text(encoding="utf-8"))
    if artifact.get("schemaVersion") != 1 or artifact.get("featureNames") != FEATURE_NAMES:
        raise RuntimeError("Candidate uses an incompatible artifact or feature schema")
    metrics = artifact.get("metrics", {})
    if not args.force and metrics.get("brierScore", 1.0) >= metrics.get("baselineUnconditionalBrier", 0.0):
        raise RuntimeError("Candidate did not beat the unconditional-frequency Brier baseline; review or use --force")

    root = Path(os.environ.get("MODEL_ARTIFACT_DIR", "/artifacts")).resolve()
    champion = root / "champion.json"
    temporary = root / "champion.tmp"
    root.mkdir(parents=True, exist_ok=True)
    temporary.write_text(json.dumps(artifact, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(champion)

    database_url = os.environ.get(
        "TRAINING_DATABASE_URL", "postgresql://analyzer:analyzer-local@postgres:5432/analyzer"
    )
    with psycopg.connect(database_url) as connection, connection.cursor() as cursor:
        cursor.execute("UPDATE analyzer.model_definition SET status = 'RETIRED' WHERE status = 'CHAMPION'")
        cursor.execute(
            "UPDATE analyzer.model_definition SET status = 'CHAMPION' WHERE model_version = %s",
            (artifact["modelVersion"],),
        )
        if cursor.rowcount != 1:
            raise RuntimeError("Candidate model is not registered in PostgreSQL")
        connection.commit()
    print(json.dumps({"status": "PROMOTED", "modelVersion": artifact["modelVersion"], "artifact": str(champion)}))


if __name__ == "__main__":
    main()
