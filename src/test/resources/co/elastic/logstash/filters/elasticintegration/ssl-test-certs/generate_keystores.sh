#!/bin/bash
cd "$(dirname "$0")"

# warning: do not use the certificates produced by this tool in production.
# This is for testing purposes only
set -e

cd generated

# create pkcs12 truststore (pass:12345678)
keytool -importcert -storetype PKCS12 -keystore ca.p12 -storepass 12345678 -alias ca -file root.crt -noprompt

# use java keytool to convert all pkcs12 keystores to jks-format keystores (pass:12345678)
keytool -importkeystore -srckeystore client_from_root.p12 -srcstoretype pkcs12 -srcstorepass 12345678 -destkeystore client_from_root.jks -deststorepass 12345678 -alias client_from_root

# cleanup csr, we don't need them
rm -rf *.csr