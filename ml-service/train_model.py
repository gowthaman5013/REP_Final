"""
AquaTrack - Random Forest Consumption Forecaster Training Suite
Usage:
    python train_model.py
"""

import sys
from pathlib import Path

# Add current directory to path
BASE_DIR = Path(__file__).resolve().parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

from training.train_consumption_forecaster import train_consumption_forecaster


def main():
    print("\n" + "=" * 75)
    print("   AQUATRACK CONSUMPTION FORECASTER TRAINING SUITE (RANDOM FOREST)")
    print("=" * 75)
    train_consumption_forecaster()
    print("\n" + "=" * 75)
    print("   TRAINING PIPELINE COMPLETED SUCCESSFULLY!")
    print("   Artifact persisted in 'models/random_forest_model.pkl'.")
    print("=" * 75 + "\n")


if __name__ == "__main__":
    main()
