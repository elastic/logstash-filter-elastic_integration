"""
E2E bootstrapping with Python script
    - A script to resolve stack versions
    - Download and spin up elastic-package
    - Clone integrations repo and prepare packages
    - When E2E finishes, teardown the stack
"""
import glob
import os
import sys
import time
import util
import ruamel.yaml

YAML = ruamel.yaml.YAML()


class Bootstrap:
    ELASTIC_PACKAGE_DISTRO_URL = "https://api.github.com/repos/elastic/elastic-package/releases/latest"
    LOGSTASH_CONTAINER_NAME = "elastic-package-stack-e2e-logstash-1"
    PLUGIN_NAME = "logstash-filter-elastic_integration"

    SUPPORTED_PROCESSORS: list = [
        "append", "bytes", "community_id", "convert", "csv", "date", "date_index_name", "dissect", "dot_expander",
        "drop", "fail", "fingerprint", "foreach", "grok", "gsub", "html_strip", "join", "json", "kv", "lowercase",
        "network_direction", "pipeline", "registered_domain", "remove", "rename", "reroute", "script", "set",
        "sort", "split", "terminate", "trim", "uppercase", "uri_parts", "urldecode", "user_agent", "redact", "geoip"
    ]

    def __init__(self, stack_version: str, project_type: str) -> None:
        f"""
        A constructor of the {Bootstrap}.

        Args:
            stack_version: An Elastic stack version where {Bootstrap} spins up with
            project_type: type of the project running with, example serverless or on-prems

        Returns:
            Validates and sets stack version, project type and resolves elastic package distro based on running OS (sys.platform)
        """
        print(f"Stack version: {stack_version}")
        self.stack_version = stack_version
        self.__validate_and_set_project_type(project_type)
        self.__resolve_distro()

    def __validate_and_set_project_type(self, project_type: str):
        project_types = ["on_prems", "serverless"]
        if project_type not in project_types:
            raise ValueError(f"project_type accepts {project_types} only")
        self.project_type = project_type
        print(f"Project type: {project_type}")

    def __resolve_distro(self) -> None:
        print(f"Platform: {sys.platform}")
        platforms = ["darwin", "linux"]
        if sys.platform not in platforms:
            raise ValueError(f"Unsupported {sys.platform}, E2E can on {platforms} only")
        self.distro = "darwin_amd64.tar.gz" if sys.platform == "darwin" else "linux_amd64.tar.gz"

    def __download_elastic_package(self) -> None:
        response = util.call_url_with_retry(self.ELASTIC_PACKAGE_DISTRO_URL)
        release_info = response.json()

        download_urls = [asset["browser_download_url"] for asset in release_info["assets"]]
        download_url = [url for url in download_urls if self.distro in url][0]
        if download_url.strip() == '':
            raise Exception(f"Could not resolve elastic-package distro download URL, release info: {release_info}.")

        file_name = "downloaded_elastic_package_" + self.distro
        # downloading with `urllib3` gives a different size file which causes a corrupted file issue
        util.run_or_raise_error(["curl", "-o", file_name, "--retry", "5", "--retry-delay", "5", "-fSL", download_url],
                                "Failed to download elastic-package")
        print("elastic-package is successfully downloaded.")

        # Extract the downloaded tar.gz file
        util.run_or_raise_error(["tar", "zxf", file_name],
                                f"Error occurred while unzipping {file_name}")

    def __make_elastic_package_global(self) -> None:
        util.run_or_raise_error(["sudo", "mv", "elastic-package", "/usr/local/bin"],
                                "Could not make `elastic-package` global")

    def __clone_integrations_repo(self) -> None:
        util.run_or_raise_error(["retry", "-t", "3", "--", "git", "clone", "--single-branch",
                                 "https://github.com/elastic/integrations.git"],
                                "Error occurred while cloning an integrations repo. Check logs for details.")

    def __scan_for_unsupported_processors(self) -> None:
        curr_dir = os.getcwd()
        pipeline_definition_file_path = "integrations/packages/**/data_stream/**/elasticsearch/ingest_pipeline/*.yml"
        files = glob.glob(os.path.join(curr_dir, pipeline_definition_file_path))
        unsupported_processors: dict[list] = {}  # {processor_type: list<file>}

        for file in files:
            try:
                with open(file, "r") as f:
                    yaml_content = YAML.load(f)
                    processors = yaml_content.get("processors", [])

                    for processor in processors:
                        for processor_type, _ in processor.items():
                            if processor_type not in self.SUPPORTED_PROCESSORS:
                                if processor_type not in unsupported_processors:
                                    unsupported_processors[processor_type]: list = []
                                if file not in unsupported_processors[processor_type]:
                                    unsupported_processors[processor_type].append(file)
            except Exception as e:
                # Intentionally failing CI for better visibility
                # For the long term, creating a whitelist of unsupported processors (assuming _really_ cannot support)
                #   and skipping them by warning would be ideal approach.
                print(f"Failed to parse file: {file}. Error: {e}")

        if len(unsupported_processors) > 0:
            raise Exception(f"Unsupported processors found: {unsupported_processors}")

    def __get_profile_path(self) -> str:
        return os.path.join(util.get_home_path(), ".elastic-package/profiles/e2e")

    def __create_config_file(self, sample_config_file: str, config_file: str) -> None:
        with open(sample_config_file, "r") as infile, open(config_file, "w") as outfile:
            for line in infile:
                if "stack.logstash_enabled: true" in line:
                    # Logstash is disabled by default, remove the comment
                    line = line.lstrip('#').lstrip()
                outfile.write(line)

    def __setup_elastic_package_profile(self) -> None:
        # Although profile doesn't exist, profile delete process will get succeeded.
        util.run_or_raise_error(["elastic-package", "profiles", "delete", "e2e"],
                                "Error occurred while deleting and then creating a profile. Check logs for details.")

        util.run_or_raise_error(["elastic-package", "profiles", "create", "e2e"],
                                "Error occurred while creating a profile. Check logs for details.")

        print("`elastic-package` e2e profile created.")

        # elastic-package creates a profile under home directory
        config_example_file = os.path.join(self.__get_profile_path(), "config.yml.example")
        config_file = os.path.join(self.__get_profile_path(), "config.yml")
        self.__create_config_file(config_example_file, config_file)
        util.run_or_raise_error(["elastic-package", "profiles", "use", "e2e"],
                                "Error occurred while creating a profile. Check logs for details.")

    def __install_plugin(self) -> None:
        with open("VERSION", "r") as f:
            version = f.read()

        plugin_name = f"logstash-filter-elastic_integration-{version.strip()}-java.gem"
        util.run_or_raise_error(["docker", "cp", plugin_name, f"{self.LOGSTASH_CONTAINER_NAME}:/usr/share/logstash"],
                                "Error occurred while copying plugin to container, see logs for details.")

        print("Installing logstash-filter-elastic_integration plugin...")
        util.run_or_raise_error(
            ["docker", "exec", self.LOGSTASH_CONTAINER_NAME, "bin/logstash-plugin", "install", plugin_name],
            "Error occurred installing plugin in Logstash container")
        print("Plugin installed successfully.")

    def __reload_container(self) -> None:
        print("Restarting Logstash container after installing the plugin.")
        util.run_or_raise_error(["docker", "restart", f"{self.LOGSTASH_CONTAINER_NAME}"],
                                "Error occurred while reloading Logstash container, see logs for details.")
        time.sleep(20)  # give a time Logstash pipeline to fully start

    def __update_pipeline_config(self) -> None:
        local_config_file_path = ".buildkite/scripts/e2e-pipeline/config/"
        config_file = "serverless_pipeline.conf" if self.project_type == "serverless" else "pipeline.conf"
        local_config_file = local_config_file_path + config_file
        container_config_file_path = "/usr/share/logstash/pipeline/logstash.conf"
        # python docker client (internally uses subprocess) requires special TAR header with tar operations
        util.run_or_raise_error(["docker", "cp", f"{local_config_file}",
                                 f"{self.LOGSTASH_CONTAINER_NAME}:{container_config_file_path}"],
                                "Error occurred while replacing pipeline config, see logs for details")
        time.sleep(20)  # give a time Logstash pipeline to fully start

    def __spin_stack(self) -> None:
        try:
            # elastic-package stack up -d --version "${ELASTIC_STACK_VERSION}"
            commands = ["elastic-package", "stack", "up", "-d", "--version", self.stack_version]
            if self.project_type == "serverless":
                commands.extend(["--provider", "serverless"])
            util.run_or_raise_error(commands,
                                    "Error occurred while running stacks with elastic-package. Check logs for details.")
        except Exception as ex:
            self.__teardown_stack()  # some containers left running, make sure to stop them

    def __teardown_stack(self) -> None:
        util.run_or_raise_error(["elastic-package", "stack", "down"],
                                "Error occurred while stopping stacks with elastic-package. Check logs for details.")

    def run_elastic_stack(self, skip_setup=False) -> None:
        """
        Downloads elastic-package, creates a profile and runs ELK, Fleet, ERP and elastic-agent
        """
        if not skip_setup:
            self.__download_elastic_package()
            self.__make_elastic_package_global()
            self.__clone_integrations_repo()
            self.__scan_for_unsupported_processors()
            self.__setup_elastic_package_profile()
        self.__spin_stack()
        self.__install_plugin()
        self.__reload_container()
        self.__update_pipeline_config()

    def stop_elastic_stack(self) -> None:
        print(f"Stopping elastic-package stack...")
        self.__teardown_stack()
