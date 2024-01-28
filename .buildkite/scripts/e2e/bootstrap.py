"""
E2E bootstrapping with Python script
    - A script to resolve stack versions
    - Download and spin up elastic-package
    - Clone integrations repo and prepare packages
    - When E2E finishes, teardown the stack
"""
import os
import subprocess
import tarfile
from util import Util


class Bootstrap:
    ELASTIC_PACKAGE_DISTRO_URL = "https://api.github.com/repos/elastic/elastic-package/releases/latest"

    def __init__(self, stack_version, platform):
        f"""
        A constructor of the {Bootstrap}.

        Args:
            stack_version (str): An Elastic stack version where {Bootstrap} spins up with
            platform (str): pass macos if you are on your dev Mac, otherwise linux 

        Returns:
            void: validates platform, sets stack version and platform
        """
        self.stack_version = stack_version
        self.__validate_platform__(platform)
        self.__set_distro__(platform)

    def __validate_platform__(self, platform):
        if platform not in ["macos", "linux"]:
            raise ValueError("platform accepts {platform}")
        self.platform = platform

    def __set_distro__(self, platform):
        self.distro = "darwin_amd64.tar.gz" if platform == "macos" else "linux_amd64.tar.gz"

    def __download_elastic_package__(self):
        response = Util.call_url_with_retry(self.ELASTIC_PACKAGE_DISTRO_URL)
        release_info = response.json()

        download_urls = [asset["browser_download_url"] for asset in release_info["assets"]]
        download_url = [url for url in download_urls if self.distro in url][0]
        if download_url.strip() == '':
            raise Exception("Could not resolve elastic-package distro download URL.")

        os.system(f"wget {download_url}")
        print("elastic-package is successfully downloaded.")

        # Extract the downloaded tar.gz file
        with tarfile.open(download_url.split("/")[-1], "r:gz") as tar:
            tar.extractall()

    def __make_global__(self):
        result = subprocess.run(['sudo', 'mv', 'elastic-package', '/usr/local/bin'])
        if result.returncode != 0:
            raise Exception("Could not make `elastic-package` global.")

    def __clone_integrations_repo__(self):
        result = subprocess.run(['git', 'clone', 'https://github.com/elastic/integrations.git'])
        if result.returncode != 0:
            raise Exception(f"Error occurred while cloning an integrations repo. Check logs for details.")

    def __get_profile_path__(self):
        return os.path.join(Util.get_home_path(), ".elastic-package/profiles/e2e")

    def __create_config_file__(self, sample_config_file, config_file):
        with open(sample_config_file, 'r') as infile, open(config_file, 'w') as outfile:
            for line in infile:
                if 'stack.logstash_enabled: true' in line:
                    # Logstash is disabled by default, remove the comment
                    line = line.lstrip('#').lstrip()
                outfile.write(line)

    def __setup_elastic_package_profile__(self):
        result = subprocess.run(['elastic-package', 'profiles', 'create', 'e2e'])
        if result.returncode != 0:
            raise Exception(f"Error occurred while creating a profile. Check logs for details.")

        print("`elastic-package` e2e profile created.")

        # elastic-package creates a profile under home directory
        config_example_file = os.path.join(self.__get_profile_path__(), 'config.yml.example')
        config_file = os.path.join(self.__get_profile_path__(), 'config.yml')
        self.__create_config_file__(config_example_file, config_file)
        subprocess.run(['elastic-package', 'profiles', 'use', 'e2e'])

    def __spin_stack__(self):
        # elastic-package stack up -d --version "${ELASTIC_STACK_VERSION}"
        result = subprocess.run(['elastic-package', 'stack', 'up', '-d', '--version', self.stack_version, '-v'])
        if result.returncode != 0:
            self.__teardown_stack__()  # some containers left running, make sure to stop them
            raise Exception(f"Error occurred while running stacks with elastic-package. Check logs for details.")

    def __teardown_stack__(self):
        result = subprocess.run(['elastic-package', 'stack', 'down'])
        if result.returncode != 0:
            raise Exception(f"Error occurred while stopping stacks with elastic-package. Check logs for details.")

    def run_elastic_stack(self):
        """
        Downloads elastic-package, creates a profile and runs ELK, Fleet, ERP and elastic-agent
        """
        self.__download_elastic_package__()
        self.__make_global__()
        self.__clone_integrations_repo__()
        self.__setup_elastic_package_profile__()
        self.__spin_stack__()

    def stop_elastic_stack(self):
        self.__teardown_stack__()
