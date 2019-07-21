require 'sidekiq'
require 'rest-client'
require 'date'
require 'googleauth'
require "json"

Sidekiq.configure_client do |config|
  config.redis = { url: ENV['REDIS_URL'] }
end

$redis = Redis.new(url: ENV['REDIS_URL'])

API_KEY = ENV['HIBP_API_KEY']
REQUEST_INTERVAL = 2100
FIREBASE_PROJECT_ID = ENV['FIREBASE_PROJECT_ID']

class BgWorker
  include Sidekiq::Worker

  @@access_token = nil
  @@expiration = 0

  def perform(req_id)
    sleepIfRequired

    key = "data_#{req_id}"
    data_json = $redis.get(key)
    email, device_token = JSON.parse(data_json)

    #puts "retrieving #{email}"
    url = "https://haveibeenpwned.com/api/v3/breachedaccount/#{email}"

    begin
      response = RestClient.get(url, 'Hibp-Api-Key' => API_KEY, :user_agent => "hibp-proxy_for_hacked_android_app")
      $redis.set("next_request_at", epoch_ms + REQUEST_INTERVAL)
      #puts response
      puts "response status 20x - successful"
      send_response(email, device_token, response)
      $redis.del(key)
    rescue RestClient::NotFound
      puts "response status 404 - no breach found"
      $redis.set("next_request_at", epoch_ms + REQUEST_INTERVAL)
      send_response(email, device_token, '[]')
      $redis.del(key)
    rescue RestClient::TooManyRequests => e
      delay = e.response.headers[:retry_after].to_i
      puts "response status 429 with requested delay #{delay}"
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

  def send_response(email, device_token, response)
    access_token = get_access_token
    url = "https://fcm.googleapis.com/v1/projects/#{FIREBASE_PROJECT_ID}/messages:send"
    response = RestClient.post(url,
                    {
                      message: {
                        token: device_token,
                        data: {
                          account: email,
                          type: 'hibp-response',
                          response: response
                        }
                      }
                    }.to_json, {
                      content_type: :json,
                      Authorization: "Bearer #{access_token}",
                      Accept: :json
                    })
    #puts response
    puts "fcm sent"
  end

  def get_access_token
    if epoch_ms > @@expiration
      puts "generating new access_token"
      scope = 'https://www.googleapis.com/auth/firebase.messaging'
      authorizer = Google::Auth::ServiceAccountCredentials.make_creds(
        scope: scope
      )
      r = authorizer.fetch_access_token!
      @@expiration = epoch_ms + r['expires_in'] * 1000 - 1000
      @@access_token = r['access_token']
    end
    @@access_token
  end
end

def epoch_ms
  DateTime.now.strftime('%Q').to_i
end
