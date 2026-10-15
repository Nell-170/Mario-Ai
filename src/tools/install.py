#!/usr/bin/env python3
import os
import sys
from pathlib import Path

# Redirects to the root install.py
root_dir = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(root_dir))
import install

if __name__ == "__main__":
    install.main()
