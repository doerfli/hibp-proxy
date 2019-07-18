require 'sidekiq'
require 'rest-client'
require 'date'


Sidekiq.configure_client do |config|
  config.redis = { url: ENV['REDIS_URL'] }
end

$redis = Redis.new(url: ENV['REDIS_URL'])

API_KEY = ENV['HIBP_API_KEY']
REQUEST_INTERVAL = 1550

class BgWorker
  include Sidekiq::Worker
  def perform(email)
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

    puts "retrieving #{email}"
    url = "https://haveibeenpwned.com/api/v3/breachedaccount/#{email}"
    begin
      response = RestClient.get(url, 'Hibp-Api-Key' => API_KEY)
      $redis.set("next_request_at", epoch_ms + REQUEST_INTERVAL)
      puts response
      # TODO push response via firebase 
    rescue RestClient::TooManyRequests => e
      delay = e.response.headers[:retry_after].to_i
      puts "got 429 with requested delay #{delay}"
      $redis.set("next_request_at", epoch_ms + delay * 1000)
      raise e
    end
  end
end

def epoch_ms
  DateTime.now.strftime('%Q').to_i
end
