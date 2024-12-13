import os
import requests
import sys
import typing
from requests.adapters import HTTPAdapter, Retry

from ruamel.yaml import YAML

RELEASES_URL = "https://raw.githubusercontent.com/elastic/logstash/main/ci/logstash_releases.json"
TEST_MATRIX_URL = "https://raw.githubusercontent.com/elastic/logstash-filter-elastic_integration/main/.buildkite/pull" \
                  "-request-test-matrix.yml"
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


def make_matrix_version_key(branch: str) -> str:
    branch_parts: typing.final = branch.split(".")
    return branch_parts[0] + ".x"


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

    matrix_map = call_url_with_retry(TEST_MATRIX_URL)
    matrix_map_yaml = YAML().load(matrix_map.text)

    # there are situations to manually run CIs with PR change,
    # set MANUAL_TARGET_BRANCH with upstream target branch and run
    manually_set_target_branch: typing.final = os.getenv("MANUAL_TARGET_BRANCH")
    target_branch: typing.final = manually_set_target_branch if manually_set_target_branch \
        else os.getenv("TARGET_BRANCH")
    print(f"Running with target_branch: {target_branch}")

    matrix_version_key = target_branch if target_branch == "main" else make_matrix_version_key(target_branch)
    matrix_releases = matrix_map_yaml.get(matrix_version_key, {}).get("releases", [])
    matrix_snapshots = matrix_map_yaml.get(matrix_version_key, {}).get("snapshots", [])

    # let's print what matrix we have got, helps debugging
    print(f"matrix_releases: {matrix_releases}")
    print(f"matrix_snapshots: {matrix_snapshots}")
    for matrix_release in matrix_releases:
        full_stack_version: typing.final = versions_json["releases"].get(matrix_release)
        # noop, if they are declared in the matrix but not in the release
        if full_stack_version is not None:
            steps += generate_unit_and_integration_test_steps(full_stack_version, "false")

    for matrix_snapshot in matrix_snapshots:
        full_stack_version: typing.final = versions_json["snapshots"].get(matrix_snapshot)
        # noop, if they are declared in the matrix but not in the snapshot
        if full_stack_version is not None:
            steps += generate_unit_and_integration_test_steps(full_stack_version, "true")

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
