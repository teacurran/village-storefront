#!/usr/bin/env python3
"""
Add custom x-* extensions to OpenAPI operations.

This script adds required custom metadata to all operations in the OpenAPI spec:
- x-tenant-scope: Indicates tenant context requirement (none, required, optional)
- x-feature-flags: List of feature flags that must be enabled
- x-rate-limit: Rate limiting configuration (limit, window, scope)

Usage:
    python3 api/scripts/add-openapi-extensions.py api/v1/openapi.yaml

The script makes a backup before modifying the file.
"""

import sys
import re
from pathlib import Path
from typing import Dict, List, Tuple
import shutil

# Extension patterns for different endpoint types
EXTENSION_TEMPLATES = {
    "public_read": {
        "x-tenant-scope": "none",
        "x-feature-flags": [],
        "x-rate-limit": {"limit": 1000, "window": "60s", "scope": "ip"}
    },
    "public_tenant": {
        "x-tenant-scope": "required",
        "x-feature-flags": [],
        "x-rate-limit": {"limit": 1000, "window": "60s", "scope": "ip"}
    },
    "auth_sensitive": {
        "x-tenant-scope": "required",
        "x-feature-flags": [],
        "x-rate-limit": {"limit": 20, "window": "60s", "scope": "ip", "note": "Strict limit for auth endpoints"}
    },
    "authenticated_read": {
        "x-tenant-scope": "required",
        "x-feature-flags": [],
        "x-rate-limit": {"limit": 1000, "window": "60s", "scope": "user"}
    },
    "authenticated_write": {
        "x-tenant-scope": "required",
        "x-feature-flags": [],
        "x-rate-limit": {"limit": 500, "window": "60s", "scope": "user"}
    },
    "headless": {
        "x-tenant-scope": "required",
        "x-feature-flags": ["headless.api.enabled"],
        "x-rate-limit": {"limit": 5000, "window": "60s", "scope": "client"}
    },
    "platform_admin": {
        "x-tenant-scope": "optional",
        "x-feature-flags": [],
        "x-rate-limit": {"limit": 1000, "window": "60s", "scope": "user"}
    }
}

def determine_extension_type(path: str, method: str, tags: List[str], has_security: bool) -> str:
    """Determine which extension template to use based on operation characteristics."""

    # System/health endpoints
    if '/health' in path:
        return "public_read"

    # Auth endpoints
    if '/auth/login' in path or '/auth/register' in path:
        return "auth_sensitive"
    if '/auth/' in path:
        return "public_tenant"

    # Platform admin
    if '/platform/' in path or 'Platform' in tags:
        return "platform_admin"

    # Headless API
    if '/headless/' in path or 'Headless' in tags:
        return "headless"

    # Public storefront endpoints
    if not has_security and ('/catalog/' in path or '/theme/' in path):
        return "public_tenant"

    # Authenticated write operations
    if has_security and method in ['post', 'put', 'patch', 'delete']:
        return "authenticated_write"

    # Authenticated read operations
    if has_security:
        return "authenticated_read"

    # Default to public tenant-scoped
    return "public_tenant"

def format_extension_value(key: str, value) -> str:
    """Format extension value as YAML."""
    if isinstance(value, list):
        if not value:
            return "[]"
        items = "\n".join(f"        - {item}" for item in value)
        return f"\n{items}"
    elif isinstance(value, dict):
        lines = []
        for k, v in value.items():
            if isinstance(v, str):
                lines.append(f'        {k}: "{v}"')
            else:
                lines.append(f'        {k}: {v}')
        return "\n" + "\n".join(lines)
    elif isinstance(value, str):
        return f' "{value}"'
    else:
        return f" {value}"

def add_extensions_to_operation(content: str) -> str:
    """Add extensions to all operations that don't have them yet."""

    lines = content.split('\n')
    result = []
    i = 0

    while i < len(lines):
        line = lines[i]
        result.append(line)

        # Check if this is an operation definition (get:, post:, etc.)
        operation_match = re.match(r'^    (get|post|put|patch|delete|head|options):\s*$', line)

        if operation_match:
            method = operation_match.group(1)

            # Look ahead to check if extensions already exist
            j = i + 1
            has_extensions = False
            security_line = None
            tags_line = None
            path_line = None

            # Find the path (backtrack)
            for k in range(i - 1, max(0, i - 10), -1):
                if re.match(r'^  /[^:]+:\s*$', lines[k]):
                    path_line = lines[k].strip().rstrip(':')
                    break

            # Scan forward to check existing content
            while j < len(lines) and (lines[j].startswith('      ') or lines[j].strip() == ''):
                if re.match(r'^\s+x-(tenant-scope|feature-flags|rate-limit):', lines[j]):
                    has_extensions = True
                    break
                if re.match(r'^\s+security:', lines[j]):
                    security_line = lines[j]
                if re.match(r'^\s+tags:', lines[j]):
                    tags_line = lines[j]
                if re.match(r'^    (get|post|put|patch|delete|responses|requestBody):', lines[j]):
                    break
                j += 1

            # If no extensions found, add them
            if not has_extensions and path_line:
                # Determine characteristics
                has_security = security_line is None or '[]' not in security_line
                tags = []
                if tags_line:
                    tag_match = re.search(r'\[(.*?)\]', tags_line)
                    if tag_match:
                        tags = [t.strip() for t in tag_match.group(1).split(',')]

                ext_type = determine_extension_type(path_line, method, tags, has_security)
                template = EXTENSION_TEMPLATES[ext_type]

                # Find insertion point (after security line or after tags/summary)
                insert_pos = i + 1
                while insert_pos < len(lines):
                    if re.match(r'^\s+(security|tags|summary|description):', lines[insert_pos]):
                        insert_pos += 1
                        # Skip multi-line descriptions
                        while insert_pos < len(lines) and lines[insert_pos].startswith('        '):
                            insert_pos += 1
                    else:
                        break

                # Insert extensions
                extension_lines = []
                for key, value in template.items():
                    formatted = format_extension_value(key, value)
                    if '\n' in formatted:
                        extension_lines.append(f"      {key}:{formatted}")
                    else:
                        extension_lines.append(f"      {key}:{formatted}")

                # Insert at the found position
                for ext_line in reversed(extension_lines):
                    result.insert(insert_pos, ext_line)

        i += 1

    return '\n'.join(result)

def main():
    if len(sys.argv) != 2:
        print("Usage: python3 add-openapi-extensions.py <openapi-file>")
        sys.exit(1)

    file_path = Path(sys.argv[1])

    if not file_path.exists():
        print(f"Error: File not found: {file_path}")
        sys.exit(1)

    # Create backup
    backup_path = file_path.with_suffix('.yaml.bak')
    shutil.copy(file_path, backup_path)
    print(f"Created backup: {backup_path}")

    # Read content
    content = file_path.read_text()

    # Add extensions
    print("Adding extensions to operations...")
    modified_content = add_extensions_to_operation(content)

    # Write back
    file_path.write_text(modified_content)
    print(f"Updated: {file_path}")
    print("\nDone! Review changes and run: npm run lint:openapi")

if __name__ == "__main__":
    main()
