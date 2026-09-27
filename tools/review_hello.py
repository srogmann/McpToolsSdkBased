#!/usr/bin/env python3
"""First end-to-end test of the shared approval workflow."""
import sys


def main():
    print("Hello, review workflow!")
    print("argv: {}".format(sys.argv[1:]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
