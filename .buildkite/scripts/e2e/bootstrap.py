"""
E2E bootstrapping with Python script
    - A script to resolve stack versions
    - Download and spin up elastic-package
    - Clone integrations repo and prepare packages
    - When E2E finishes, teardown the stack
"""
import io
import os
import tarfile
from util import Util


class Bootstrap:
    ELASTIC_PACKAGE_DISTRO_URL = "https://api.github.com/repos/elastic/elastic-package/releases/latest"
    LOGSTASH_CONTAINER_NAME = "elastic-package-stack-e2e-logstash-1"
    PLUGIN_NAME = "logstash-filter-elastic_integration"

    def __init__(self, stack_version: str, platform: str) -> None:
        f"""
        A constructor of the {Bootstrap}.

        Args:
            stack_version: An Elastic stack version where {Bootstrap} spins up with
            platform: pass macos if you are on your dev Mac, otherwise linux 

        Returns:
            Validates platform, sets stack version and platform
        """
        self.stack_version = stack_version
        self.__validate_platform(platform)
        self.__set_distro(platform)

    def __validate_platform(self, platform: str) -> None:
        platforms = ["macos", "linux"]
        if platform not in platforms:
            raise ValueError(f"platform accepts {platforms} only")
        self.platform = platform

    def __set_distro(self, platform) -> None:
        self.distro = "darwin_amd64.tar.gz" if platform == "macos" else "linux_amd64.tar.gz"

    def __download_elastic_package(self) -> None:
        response = Util.call_url_with_retry(self.ELASTIC_PACKAGE_DISTRO_URL)
        release_info = response.json()

        download_urls = [asset["browser_download_url"] for asset in release_info["assets"]]
        download_url = [url for url in download_urls if self.distro in url][0]
        if download_url.strip() == '':
            raise Exception(f"Could not resolve elastic-package distro download URL, release info: {release_info}.")

        file_name = "downloaded_elastic_package_" + self.distro
        # downloading with `urllib3` gives a different size file which causes a corrupted file issue
        commands = ["curl", "-o", file_name, "--retry", "5", "--retry-delay", "5", "-fSL", download_url]
        error_message = "Failed to download elastic-package"
        Util.run_subprocess(commands, error_message)
        print("elastic-package is successfully downloaded.")

        # Extract the downloaded tar.gz file
        commands = ["tar", "zxf", file_name]
        error_message = f"Error occurred while unzipping {commands}"
        Util.run_subprocess(commands, error_message)

    def __make_elastic_package_global(self) -> None:
        commands = ["sudo", "mv", "elastic-package", "/usr/local/bin"]
        error_message = "Could not make `elastic-package` global"
        Util.run_subprocess(commands, error_message)

    def __clone_integrations_repo(self) -> None:
        commands = ["git", "clone", "https://github.com/elastic/integrations.git"]
        error_message = "Error occurred while cloning an integrations repo. Check logs for details."
        Util.run_subprocess(commands, error_message)

    def __get_profile_path(self) -> str:
        return os.path.join(Util.get_home_path(), ".elastic-package/profiles/e2e")

    def __create_config_file(self, sample_config_file: str, config_file: str) -> None:
        with open(sample_config_file, "r") as infile, open(config_file, "w") as outfile:
            for line in infile:
                if "stack.logstash_enabled: true" in line:
                    # Logstash is disabled by default, remove the comment
                    line = line.lstrip('#').lstrip()
                outfile.write(line)

    def __setup_elastic_package_profile(self) -> None:
        commands = ["elastic-package", "profiles", "create", "e2e"]
        error_message = "Error occurred while creating a profile. Check logs for details."
        Util.run_subprocess(commands, error_message)

        print("`elastic-package` e2e profile created.")

        # elastic-package creates a profile under home directory
        config_example_file = os.path.join(self.__get_profile_path(), "config.yml.example")
        config_file = os.path.join(self.__get_profile_path(), "config.yml")
        self.__create_config_file(config_example_file, config_file)
        commands = ["elastic-package", "profiles", "use", "e2e"]
        Util.run_subprocess(commands, error_message)

    def __install_plugin(self) -> None:
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

    def __reload_container(self) -> None:
        print("Restarting Logstash container after installing the plugin and updating pipeline config...")
        commands = ["docker", "restart", f"{self.LOGSTASH_CONTAINER_NAME}"]
        error_message = "Error occurred while reloading Logstash container, see logs for details."
        Util.run_subprocess(commands, error_message)

    def __update_pipeline_config(self) -> None:
        local_config_file = ".buildkite/scripts/e2e/config/pipeline.conf"
        container_config_file_path = "/usr/share/logstash/pipeline/logstash.conf"
        # python docker client (internally uses subprocess) requires special TAR header with tar operations
        commands = ["docker", "cp", f"{local_config_file}",
                    f"{self.LOGSTASH_CONTAINER_NAME}:{container_config_file_path}"]
        error_message = "Error occurred while replacing pipeline config, see logs for details."
        Util.run_subprocess(commands, error_message)

    def __spin_stack(self) -> None:
        # elastic-package stack up -d --version "${ELASTIC_STACK_VERSION}"
        commands = ["elastic-package", "stack", "up", "-d", "--version", self.stack_version]
        error_message = "Error occurred while running stacks with elastic-package. Check logs for details."
        try:
            Util.run_subprocess(commands, error_message)
        except Exception as ex:
            self.__teardown_stack()  # some containers left running, make sure to stop them

    def __teardown_stack(self) -> None:
        commands = ["elastic-package", "stack", "down"]
        error_message = "Error occurred while stopping stacks with elastic-package. Check logs for details."
        Util.run_subprocess(commands, error_message)

    def run_elastic_stack(self) -> None:
        """
        Downloads elastic-package, creates a profile and runs ELK, Fleet, ERP and elastic-agent
        """
        self.__download_elastic_package()
        self.__make_elastic_package_global()
        self.__clone_integrations_repo()
        self.__setup_elastic_package_profile()
        self.__spin_stack()
        self.__install_plugin()
        self.__update_pipeline_config()
        self.__reload_container()

    def stop_elastic_stack(self) -> None:
        print(f"Stopping elastic-package stack...")
        self.__teardown_stack()
