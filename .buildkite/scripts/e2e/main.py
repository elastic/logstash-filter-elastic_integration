"""
 Main entry point of the E2E test suites
"""

import os
from bootstrap import Bootstrap
from test_plugin import TestPlugin

INTEGRATION_PACKAGES_TO_TEST = ["apache", "apache_tomcat", "elastic_agent", "elasticsearch",
                                "m365_defender", "nginx", "tomcat"]


def main():
    platform = os.environ.get("E2E_PLATFORM", "linux")
    stack_version = os.environ.get("ELASTIC_STACK_VERSION")
    if stack_version is None:
        raise Exception("ELASTIC_STACK_VERSION environment variable is missing, please export and try again.")

    print(f"Starting E2E test of Logstash running Elastic Integrations against {stack_version} versions.")
    bootstrap = Bootstrap(stack_version, platform)
    bootstrap.run_elastic_stack()
    test_plugin = TestPlugin()
    report = {}

    for package in INTEGRATION_PACKAGES_TO_TEST:
        try:
            os.chdir(f"{os.getcwd()}/integrations/packages/{package}")
            test_plugin.on(package)
        except Exception as e:
            print(f"Test failed for {package} with {e} exception.")
            report[package] = e
        finally:
            os.chdir("../../../")

    bootstrap.stop_elastic_stack()
    if len(report) != 0:
        raise Exception(f"Following packages failed: {report}")


if __name__ == "__main__":
    main()
