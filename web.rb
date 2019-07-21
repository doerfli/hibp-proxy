require 'sidekiq'
require 'sinatra/base'
require_relative 'worker'
require 'securerandom'
require "json"

Sidekiq.configure_client do |config|
  config.redis = { url: ENV['REDIS_URL'] }
end

$redis = Redis.new(url: ENV['REDIS_URL'])

class Web < Sinatra::Application
  get '/search' do
    req_id = SecureRandom.uuid
    email = params[:email]
    device_token = params[:device_token]

    if device_token.empty?
      puts "ERROR - device_token empty"
      return
    end

    key = "data_#{req_id}"
    puts "#{key} #{email} - #{device_token}"
    $redis.set(key, [email, device_token].to_json)
    BgWorker.perform_async(req_id)
    "enqueued request for #{device_token}"
  end
end
