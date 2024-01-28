"""
A class to provide information about Logstash node stats.
"""

from util import Util


class LogstashStats:
    LOGSTASH_STATS_URL = "http://localhost:9600/_node/stats"

    def __init__(self):
        pass

    def get(self):
        response = Util.call_url_with_retry(self.LOGSTASH_STATS_URL)
        return response.json()
