import docker
import glob
import os
import requests
import subprocess
from docker.models.containers import Container
from requests.adapters import HTTPAdapter, Retry


def get_home_path() -> str:
    return os.path.expanduser("~")


def call_url_with_retry(url: str, max_retries: int = 5, delay: int = 1) -> requests.Response:
    schema = "https://" if "https://" in url else "http://"
    session = requests.Session()
    # retry on most common failures such as connection timeout(408), etc...
    retries = Retry(total=max_retries, backoff_factor=delay, status_forcelist=[408, 502, 503, 504])
    session.mount(schema, HTTPAdapter(max_retries=retries))
    return session.get(url)

def show_containers_logs(container_prefixes):
    client = docker.from_env()
    containers = client.containers.list(all=True)
    print(f"Available container names: {[c.name for c in containers]}")
    matching_containers = []
    for container in containers:
        if any(prefix in container.name for prefix in container_prefixes):
            matching_containers.append(container)
    
    if not matching_containers:
        prefixes_str = ", ".join(container_prefixes)
        print(f"No containers found with prefixes: {prefixes_str}")
        return

    for container in matching_containers:
        # pretty printing with clear separators
        separator = "=" * 80
        print(f"\n{separator}")
        print(f"Container: {container.name}")
        print(f"{separator}")
        container_logs = container.logs().decode('utf-8')
        for log_line in container_logs.splitlines():
            print(f"  {log_line}")
        print(f"{separator}\n")

def show_independent_agent_port_state():
    """Print port bindings of all stopped elastic-package independent agent containers.

    The elastic-package 'adding service container internal ports to context' step
    fails silently when expected ports are missing. This shows the container's actual
    port state at teardown time to diagnose port-mapping regressions (e.g. port conflicts
    with the stack's fleet-server on 8220).
    """
    client = docker.from_env()
    containers = client.containers.list(all=True)
    ep_agent_containers = [c for c in containers if "elastic-package-agent" in c.name]
    if not ep_agent_containers:
        print("No independent elastic-package agent containers found.")
        return
    for container in ep_agent_containers:
        separator = "=" * 80
        print(f"\n{separator}")
        print(f"Independent agent container: {container.name}  status: {container.status}")
        print(separator)
        attrs = container.attrs or {}
        ports = attrs.get("NetworkSettings", {}).get("Ports", {})
        if ports:
            for internal_port, bindings in ports.items():
                print(f"  {internal_port} -> {bindings}")
        else:
            print("  (no port bindings — this is likely the cause of the setup error)")
        print(separator)

def show_elastic_package_logs(working_dir: str):
    """Print log files written by elastic-package for independent test agent containers."""
    log_dir = os.path.join(working_dir, "integrations", "build", "container-logs")
    if not os.path.isdir(log_dir):
        print(f"No elastic-package container log directory found at: {log_dir}")
        return

    log_files = sorted(glob.glob(os.path.join(log_dir, "*.log")))
    if not log_files:
        print(f"No log files found in: {log_dir}")
        return

    for log_file in log_files:
        separator = "=" * 80
        print(f"\n{separator}")
        print(f"elastic-package container log: {os.path.basename(log_file)}")
        print(separator)
        try:
            with open(log_file, "r", errors="replace") as f:
                for line in f:
                    print(f"  {line}", end="")
        except Exception as e:
            print(f"  Could not read log file: {e}")
        print(f"\n{separator}\n")

def run_or_raise_error(commands: list, error_message):
    result = subprocess.run(commands, universal_newlines=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.returncode != 0:
        full_error_message = (error_message + ", output: " + result.stdout) \
            if result.stdout else error_message
        raise Exception(f"{full_error_message}")
