import docker
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

def run_or_raise_error(commands: list, error_message):
    result = subprocess.run(commands, universal_newlines=True, stdout=subprocess.PIPE)
    if result.returncode != 0:
        full_error_message = (error_message + ", output: " + result.stdout.decode('utf-8')) \
            if result.stdout else error_message
        raise Exception(f"{full_error_message}")
