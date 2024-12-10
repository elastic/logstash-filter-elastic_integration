import os
import requests
import sys
import typing
from requests.adapters import HTTPAdapter, Retry

from ruamel.yaml import YAML

RELEASES_URL = "https://raw.githubusercontent.com/elastic/logstash/main/ci/logstash_releases.json"
TEST_COMMAND: typing.final = ".buildkite/scripts/run_tests.sh"


def generate_unit_and_integration_test_steps(stack_version, snapshot) -> list[typing.Any]:
    test_steps = []

    # step-1, unit tests
    label_unit_test: typing.final = f"Unit test for {stack_version}, snapshot: {snapshot}"
    test_steps.append({
        "label": label_unit_test,
        "command": TEST_COMMAND,
        "env": {
            "SNAPSHOT": snapshot,
            "ELASTIC_STACK_VERSION": stack_version,
            "INTEGRATION": "false"
        }
    })

    # step-2, integration tests
    label_integration_test: typing.final = f"Integration test for {stack_version}, snapshot: {snapshot}"
    test_steps.append({
        "label": label_integration_test,
        "command": TEST_COMMAND,
        "env": {
            "SNAPSHOT": snapshot,
            "ELASTIC_STACK_VERSION": stack_version,
            "INTEGRATION": "true",
            "SECURE_INTEGRATION": "true",
            "LOG_LEVEL": "info"
        }
    })
    return test_steps


def call_url_with_retry(url: str, max_retries: int = 5, delay: int = 1) -> requests.Response:
    schema = "https://" if "https://" in url else "http://"
    session = requests.Session()
    # retry on most common failures such as connection timeout(408), etc...
    retries = Retry(total=max_retries, backoff_factor=delay, status_forcelist=[408, 502, 503, 504])
    session.mount(schema, HTTPAdapter(max_retries=retries))
    return session.get(url)


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

    target_branch: typing.final = os.getenv("GITHUB_PR_TARGET_BRANCH")
    if target_branch == '8.x':
        full_stack_version: typing.final = versions_json["snapshots"]["8.future"]
        steps += generate_unit_and_integration_test_steps(full_stack_version, "true")
    elif target_branch == 'main':
        full_stack_version: typing.final = versions_json["snapshots"][target_branch]
        steps += generate_unit_and_integration_test_steps(full_stack_version, "true")
    else:
        # generate steps for the version if released
        releases = versions_json["releases"]
        for release_version in releases:
            if releases[release_version].startswith(target_branch):
                steps += generate_unit_and_integration_test_steps(releases[release_version], "false")
                break

        # steps for snapshot version
        snapshots = versions_json["snapshots"]
        for snapshot_version in snapshots:
            if snapshots[snapshot_version].startswith(target_branch):
                steps += generate_unit_and_integration_test_steps(snapshots[snapshot_version], "false")
                break

    group_desc = f"{target_branch} branch steps"
    key_desc = "pr-and-build-steps"
    structure["steps"].append({
        "group": group_desc,
        "key": key_desc,
        "steps": steps
    })

    print(
        '# yaml-language-server: $schema=https://raw.githubusercontent.com/buildkite/pipeline-schema/main/schema.json')
    YAML().dump(structure, sys.stdout)
