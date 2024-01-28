import os
import subprocess


def test_with_package(package):
    print(f"Testing the package: {package}")

    os.chdir(f"{os.getcwd()}/integrations/packages/{package}")
    # emit the events under the package
    subprocess.run(['elastic-package', 'test', 'system'])

    # TODO: validate with lS node stats API
