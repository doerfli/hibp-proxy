require 'sidekiq'
require 'rest-client'
require 'date'
require 'googleauth'
require 'json'
require 'uri'

def get_redis_url(baseurl)
  i = 0
  loop do
    begin
      url = "#{baseurl}/conf"
      response = RestClient.get(url)
      content = JSON.parse(response, symbolize_names: true)
      puts "new redis url #{content}"
      return content[:REDIS_URL]
    rescue
      puts 'waiting one second for server to finish starting'
      sleep 1
    end
    i += 1
    break if i >= 3
  end
end

redis_url = get_redis_url(ENV['HIBP_PROXY_BASE_URL'])
puts "using redis url #{redis_url}"

Sidekiq.configure_client do |config|
  config.redis = { url: redis_url }
end

Sidekiq.configure_server do |config|
  config.error_handlers << proc { |ex, _ctx_hash|
    puts "caught exception (#{ex.class}) ... reconfiguring db connection"
    if ex.is_a? Redis::CannotConnectError
      Sidekiq.configure_server do |config|
        new_redis_url = get_redis_url(ENV['HIBP_PROXY_BASE_URL'])
        config.redis = { url: new_redis_url }
        $redis = Redis.new(url: new_redis_url)
      end
    end
  }
end

$redis = Redis.new(url: redis_url)

API_KEY = ENV['HIBP_API_KEY']
REQUEST_INTERVAL = 1500
FIREBASE_PROJECT_ID = ENV['FIREBASE_PROJECT_ID']

class BgWorker
  include Sidekiq::Worker

  @@access_token = nil
  @@expiration = 0

  def perform(req_id)
    sleepIfRequired

    key = "data_#{req_id}"
    data_json = $redis.get(key)
    account, device_token = JSON.parse(data_json)

    if account.nil? || account.empty?
      puts "ERROR - account was empty"
      return
    end

    if device_token.nil? || device_token.empty?
      puts "ERROR - device_token for account #{account} was empty"
      return
    end

    #puts "#{key} checking #{account} / #{device_token}"
    url = "https://haveibeenpwned.com/api/v3/breachedaccount/#{URI::encode(account)}"
    #puts url

    begin
      response = RestClient.get(url, 'Hibp-Api-Key' => API_KEY, :user_agent => 'hibp-proxy_for_hacked_android_app')
      $redis.set("next_request_at", epoch_ms + REQUEST_INTERVAL)
      #puts response
      puts "response status 20x - successful"
      send_response(account, device_token, response)
      $redis.del(key)
    rescue RestClient::NotFound
      puts "response status 404 - no breach found"
      $redis.set("next_request_at", epoch_ms + REQUEST_INTERVAL)
      send_response(account, device_token, '[]')
      $redis.del(key)
    rescue RestClient::TooManyRequests => e
      delay = e.response.headers[:retry_after].to_i
      puts "WARN - response status 429 with requested delay #{delay}"
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

  def send_response(account, device_token, response)
    access_token = get_access_token
    url = "https://fcm.googleapis.com/v1/projects/#{FIREBASE_PROJECT_ID}/messages:send"
    response = RestClient.post(url,
                    {
                      message: {
                        token: device_token,
                        data: {
                          account: account,
                          type: 'hibp-response',
                          response: response
                        },
                        fcmOptions: {
                          analyticsLabel: "hibp-response"
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
