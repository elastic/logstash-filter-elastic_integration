import sys
import typing
import util

from ruamel.yaml import YAML

RELEASES_URL = "https://raw.githubusercontent.com/elastic/logstash/main/ci/logstash_releases.json"
TEST_COMMAND: typing.final = ".buildkite/scripts/run_e2e_tests.sh"


def generate_test_step(stack_version, es_treeish, snapshot) -> dict:
    label_integration_test: typing.final = f"E2E tests for {stack_version}, snapshot: {snapshot}"
    return {
        "label": label_integration_test,
        "command": TEST_COMMAND,
        "env": {
            "SNAPSHOT": snapshot,
            "ELASTIC_STACK_VERSION": stack_version,
            "ELASTICSEARCH_TREEISH": es_treeish,
            "TARGET_BRANCH": branch
        }
    }


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

    steps = []
    response = util.call_url_with_retry(RELEASES_URL)
    release_json = response.json()
    snapshots = release_json["snapshots"]
    for snapshot_version in snapshots:
        if snapshots[snapshot_version].startswith("7.") or snapshots[snapshot_version].startswith("8.15"):
            continue
        full_stack_version = snapshots[snapshot_version]
        version_parts = snapshots[snapshot_version].split(".")
        major_minor_versions = snapshot_version if snapshot_version == "main" else f"{version_parts[0]}.{version_parts[1]}"
        branch = f"{version_parts[0]}.x" if snapshot_version.find("future") > -1 else major_minor_versions
        steps.append(generate_test_step(full_stack_version, branch, "true"))

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
