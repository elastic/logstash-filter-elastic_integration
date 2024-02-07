
## Getting started
E2E tests can be also run on your local machine.

### Prerequisites

#### Defining a platform
If you have Mac, you need to let the E2E know with
```bash
export E2E_PLATFORM = "macos"
```
Otherwise, it assumes test are being run on Linux platform.

#### Stack version
E2E also requires `STACK_VERSION` (ex: "8.12.0") environment variable in order to test against.
Make sure to export it before running. In the Buildkite pipeline, this var will be resolved and exported. 

#### Installing dependencies
Make sure you have python installed on you local
```bash
pip install -r requirements.txt
```

### Run
```bash
python3 main.py
```

## Troubleshooting