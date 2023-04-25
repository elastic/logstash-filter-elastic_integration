#!/bin/bash
set -e

if [[ $1 == "macos" ]]; then
  download_url_ext="*darwin_amd64.tar.gz"
elif [[ $1 == "debian" ]]; then
  download_url_ext="*linux_amd64.tar.gz"
else
  echo "Invalid $1 platform. Please provide either macos or debian."
  exit 1
fi

# Pull elastic-package
curl -s https://api.github.com/repos/elastic/elastic-package/releases/latest |
  grep "browser_download_url.$download_url_ext" |
  cut -d : -f 2,3 |
  tr -d \" |
  wget -i -

echo "elastic-package is successfully downloaded."
tar -xvf *amd64.tar.gz