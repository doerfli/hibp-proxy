https://github.com/doerfli/hibp-proxy/workflows/CI/badge.svg

# HIBP API Proxy

This is a proxy for the haveibeenpwned.com API V3 function _Getting all breaches for an account_ (https://haveibeenpwned.com/API/v3#BreachesForAccount).
The result of the query is sent to an Android Device via Google Firebase Messaging. 

## Usage

Then send a HTTP `GET` request to `<hostname>:<port>/search?account=<account>&device_token=<firebase-device-token>`. 
The response is sent back to the device identified by the `device_token`. 

## Environment variables

To run this, the following environment variables need to be set

 * HIBP_API_KEY
 * FIREBASE_CREDENTIALS - base64 encoded content of service-account-file.json (from google firebase console)

