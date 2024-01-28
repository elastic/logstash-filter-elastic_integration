"""
A class to validate the Integration Plugin with a given integration package
"""
import subprocess
from logstash_stats import LogstashStats


class TestPlugin:
    LOGSTASH_STATS = LogstashStats()
    LAST_PROCESSED_EVENTS = {"in": 0, "out": 0}

    def __init__(self):
        pass

    def on(self, package):
        print(f"Testing the package: {package}")

        # `elastic-package test system` deploys current package
        # emits the data stream events, the process finishes when the package sends all available events
        result = subprocess.run(['elastic-package', 'test', 'system'])
        if result.returncode != 0:
            raise Exception(f"Error occurred while deploying the package and sending events. Check logs for details.")

        pipeline_stats = self.LOGSTASH_STATS.get()["pipelines"]["main"]
        integration_stats = [item for item in pipeline_stats.get("plugins", {}).get("filters", []) if item.get("name") == "elastic_integration"]
        if len(integration_stats) <= 0:
            raise Exception("Could not fetch elastic integration plugin stats.")

        processed_events = integration_stats[0]["events"]
        print(f"Found integration plugin current stats: {processed_events}")
        in_events = self.LAST_PROCESSED_EVENTS["in"] - processed_events["in"]
        out_events = self.LAST_PROCESSED_EVENTS["out"] - processed_events["out"]
        if in_events != out_events or out_events == 0:
            raise Exception(f"Processed events are not equal or events not processed, events, in: {in_events}, out: {out_events}")

        print(f"Test succeeded with: {package}")
        self.LAST_PROCESSED_EVENTS = processed_events

