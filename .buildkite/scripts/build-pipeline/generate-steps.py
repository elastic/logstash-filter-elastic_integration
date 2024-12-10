import requests
import sys
import typing
from requests.adapters import HTTPAdapter, Retry

from ruamel.yaml import YAML

RELEASES_URL = "https://raw.githubusercontent.com/elastic/logstash/main/ci/logstash_releases.json"
TEST_COMMAND: typing.final = ".buildkite/scripts/run_tests.sh"


def call_url_with_retry(url: str, max_retries: int = 5, delay: int = 1) -> requests.Response:
    schema = "https://" if "https://" in url else "http://"
    session = requests.Session()
    # retry on most common failures such as connection timeout(408), etc...
    retries = Retry(total=max_retries, backoff_factor=delay, status_forcelist=[408, 502, 503, 504])
    session.mount(schema, HTTPAdapter(max_retries=retries))
    return session.get(url)


def generate_test_step(stack_version, branch, snapshot) -> dict:
    label_integration_test: typing.final = f"Integration test for {stack_version}, snapshot: {snapshot}"
    return {
        "label": label_integration_test,
        "command": TEST_COMMAND,
        "env": {
            "SNAPSHOT": snapshot,
            "ELASTIC_STACK_VERSION": stack_version,
            "ELASTICSEARCH_TREEISH": branch,
            "TARGET_BRANCH": branch,
            "INTEGRATION": "true",
            "SECURE_INTEGRATION": "true",
            "LOG_LEVEL": "info"
        }
    }


if __name__ == "__main__":
    structure = {
        "agents": {
            "provider": "gcp",
            "machineType": "n1-standard-4",
            "image": "family/core-ubuntu-2204"
        },
        "steps": []}

    steps = []
    response = call_url_with_retry(RELEASES_URL)
    versions_json = response.json()
    snapshots = versions_json["snapshots"]
    for snapshot_version in snapshots:
        if snapshots[snapshot_version].startswith("7.") or snapshots[snapshot_version].startswith("8.15"):
            continue
        full_stack_version = snapshots[snapshot_version]
        version_parts = snapshots[snapshot_version].split(".")
        major_minor_versions = snapshot_version if snapshot_version == "main" else f"{version_parts[0]}.{version_parts[1]}"
        branch = f"{version_parts[0]}.x" if snapshot_version.find("future") > -1 else major_minor_versions
        steps.append(generate_test_step(full_stack_version, branch, "true"))

    group_desc = f"Build steps"
    key_desc = "build-steps"
    structure["steps"].append({
        "group": group_desc,
        "key": key_desc,
        "steps": steps,
    })

    print(
        '# yaml-language-server: $schema=https://raw.githubusercontent.com/buildkite/pipeline-schema/main/schema.json')
    YAML().dump(structure, sys.stdout)
