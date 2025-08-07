import os
import requests
import sys
import typing
from requests.adapters import HTTPAdapter, Retry

from ruamel.yaml import YAML

RELEASES_URL = "https://raw.githubusercontent.com/logstash-plugins/.ci/refs/heads/1.x/logstash-versions.yml"
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
            "machineType": "n2-standard-4",
            "imageProject": "elastic-images-prod",
            "image": "family/platform-ingest-logstash-multi-jdk-ubuntu-2204"
        },
        "steps": []}

    steps = []
    response = call_url_with_retry(RELEASES_URL)
<<<<<<< HEAD
    versions_json = response.json()

    # there are situations to manually run CIs with PR change,
    # set MANUAL_TARGET_BRANCH with upstream target branch and run
    manually_set_target_branch: typing.final = os.getenv("MANUAL_TARGET_BRANCH")
    target_branch: typing.final = manually_set_target_branch if manually_set_target_branch else os.getenv("TARGET_BRANCH")
    print(f"Running with target_branch: {target_branch}")
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
=======
    yaml = YAML(typ='safe')
    versions_yaml: typing.final = yaml.load(response.text)

    # Use BUILDKITE_SOURCE to figure out PR merge or schedule.
    # If PR merge, no need to run builds on all branches, target branch will be good
    #   - webhook when PR gets merged
    #   - schedule when daily schedule starts
    #   - ui when manually kicking job from BK UI
    #       - manual kick off will be on PR or entire main branch, can be decided with BUILDKITE_BRANCH
    bk_source = os.getenv("BUILDKITE_SOURCE")
    bk_branch = os.getenv("BUILDKITE_BRANCH")
    steps = generate_steps_for_scheduler(versions_yaml) if (bk_source == "schedule" or bk_branch == "main") \
        else generate_steps_for_main_branch(versions_yaml)
>>>>>>> febb207 (Update buildkite script to look for new logstash releases location (#358))

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
