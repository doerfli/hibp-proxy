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
    #puts "#{email} - #{device_token}"
    $redis.set("data_#{req_id}", [email, device_token].to_json)
    BgWorker.perform_async(req_id)
    "enqueued request for #{device_token}"
  end
end
