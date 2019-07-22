require 'sidekiq'
require 'sinatra/base'
require_relative 'worker'
require 'securerandom'
require 'json'

Sidekiq.configure_client do |config|
  config.redis = { url: ENV['REDIS_URL'] }
end

$redis = Redis.new(url: ENV['REDIS_URL'])

class Web < Sinatra::Application
  get '/search' do
    req_id = SecureRandom.uuid
    account = params[:account]
    device_token = params[:device_token]

    if account.nil? || account.empty?
      puts 'ERROR - account empty'
      halt 400, 'account empty'
      return
    end

    if device_token.nil? || device_token.empty?
      puts 'ERROR - device_token empty'
      halt 400, 'device_token empty'
      return
    end

    key = "data_#{req_id}"
    puts "#{key} #{account} - #{device_token}"
    $redis.set(key, [account, device_token].to_json)
    BgWorker.perform_async(req_id)
    "enqueued request for #{device_token}"
  end
end
