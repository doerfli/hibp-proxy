require 'sidekiq'
require 'rest-client'
require 'date'
require 'googleauth'

Sidekiq.configure_client do |config|
  config.redis = { url: ENV['REDIS_URL'] }
end

$redis = Redis.new(url: ENV['REDIS_URL'])

API_KEY = ENV['HIBP_API_KEY']
REQUEST_INTERVAL = 2100
SERVICE_ACCOUNT_FILE = ENV['SERVICE_ACCOUNT_FILE']
FIREBASE_PROJECT_ID = ENV['FIREBASE_PROJECT_ID']

class BgWorker
  include Sidekiq::Worker
  def perform(email, device_token)
    sleepIfRequired

    puts "retrieving #{email}"
    url = "https://haveibeenpwned.com/api/v3/breachedaccount/#{email}"
    begin
      response = RestClient.get(url, 'Hibp-Api-Key' => API_KEY, :user_agent => "hibp-proxy_for_hacked_android_app")
      $redis.set("next_request_at", epoch_ms + REQUEST_INTERVAL)
      puts response
      # TODO push response via firebase
      send_response(device_token, response)
    rescue RestClient::NotFound
      puts "no breach found"
      $redis.set("next_request_at", epoch_ms + REQUEST_INTERVAL)
      send_response(device_token, '{}')
    rescue RestClient::TooManyRequests => e
      delay = e.response.headers[:retry_after].to_i
      puts "got 429 with requested delay #{delay}"
      $redis.set("next_request_at", epoch_ms + delay * 1000)
      raise e
    end
  end

  def sleepIfRequired
    next_request_at = $redis.get("next_request_at")

    unless next_request_at.nil?
      wait_time = next_request_at.to_i - epoch_ms
      if wait_time.positive?
        puts "waiting for #{wait_time}ms"
        s =  wait_time.to_f / 1000
        # puts s
        sleep(s)
      end
    end
  end

  def send_response(device_token, response)
    access_token = get_access_token
    url = "https://fcm.googleapis.com/v1/projects/#{FIREBASE_PROJECT_ID}/messages:send"
    response = RestClient.post(url,
                    {
                      message: {
                        token: device_token,
                        data: {
                          type: 'hibp-response',
                          response: response
                        }
                      }
                    }.to_json, {
                      content_type: :json,
                      Authorization: "Bearer #{access_token}",
                      Accept: :json
                    })
    puts response
  end

  def get_access_token
    scope = 'https://www.googleapis.com/auth/firebase.messaging'
    authorizer = Google::Auth::ServiceAccountCredentials.make_creds(
      json_key_io: File.open(SERVICE_ACCOUNT_FILE),
      scope: scope
    )
    authorizer.fetch_access_token!['access_token']
  end
end

def epoch_ms
  DateTime.now.strftime('%Q').to_i
end
