import os
import requests
import sys
import typing
from requests.adapters import HTTPAdapter, Retry
from ruamel.yaml import YAML

RELEASES_URL = "https://raw.githubusercontent.com/elastic/logstash/main/ci/logstash_releases.json"
TEST_COMMAND: typing.final = ".buildkite/scripts/run_e2e_tests.sh"


def call_url_with_retry(url: str, max_retries: int = 5, delay: int = 1) -> requests.Response:
    schema = "https://" if "https://" in url else "http://"
    session = requests.Session()
    # retry on most common failures such as connection timeout(408), etc...
    retries = Retry(total=max_retries, backoff_factor=delay, status_forcelist=[408, 502, 503, 504])
    session.mount(schema, HTTPAdapter(max_retries=retries))
    return session.get(url)


def generate_test_step(stack_version, branch, snapshot) -> dict:
    label_integration_test: typing.final = f"E2E tests for {stack_version}, snapshot: {snapshot}"
    step: dict = {
        "label": label_integration_test,
        "command": TEST_COMMAND,
        "env": {
            "SNAPSHOT": snapshot,
            "ELASTIC_STACK_VERSION": stack_version,
        }
    }
    # we are not going to set branch if job kicked of through webhook (PR merge or manual PR run)
    if branch is not None:
        step["env"]["TARGET_BRANCH"] = branch
    return step


def generate_steps_for_scheduler(versions) -> list:
    steps: list = []
    snapshots = versions["snapshots"]
    for snapshot_version in snapshots:
        if snapshots[snapshot_version].startswith("7."):
            continue
        full_stack_version = snapshots[snapshot_version]
        version_parts = snapshots[snapshot_version].split(".")
        major_minor_versions = snapshot_version if snapshot_version == "main" else f"{version_parts[0]}.{version_parts[1]}"
        branch = f"{version_parts[0]}.x" if snapshot_version.find("future") > -1 else major_minor_versions
        steps.append(generate_test_step(full_stack_version, branch, "true"))
    return steps


def generate_steps_for_main_branch(versions) -> list:
    steps: list = []
    full_stack_version: typing.final = versions["snapshots"]["main"]
    steps.append(generate_test_step(full_stack_version, None, "true"))
    return steps


if __name__ == "__main__":
    structure = {
        "agents": {
            "provider": "gcp",
            "machineType": "n2-standard-4",
            "imageProject": "elastic-images-prod",
            "image": "family/platform-ingest-logstash-multi-jdk-ubuntu-2204",
            "diskSizeGb": 120
        },
        "steps": []}

    response = call_url_with_retry(RELEASES_URL)
    versions_json: typing.final = response.json()

    # Use BUILDKITE_SOURCE to figure out PR merge or schedule.
    # If PR merge, no need to run builds on all branches, target branch will be good
    #   - webhook when PR gets merged
    #   - schedule when daily schedule starts
    #   - ui when manually kicking job from BK UI
    #       - manual kick off will be on PR or entire main branch, can be decided with BUILDKITE_BRANCH
    bk_source = os.getenv("BUILDKITE_SOURCE")
    bk_branch = os.getenv("BUILDKITE_BRANCH")
    steps = generate_steps_for_scheduler(versions_json) if (bk_source == "schedule" or bk_branch == "main") \
        else generate_steps_for_main_branch(versions_json)

    group_desc = f"E2E steps"
    key_desc = "e2e-steps"
    structure["steps"].append({
        "group": group_desc,
        "key": key_desc,
        "steps": steps,
    })

    print(
        '# yaml-language-server: $schema=https://raw.githubusercontent.com/buildkite/pipeline-schema/main/schema.json')
    YAML().dump(structure, sys.stdout)
