# HIBP API Proxy

This is a proxy for the haveibeenpwned.com API V3 function _Getting all breaches for an account_ (https://haveibeenpwned.com/API/v3#BreachesForAccount).
The result of the query is sent to an Android Device via Google Firebase Messaging. 

## Usage

Start the decoupled service using `forego web` and `forego worker`. 

Then send a HTTP `GET` request to `<hostname>:<port>/search?account=<account>&device_token=<firebase-device-token>`. 
The response is sent back to the device identified by the `device_token`. 

Docker container Redis start with `docker run -d --rm -p 32768:6379 redis` 

## Environment variables

To run this, the following environment variables need to be set

 * HIBP_API_KEY
 * REDIS_URL
 * FIREBASE_PROJECT_ID
 * GOOGLE_PRIVATE_KEY
 * GOOGLE_CLIENT_EMAIL
 * GOOGLE_CLIENT_ID
 * GOOGLE_ACCOUNT_TYPE
 * HIBP_PROXY_BASE_URL - the base url of the web application

The last four can be extracted from the `service-account-file.json` that is downloadable through the Firebase Console. 
