"""
 Main entry point of the E2E test suites
"""

import docker
import os
from bootstrap import Bootstrap
from plugin_test import PluginTest

INTEGRATION_PACKAGES_TO_TEST = ["apache", "apache_tomcat", "m365_defender", "nginx", "tomcat"]


def main():
    platform = os.environ.get("E2E_PLATFORM", "linux")
    stack_version = os.environ.get("ELASTIC_STACK_VERSION")
    if stack_version is None:
        raise Exception("ELASTIC_STACK_VERSION environment variable is missing, please export and try again.")

    print(f"Starting E2E test of Logstash running Elastic Integrations against {stack_version} versions.")
    bootstrap = Bootstrap(stack_version, platform)
    bootstrap.run_elastic_stack()
    test_plugin = PluginTest()
    failed_packages = []

    working_dir = os.getcwd()
    for package in INTEGRATION_PACKAGES_TO_TEST:
        try:
            os.chdir(f"{working_dir}/integrations/packages/{package}")
            test_plugin.on(package)
        except Exception as e:
            print(f"Test failed for {package} with {e}.")
            failed_packages.append(package)

    client = docker.from_env()
    container = client.containers.get('elastic-package-stack-e2e-logstash-1')
    # pretty printing
    print(f"Logstash docker container logs..")
    ls_container_logs = container.logs().decode('utf-8')
    for log_line in ls_container_logs.splitlines(): print(log_line)

    bootstrap.stop_elastic_stack()
    if len(failed_packages) > 0:
        raise Exception(f"Following packages failed: {failed_packages}")


if __name__ == "__main__":
    main()
