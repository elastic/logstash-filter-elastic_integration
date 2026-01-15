import os
import requests
import sys
import typing
from requests.adapters import HTTPAdapter, Retry

from ruamel.yaml import YAML

RELEASES_URL = "https://raw.githubusercontent.com/logstash-plugins/.ci/refs/heads/1.x/logstash-versions.yml"
TEST_COMMAND: typing.final = ".buildkite/scripts/run_e2e_tests.sh"


def call_url_with_retry(url: str, max_retries: int = 5, delay: int = 1) -> requests.Response:
    schema = "https://" if "https://" in url else "http://"
    session = requests.Session()
    # retry on most common failures such as connection timeout(408), etc...
    retries = Retry(total=max_retries, backoff_factor=delay, status_forcelist=[408, 502, 503, 504])
    session.mount(schema, HTTPAdapter(max_retries=retries))
    return session.get(url)


def generate_test_step(stack_version, es_treeish, snapshot) -> dict:
    label_integration_test: typing.final = f"E2E tests for {stack_version}, snapshot: {snapshot}"
    return {
        "label": label_integration_test,
        "command": TEST_COMMAND,
        "env": {
            "SNAPSHOT": snapshot,
            "ELASTIC_STACK_VERSION": stack_version,
            "ELASTICSEARCH_TREEISH": es_treeish
        }
    }


if __name__ == "__main__":
    structure = {
        "agents": {
            "provider": "gcp",
            "machineType": "n2-standard-16",
            "imageProject": "elastic-images-prod",
            "image": "family/platform-ingest-logstash-multi-jdk-ubuntu-2204",
            "diskSizeGb": 120
        },
        "steps": []}

    steps = []
    response = call_url_with_retry(RELEASES_URL)
    yaml = YAML(typ='safe')
    versions_yaml: typing.final = yaml.load(response.text)

    # there are situations to manually run CIs with PR change,
    # set MANUAL_TARGET_BRANCH with upstream target branch and run
    manually_set_target_branch: typing.final = os.getenv("MANUAL_TARGET_BRANCH")
    target_branch: typing.final = manually_set_target_branch if manually_set_target_branch else os.getenv("TARGET_BRANCH")
    print(f"Running with target_branch: {target_branch}")
    if target_branch == '8.x':
        full_stack_version: typing.final = versions_yaml["snapshots"]["8.future"]
        steps.append(generate_test_step(full_stack_version, target_branch, "true"))
    elif target_branch == 'main':
        full_stack_version: typing.final = versions_yaml["snapshots"][target_branch]
        steps.append(generate_test_step(full_stack_version, target_branch, "true"))
    else:
        # generate steps for the version if released
        releases = versions_yaml["releases"]
        for release_version in releases:
            if releases[release_version].startswith(target_branch):
                steps.append(generate_test_step(releases[release_version], target_branch, "false"))
                break
        # steps for snapshot version
        snapshots = versions_yaml["snapshots"]
        for snapshot_version in snapshots:
            if snapshots[snapshot_version].startswith(target_branch):
                steps.append(generate_test_step(snapshots[snapshot_version], target_branch, "false"))
                break

    group_desc = f"{target_branch} branch E2E steps"
    key_desc = "e2e-steps"
    structure["steps"].append({
        "group": group_desc,
        "key": key_desc,
        "steps": steps
    })

    print(
        '# yaml-language-server: $schema=https://raw.githubusercontent.com/buildkite/pipeline-schema/main/schema.json')
    YAML().dump(structure, sys.stdout)
