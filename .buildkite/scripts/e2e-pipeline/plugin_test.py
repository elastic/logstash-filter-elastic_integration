"""
A class to validate the Integration Plugin with a given integration package
"""
import subprocess
import time
from logstash_stats import LogstashStats

class PluginTest:
    logstash_stats_api = LogstashStats()
    LAST_PROCESSED_EVENTS = {"in": 0, "out": 0}

    def __init__(self):
        pass

    def __analyze_logstash_throughput(self, package: str, elastic_package_result: subprocess.CompletedProcess) -> None:
        pipeline_stats = self.logstash_stats_api.get()["pipelines"]["main"]
        integration_stats = [item for item in pipeline_stats.get("plugins", {}).get("filters", []) if
                             item.get("name") == "elastic_integration"]
        if len(integration_stats) <= 0:
            raise Exception("Could not fetch elastic integration plugin stats.")

        processed_events = integration_stats[0]["events"]
        print(f"Found integration plugin current event stats: {processed_events}")
        in_events = processed_events["in"] - self.LAST_PROCESSED_EVENTS["in"]
        out_events = processed_events["out"] - self.LAST_PROCESSED_EVENTS["out"]

        if out_events == 0:
            if elastic_package_result.returncode != 0:
                raise Exception(f"events not processed, events, in: {in_events}, out: {out_events}")
            else:
                print("WARN: `elastic_integration` plugin didn't output events. This may happen when ingest pipeline "
                      "cancel the events or `elasticsearch-output` failed, check Logstash docker logs.")
        if in_events != out_events:
            if elastic_package_result.returncode != 0:
                raise Exception(f"processed events are not equal, events, in: {in_events}, out: {out_events}")
            else:
                print("WARN: in and out event count in `elastic_integration` differ. This may happen when ingest "
                      "pipeline cancel some events or `elasticsearch-output` failed, check Logstash docker logs.")

        print(f"Test succeeded with: {package}")
        self.LAST_PROCESSED_EVENTS = processed_events

    def on(self, package: str) -> None:
        print(f"Testing the package: {package}")

        # `elastic-package test system` deploys current package
        # emits the data stream events, the process finishes when the package sends all available events
        # note that `elastic-package test pipeline` is for validation purpose only
        result = subprocess.run(["elastic-package", "test", "system"], universal_newlines=True, stdout=subprocess.PIPE)
        if result.returncode != 0:
            # elastic-package also checks ES index if event is arrived, and compares with exp
            # sometimes tests may fail because of multiple reasons: timeout,
            # ecs needs to be disabled since event already has `event.original`, etc...
            print(f"Internal failure happened with {package}, process return code: {result.returncode}.")

            if result.stdout:
                # print line by line for better visibility
                for result_line in result.stdout.splitlines(): print(f"{result_line}")

        # although there was an error, le's check how LS performed and make sure errors weren't because of Logstash
        time.sleep(2) # make sure LS processes the event way to downstream ES
        self.__analyze_logstash_throughput(package, result)
