"""
 Main entry point of the E2E test suites
"""

import os
from bootstrap import Bootstrap
from plugin_test import PluginTest
import util

INTEGRATION_PACKAGES_TO_TEST = ["apache", "m365_defender", "nginx", "tomcat"]


class BootstrapContextManager:
    def __enter__(self):
        stack_version = os.environ.get("ELASTIC_STACK_VERSION")
        project_type = os.environ.get("E2E_PROJECT_TYPE", "on_prems")
        if stack_version is None:
            raise Exception("ELASTIC_STACK_VERSION environment variable is missing, please export and try again.")

        print(f"Starting E2E test of Logstash running Elastic Integrations against {stack_version} version.")
        self.bootstrap = Bootstrap(stack_version, project_type)
        self.bootstrap.run_elastic_stack()
        return self.bootstrap

    def __exit__(self, exc_type, exc_value, traceback):
        if exc_type is not None:
            traceback.print_exception(exc_type, exc_value, traceback)

        if self.bootstrap:
            self.bootstrap.stop_elastic_stack()


def main():
    failed_packages = []

    with BootstrapContextManager() as bootstrap:
        working_dir = os.getcwd()
        test_plugin = PluginTest()
        for package in INTEGRATION_PACKAGES_TO_TEST:
            try:
                os.chdir(f"{working_dir}/integrations/packages/{package}")
                test_plugin.on(package)
            except Exception as e:
                print(f"Test failed for {package} with {e}.")
                failed_packages.append(package)

        container = util.get_logstash_container()

        # pretty printing
        print(f"Logstash docker container logs..")
        ls_container_logs = container.logs().decode('utf-8')
        for log_line in ls_container_logs.splitlines():
            print(log_line)

    if len(failed_packages) > 0:
        raise Exception(f"Following packages failed: {failed_packages}")


if __name__ == "__main__":
    main()
