
## Getting started
E2E tests can be also run on your local machine.

### Prerequisites

#### Building the plugin
E2E tries to install the plugin on Logstash container from the gem file. To generate a gem:
1. Building plugin requires Logstash
- If you have `LOGSTASH_PATH` already defined, skip this step
- You can also download Logstash and export its path to `LOGSTASH_PATH`
- OR build from source
    ```shell
    git clone --single-branch https://github.com/elastic/logstash.git
    cd logstash && ./gradlew clean bootstrap assemble installDefaultGems && cd ..
    LOGSTASH_PATH=$(pwd)/logstash
    export LOGSTASH_PATH
    ```

2.Run the following command:
```shell
./gradlew clean vendor localGem
```

#### Defining a project type
If you want to run tests with serverless, this will be for you.
Defaults to `on_prems` where local stack containers will be spun up and tested.
```bash
export E2E_PROJECT_TYPE="serverless"
```

In order to run tests with serverless, you also need to export `EC_API_KEY` which is an organization API key to create a project.
In the pipelines, this will be automatically retrieved from Vault services.

#### Stack version
E2E also requires `STACK_VERSION` (ex: "8.12.0") environment variable in order to test against.
Make sure to export it before running. In the Buildkite pipeline, this var will be resolved and exported. 

#### Installing dependencies
Make sure you have python installed on you local
```bash
pip install -r .buildkite/scripts/e2e/requirements.txt
```

### Run
Run the following command from the repo dir:
```bash
python3 .buildkite/scripts/e2e/main.py
```

## Troubleshooting