import os
import sys
import typing

from ruamel.yaml import YAML

TEST_COMMAND: typing.final = ".buildkite/scripts/run_tests.sh"


def generate_unit_and_integration_test_steps(target_branch, stack_version, snapshot) -> list[typing.Any]:
    test_steps = []

    # step-1, unit tests
    label_unit_test: typing.final = f"Unit test for {target_branch}, snapshot: {snapshot}"
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
    label_integration_test: typing.final = f"Integration test for {target_branch}, snapshot: {snapshot}"
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


if __name__ == "__main__":
    structure = {
        "agents": {
            "provider": "gcp",
            "machineType": "n1-standard-4",
            "image": "family/core-ubuntu-2204"
        },
        "steps": []}

    steps = []
    gh_pr_target_branch: typing.final = os.getenv("GITHUB_PR_TARGET_BRANCH")
    if gh_pr_target_branch == '8.x':
        stack_version: typing.final = "8.future"
        snapshot: typing.final = "true"
        steps += generate_unit_and_integration_test_steps(gh_pr_target_branch,
                                                          stack_version,
                                                          snapshot)
    elif gh_pr_target_branch == 'main':
        stack_version: typing.final = "main"
        snapshot: typing.final = "true"
        steps += generate_unit_and_integration_test_steps(gh_pr_target_branch,
                                                          stack_version,
                                                          snapshot)
    else:
        # steps for non-snapshot version
        stack_version: typing.final = gh_pr_target_branch
        snapshot = "false"
        steps += generate_unit_and_integration_test_steps(gh_pr_target_branch,
                                                          stack_version,
                                                          snapshot)

        # steps for snapshot version
        snapshot = "true"
        steps += generate_unit_and_integration_test_steps(gh_pr_target_branch,
                                                          stack_version,
                                                          snapshot)

    group_desc = f"{gh_pr_target_branch} branch steps"
    key_desc = f"{gh_pr_target_branch}-steps"
    structure["steps"].append({
        "group": group_desc,
        "key": key_desc,
        "steps": steps
    })

    print(
        '# yaml-language-server: $schema=https://raw.githubusercontent.com/buildkite/pipeline-schema/main/schema.json')
    YAML().dump(structure, sys.stdout)
