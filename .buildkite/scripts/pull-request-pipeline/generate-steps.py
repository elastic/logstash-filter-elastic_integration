import os
import sys
import typing

from ruamel.yaml import YAML

CURRENT_PATH = os.path.dirname(os.path.abspath(__file__))


def eight_x_branch_steps() -> typing.Dict[str, typing.List[typing.Any]]:
    with open(os.path.join(CURRENT_PATH, "pull-request-pipeline-steps-8x.yml")) as fp:
        return YAML().load(fp)


def main_branch_steps() -> typing.Dict[str, typing.List[typing.Any]]:
    with open(os.path.join(CURRENT_PATH, "pull-request-pipeline-steps-main.yml")) as fp:
        return YAML().load(fp)


if __name__ == "__main__":
    structure = {
        "agents": {
            "provider": "gcp",
            "machineType": "n1-standard-4",
            "image": "family/core-ubuntu-2204"
        },
        "steps": []}

    GITHUB_PR_TARGET_BRANCH = os.getenv("GITHUB_PR_TARGET_BRANCH")
    steps = eight_x_branch_steps() if GITHUB_PR_TARGET_BRANCH == '8.x' else main_branch_steps()
    group_desc = "8.x branch steps" if GITHUB_PR_TARGET_BRANCH == '8.x' else "main branch steps"
    key_desc = "eight-x-steps" if GITHUB_PR_TARGET_BRANCH == '8.x' else "main-steps"

    structure["steps"].append({
        "group": group_desc,
        "key": key_desc,
        **steps,
    })

    print(
        '# yaml-language-server: $schema=https://raw.githubusercontent.com/buildkite/pipeline-schema/main/schema.json')
    YAML().dump(structure, sys.stdout)
