"""
E2E bootstrapping with Python script
    - A script to resolve stack versions
    - Download and spin up elastic-package
    - Clone integrations repo and prepare packages
    - When E2E finishes, teardown the stack
"""
import io
import os
import subprocess
import tarfile
import time
from logstash_stats import LogstashStats
from util import Util


class Bootstrap:
    ELASTIC_PACKAGE_DISTRO_URL = "https://api.github.com/repos/elastic/elastic-package/releases/latest"
    LOGSTASH_CONTAINER_NAME = "elastic-package-stack-e2e-logstash-1"
    PLUGIN_NAME = "logstash-filter-elastic_integration"

    logstash_stats_api = LogstashStats()

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
        self.__validate_platform(platform)
        self.__set_distro(platform)

    def __validate_platform(self, platform):
        platforms = ["macos", "linux"]
        if platform not in platforms:
            raise ValueError(f"platform accepts {platforms}")
        self.platform = platform

    def __set_distro(self, platform):
        self.distro = "darwin_amd64.tar.gz" if platform == "macos" else "linux_amd64.tar.gz"

    def __download_elastic_package(self):
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

    def __make_elastic_package_global(self):
        result = subprocess.run(["sudo", "mv", "elastic-package", "/usr/local/bin"])
        if result.returncode != 0:
            raise Exception("Could not make `elastic-package` global.")

    def __clone_integrations_repo(self):
        result = subprocess.run(["git", "clone", "https://github.com/elastic/integrations.git"])
        if result.returncode != 0:
            raise Exception(f"Error occurred while cloning an integrations repo. Check logs for details.")

    def __get_profile_path(self):
        return os.path.join(Util.get_home_path(), ".elastic-package/profiles/e2e")

    def __create_config_file(self, sample_config_file, config_file):
        with open(sample_config_file, "r") as infile, open(config_file, "w") as outfile:
            for line in infile:
                if "stack.logstash_enabled: true" in line:
                    # Logstash is disabled by default, remove the comment
                    line = line.lstrip('#').lstrip()
                outfile.write(line)

    def __setup_elastic_package_profile(self):
        result = subprocess.run(["elastic-package", "profiles", "create", "e2e"])
        if result.returncode != 0:
            raise Exception(f"Error occurred while creating a profile. Check logs for details.")

        print("`elastic-package` e2e profile created.")

        # elastic-package creates a profile under home directory
        config_example_file = os.path.join(self.__get_profile_path(), "config.yml.example")
        config_file = os.path.join(self.__get_profile_path(), "config.yml")
        self.__create_config_file(config_example_file, config_file)
        subprocess.run(["elastic-package", "profiles", "use", "e2e"])

    def __install_plugin(self):
        with open("VERSION", "r") as f:
            version = f.read()

        plugin_name = f"logstash-filter-elastic_integration-{version.strip()}-java.gem"
        container = Util.get_logstash_container()

        tar_data = io.BytesIO()
        with tarfile.open(fileobj=tar_data, mode='w') as tar:
            tar.add(plugin_name, arcname=f"/usr/share/logstash/{plugin_name}")

        tar_data.seek(0)
        container.put_archive('/', tar_data.getvalue())

        print("Installing logstash-filter-elastic_integration plugin...")
        command = f"bin/logstash-plugin install {plugin_name}"
        exec_result = container.exec_run(command)
        if exec_result.exit_code != 0:
            raise Exception("Error occurred installing plugin in Logstash container, "
                            f"output: {exec_result.output.decode('utf-8')}.")
        print("Plugin installed successfully.")

    def __reload_container(self):
        result = subprocess.run(["docker", "restart", f"{self.LOGSTASH_CONTAINER_NAME}"])
        print(f"Logstash container restart result: {result}")
        if result.returncode != 0:
            raise Exception(f"Error occurred while reloading Logstash container, see logs for details.")

    def __update_pipeline_config(self):
        local_config_file = ".buildkite/scripts/e2e/config/pipeline.conf"
        container_config_file_path = "/usr/share/logstash/pipeline/logstash.conf"
        # python docker client (internally uses subprocess) requires special TAR header with tar operations
        result = subprocess.run(["docker", "cp", f"{local_config_file}",
                                 f"{self.LOGSTASH_CONTAINER_NAME}:{container_config_file_path}"])
        print(f"Copy result: {result}")
        if result.returncode != 0:
            raise Exception(f"Error occurred while replacing pipeline config, see logs for details.")

    def __wait_for_pipeline_reload(self):
        pipeline_stats = self.logstash_stats_api.get()["pipelines"]["main"]
        while True:
            if pipeline_stats["reloads"]["failures"] > 0 or pipeline_stats["reloads"]["successes"] > 0:
                break
            time.sleep(3)
            pipeline_stats = self.logstash_stats_api.get()["pipelines"]["main"]

        if pipeline_stats["reloads"]["failures"] > 0:
            raise Exception("Reloading Logstash pipeline failed, check logs for details.")

        print("Reloading pipeline succeeded.")

    def __spin_stack(self):
        # elastic-package stack up -d --version "${ELASTIC_STACK_VERSION}"
        result = subprocess.run(["elastic-package", "stack", "up", "-d", "--version", self.stack_version])
        if result.returncode != 0:
            self.__teardown_stack()  # some containers left running, make sure to stop them
            raise Exception(f"Error occurred while running stacks with elastic-package. Check logs for details.")

    def __teardown_stack(self):
        result = subprocess.run(["elastic-package", "stack", "down"])
        if result.returncode != 0:
            raise Exception(f"Error occurred while stopping stacks with elastic-package. Check logs for details.")

    def run_elastic_stack(self):
        """
        Downloads elastic-package, creates a profile and runs ELK, Fleet, ERP and elastic-agent
        """
        self.__download_elastic_package()
        self.__make_elastic_package_global()
        self.__clone_integrations_repo()
        self.__setup_elastic_package_profile()
        self.__spin_stack()
        self.__install_plugin()
        self.__reload_container()
        self.__update_pipeline_config()
        self.__wait_for_pipeline_reload()

    def stop_elastic_stack(self):
        print(f"Stopping elastic-package stack...")
        self.__teardown_stack()
