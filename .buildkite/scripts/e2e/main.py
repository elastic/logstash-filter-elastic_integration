"""
 Main entry point of the E2E test suites
"""

import os
from bootstrap import Bootstrap
from test_package import test_with_package

INTEGRATION_PACKAGES_TO_TEST = ["apache", "apache_tomcat", "elastic_agent", "elasticsearch", "m365_defender", "nginx",
                                "tomcat", "crowdstrike"]


def main():
    print("Starting E2E test of Logstash running Elastic Integrations...")
    platform = os.environ.get("E2E_PLATFORM", "linux")
    stack_version = os.environ.get("ELASTIC_STACK_VERSION")
    if stack_version is None:
        raise Exception("ELASTIC_STACK_VERSION environment variable is missing, please export and try again.")

    bootstrap = Bootstrap(stack_version, platform)
    bootstrap.run_elastic_stack()

    for package in INTEGRATION_PACKAGES_TO_TEST:
        try:
            test_with_package(package)
        except Exception as e:
            print(f"Test failed for {package} with {e} exception.")

    # bootstrap.stop_elastic_stack()


if __name__ == "__main__":
    main()
