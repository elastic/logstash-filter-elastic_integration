import os
import requests
from requests.adapters import HTTPAdapter, Retry


class Util:

    @staticmethod
    def get_home_path():
        return os.path.expanduser("~")

    @staticmethod
    def call_url_with_retry(url):
        schema = "https://" if "https://" in url else "http://"
        session = requests.Session()
        # retry on most common failures such as connection timeout(408), etc...
        retries = Retry(total=5, backoff_factor=1, status_forcelist=[408, 502, 503, 504])
        session.mount(schema, HTTPAdapter(max_retries=retries))
        return session.get(url)
