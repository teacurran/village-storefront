#!/usr/bin/env python3
"""
Manifest Anchor Validation Script

Validates that all anchors referenced in plan_manifest.json and architecture_manifest.json
actually exist in the corresponding markdown files.

Usage:
    python anchor_validation.py
    python anchor_validation.py --manifest-dir ../../.codemachine/artifacts
    python anchor_validation.py --verbose
    python anchor_validation.py --json-output manifest-results.json
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

try:
    from colorama import Fore, Style, init
    init(autoreset=True)
    COLORS_AVAILABLE = True
except ImportError:
    COLORS_AVAILABLE = False

    class DummyColor:
        def __getattr__(self, name):
            return ''

    Fore = DummyColor()
    Style = DummyColor()


class AnchorValidator:
    """Validates manifest anchors against markdown files"""

    def __init__(
        self,
        manifest_dir: Path,
        verbose: bool = False,
        json_output: Optional[Path] = None,
    ):
        self.manifest_dir = manifest_dir
        self.verbose = verbose
        self.json_output = json_output
        self.errors: List[str] = []
        self.warnings: List[str] = []
        self.summary: Dict[str, object] = {}

    def log(self, message: str, level: str = 'info'):
        """Log message with color coding"""
        if level == 'error':
            print(f"{Fore.RED}✗ {message}{Style.RESET_ALL}")
        elif level == 'warning':
            print(f"{Fore.YELLOW}⚠ {message}{Style.RESET_ALL}")
        elif level == 'success':
            print(f"{Fore.GREEN}✓ {message}{Style.RESET_ALL}")
        elif level == 'info' and self.verbose:
            print(f"{Fore.CYAN}ℹ {message}{Style.RESET_ALL}")

    def load_manifest(self, manifest_file: str) -> Dict:
        """Load and parse a manifest JSON file"""
        manifest_path = self.manifest_dir / manifest_file

        if not manifest_path.exists():
            error_msg = f"Manifest file not found: {manifest_path}"
            self.errors.append(error_msg)
            self.log(error_msg, 'error')
            return {}

        try:
            with open(manifest_path, 'r', encoding='utf-8') as f:
                return json.load(f)
        except json.JSONDecodeError as e:
            error_msg = f"Invalid JSON in {manifest_file}: {e}"
            self.errors.append(error_msg)
            self.log(error_msg, 'error')
            return {}

    def extract_anchors_from_markdown(self, md_file: Path) -> Set[str]:
        """Extract all anchor tags from a markdown file"""
        if not md_file.exists():
            return set()

        anchors = set()
        anchor_pattern = re.compile(r'<!--\s*anchor:\s*([a-zA-Z0-9\-_]+)\s*-->')

        try:
            with open(md_file, 'r', encoding='utf-8') as f:
                for line in f:
                    matches = anchor_pattern.findall(line)
                    anchors.update(matches)
        except Exception as e:
            warning_msg = f"Error reading {md_file}: {e}"
            self.warnings.append(warning_msg)
            self.log(warning_msg, 'warning')

        return anchors

    def validate_plan_manifest(self) -> Tuple[int, int]:
        """Validate plan manifest anchors"""
        self.log("Validating plan manifest...", 'info')

        manifest = self.load_manifest('plan/plan_manifest.json')
        if not manifest or 'locations' not in manifest:
            return (0, 0)

        plan_dir = self.manifest_dir / 'plan'
        total_anchors = 0
        missing_anchors = 0

        for location in manifest['locations']:
            key = location.get('key')
            file = location.get('file')
            start_anchor = location.get('start_anchor', '')

            if not key or not file:
                warning_msg = f"Invalid location entry: {location}"
                self.warnings.append(warning_msg)
                self.log(warning_msg, 'warning')
                continue

            total_anchors += 1

            # Extract anchor from start_anchor string
            anchor_match = re.search(r'anchor:\s*([a-zA-Z0-9\-_]+)', start_anchor)
            if not anchor_match:
                warning_msg = f"Could not parse anchor from: {start_anchor}"
                self.warnings.append(warning_msg)
                self.log(warning_msg, 'warning')
                continue

            anchor = anchor_match.group(1)

            # Check if anchor exists in file
            md_file = plan_dir / file
            file_anchors = self.extract_anchors_from_markdown(md_file)

            if anchor not in file_anchors:
                missing_anchors += 1
                error_msg = f"Missing anchor '{anchor}' in {file} (key: {key})"
                self.errors.append(error_msg)
                self.log(error_msg, 'error')
            else:
                self.log(f"Found anchor '{anchor}' in {file}", 'info')

        return (total_anchors, missing_anchors)

    def validate_architecture_manifest(self) -> Tuple[int, int]:
        """Validate architecture manifest anchors"""
        self.log("Validating architecture manifest...", 'info')

        manifest = self.load_manifest('architecture/architecture_manifest.json')
        if not manifest or 'files' not in manifest:
            return (0, 0)

        arch_dir = self.manifest_dir / 'architecture'
        total_anchors = 0
        missing_anchors = 0

        for file_name, locations in manifest['files'].items():
            md_file = arch_dir / file_name
            file_anchors = self.extract_anchors_from_markdown(md_file)

            for location in locations:
                key = location.get('key')
                start_anchor = location.get('start_anchor', '')

                if not key:
                    warning_msg = f"Invalid location entry in {file_name}: {location}"
                    self.warnings.append(warning_msg)
                    self.log(warning_msg, 'warning')
                    continue

                total_anchors += 1

                # Extract anchor from start_anchor string
                anchor_match = re.search(r'anchor:\s*([a-zA-Z0-9\-_]+)', start_anchor)
                if not anchor_match:
                    warning_msg = f"Could not parse anchor from: {start_anchor}"
                    self.warnings.append(warning_msg)
                    self.log(warning_msg, 'warning')
                    continue

                anchor = anchor_match.group(1)

                if anchor not in file_anchors:
                    missing_anchors += 1
                    error_msg = f"Missing anchor '{anchor}' in {file_name} (key: {key})"
                    self.errors.append(error_msg)
                    self.log(error_msg, 'error')
                else:
                    self.log(f"Found anchor '{anchor}' in {file_name}", 'info')

        return (total_anchors, missing_anchors)

    def validate(self) -> bool:
        """Run all validations and return success status"""
        print(f"\n{Fore.CYAN}{'=' * 60}{Style.RESET_ALL}")
        print(f"{Fore.CYAN}Manifest Anchor Validation{Style.RESET_ALL}")
        print(f"{Fore.CYAN}{'=' * 60}{Style.RESET_ALL}\n")

        # Validate plan manifest
        plan_total, plan_missing = self.validate_plan_manifest()

        print()

        # Validate architecture manifest
        arch_total, arch_missing = self.validate_architecture_manifest()

        # Print summary
        print(f"\n{Fore.CYAN}{'=' * 60}{Style.RESET_ALL}")
        print(f"{Fore.CYAN}Validation Summary{Style.RESET_ALL}")
        print(f"{Fore.CYAN}{'=' * 60}{Style.RESET_ALL}\n")

        total_anchors = plan_total + arch_total
        total_missing = plan_missing + arch_missing

        print(f"Plan Manifest:         {plan_total - plan_missing}/{plan_total} anchors valid")
        print(f"Architecture Manifest: {arch_total - arch_missing}/{arch_total} anchors valid")
        print(f"Total:                 {total_anchors - total_missing}/{total_anchors} anchors valid")

        summary = {
            'plan': {'total': plan_total, 'missing': plan_missing},
            'architecture': {'total': arch_total, 'missing': arch_missing},
            'total': {'total': total_anchors, 'missing': total_missing},
            'warnings': self.warnings,
            'errors': self.errors,
            'success': total_missing == 0 and not self.errors,
        }
        self.summary = summary

        if self.json_output:
            try:
                with open(self.json_output, 'w', encoding='utf-8') as f:
                    json.dump(summary, f, indent=2)
            except Exception as exc:
                warning_msg = f"Failed to write summary JSON: {exc}"
                self.warnings.append(warning_msg)
                self.log(warning_msg, 'warning')

        if self.warnings:
            print(f"\n{Fore.YELLOW}Warnings: {len(self.warnings)}{Style.RESET_ALL}")

        if self.errors:
            print(f"\n{Fore.RED}Errors: {len(self.errors)}{Style.RESET_ALL}")
            return False
        else:
            print(f"\n{Fore.GREEN}✓ All manifest anchors validated successfully!{Style.RESET_ALL}\n")
            return True


def main():
    """Main entry point"""
    parser = argparse.ArgumentParser(
        description='Validate manifest anchors against markdown files'
    )
    parser.add_argument(
        '--manifest-dir',
        type=Path,
        default=Path(__file__).parent.parent.parent / '.codemachine' / 'artifacts',
        help='Path to artifacts directory containing manifests (default: ../../.codemachine/artifacts)'
    )
    parser.add_argument(
        '-v', '--verbose',
        action='store_true',
        help='Enable verbose output'
    )
    parser.add_argument(
        '--json-output',
        type=Path,
        help='Optional path to write validation summary JSON'
    )

    args = parser.parse_args()

    if not args.manifest_dir.exists():
        print(f"{Fore.RED}Error: Manifest directory not found: {args.manifest_dir}{Style.RESET_ALL}")
        sys.exit(1)

    validator = AnchorValidator(args.manifest_dir, args.verbose, args.json_output)
    success = validator.validate()

    sys.exit(0 if success else 1)


if __name__ == '__main__':
    main()
