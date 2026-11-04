"""
AquaTrack - Synthetic Dataset Generator for Consumption Forecasting
Usage:
    python generate_dataset.py
"""

import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

from training.generate_consumption_data import generate_consumption_dataset

# Keep backward-compatible function name
generate_synthetic_water_dataset = generate_consumption_dataset


def main():
    print("\n" + "=" * 75)
    print("   AQUATRACK BENCHMARK DATASET GENERATOR (CONSUMPTION FORECASTING)")
    print("=" * 75)
    generate_consumption_dataset()
    print("\n" + "=" * 75)
    print("   DATASET GENERATION COMPLETED SUCCESSFULLY!")
    print("   Saved to: data/consumption_forecasting/synthetic_water_usage.csv")
    print("=" * 75 + "\n")


if __name__ == "__main__":
    main()
